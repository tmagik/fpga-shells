// See LICENSE for license details.
package sifive.fpgashells.shell.microsemi

import chisel3._
import chisel3.experimental.{IO, withClockAndReset}
import freechips.rocketchip.config._
import freechips.rocketchip.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import sifive.fpgashells.clocks._
import sifive.fpgashells.shell._
import sifive.fpgashells.ip.microsemi._
import sifive.blocks.devices.chiplink._

import sifive.fpgashells.devices.microsemi.polarfireevalkitpciex4._
import sifive.fpgashells.devices.microsemi.polarfireddr4._

import sifive.fpgashells.ip.microsemi._
import sifive.fpgashells.ip.microsemi.polarfirexcvrrefclk._
import sifive.fpgashells.ip.microsemi.polarfiretxpll._
import sifive.fpgashells.ip.microsemi.polarfire_oscillator._
import sifive.fpgashells.ip.microsemi.polarfireclockdivider._
import sifive.fpgashells.ip.microsemi.polarfireglitchlessmux._
import sifive.fpgashells.ip.microsemi.polarfirereset._

class SysClockVeraOverlay(val shell: VeraShell, val name: String, params: ClockInputOverlayParams)
  extends ClockInputMicrosemiOverlay(params)
{
  val node = shell { ClockSourceNode(name, freqMHz = 50, jitterPS = 50)(ValName(name)) }

  shell { InModuleBody {
    val (c, _) = node.out(0)
    c.reset := shell.pllReset
    shell.io_pdc.addPin(io:Clock, "AG4")
  } }
}

class LEDVeraOverlay(val shell: VeraShell, val name: String, params: LEDOverlayParams)
  extends LEDMicrosemiOverlay(params, Seq("AK17", "AN17", "AM17", "AL18"))

class ChipLinkVeraOverlay(val shell: VeraShell, val name: String, params: ChipLinkOverlayParams)
  extends ChipLinkPolarFireOverlay(params)
{
  val ereset_n = shell { InModuleBody {
    val ereset_n = IO(Input(Bool()))
    ereset_n.suggestName("ereset_n")
    shell.io_pdc.addPin(ereset_n, "U5")
    ereset_n
  } }

  shell { InModuleBody {
    val dir1 = Seq("U10", "AC8", "U9", /* clk, rst, send */
                   "V13", "W13", "T5",  "T4",  "V14", "W14", "R3",  "R2",
                   "W6",  "Y6",  "U2",  "U1",  "T3",  "T2",  "W1",  "Y1",
                   "V3",  "V4",  "AA4", "AA5", "V1",  "V2",  "Y3",  "Y2",
                   "W4",  "W3",  "AA3", "AA2", "Y7",  "AA7", "AB2", "AB1")
    val dir2 = Seq("T9",  "AD5", "AD3", /* clk, rst, send */
                   "AB9", "AA8", "AB5", "AC4", "AC1", "AD1", "AB4", "AC3",
                   "W10", "Y10", "AB7", "AB6", "W8",  "Y8",  "Y12", "Y13",
                   "AA10","AA9", "W11", "Y11", "AC7", "AC6", "AA14","AA13",
                   "AB11","AB10","AB14","AC13","AC11","AC12","AB12","AA12")
    val dirB2C = Seq(IOPin(io.b2c.clk), IOPin(io.b2c.rst), IOPin(io.b2c.send)) ++
                 IOPin.of(io.b2c.data)
    val dirC2B = Seq(IOPin(io.c2b.clk), IOPin(io.c2b.rst), IOPin(io.c2b.send)) ++
                 IOPin.of(io.c2b.data)
    (dirB2C zip dir1) foreach { case (io, pin) => shell.io_pdc.addPin(io, pin) }
    (dirC2B zip dir2) foreach { case (io, pin) => shell.io_pdc.addPin(io, pin) }

    val (rx, _) = rxI.out(0)
    rx.reset := shell.pllReset
  } }
}

class PCIeVeraOverlay(val shell: VeraShell, val name: String, params: PCIeOverlayParams)
  extends PCIeOverlay[PolarFireEvalKitPCIeX4Pads](params)
{
  val sdcClockName = "axiClock"
  val pcie = LazyModule(new PolarFireEvalKitPCIeX4)
  val ioNode = BundleBridgeSource(() => pcie.module.io.cloneType)
  val topIONode = shell { ioNode.makeSink() }
  val pcieClk_125 = shell { ClockSinkNode(freqMHz = 125)}
  val pcieGroup = shell { ClockGroup()}
  pcieClk_125 := params.wrangler := pcieGroup := params.corePLL

  val slaveSide = TLIdentityNode()
  pcie.crossTLIn(pcie.slave) := slaveSide
  pcie.crossTLIn(pcie.control) := slaveSide
  val node = NodeHandle(slaveSide, pcie.crossTLOut(pcie.master))
  val intnode = pcie.crossIntOut(pcie.intnode)

  def designOutput = (node, intnode)
  def ioFactory = new PolarFireEvalKitPCIeX4Pads

  InModuleBody { ioNode.bundle <> pcie.module.io }
/*
  val pf_rstb = shell { InModuleBody { 
    pf_rstb
  } }
*/

  shell { InModuleBody {
    val (sys, _) = shell.sys_clock.get.node.out(0)
    val (pcieClk, _) = pcieClk_125.in(0)
    val port = topIONode.bundle.port
    val coreClock = shell.pllFactory.plls.getWrappedValue(1)._1.getClocks(0)
    io <> port


    val refClk = Module(new PolarFireTransceiverRefClk)
    val pcie_tx_pll = Module(new PolarFireTxPLL)
    val pf_osc = Module(new PolarFireOscillator)
    val pf_clk_div = Module(new PolarFireClockDivider)
    val pf_gless_mux = Module(new PolarFireGlitchlessMux)

    val pf_reset = Module(new PolarFireReset)
    pf_reset.io.CLK      := coreClock
    pf_reset.io.PLL_LOCK := shell.pllFactory.plls.getWrappedValue(1)._1.getLocked
    pf_reset.io.INIT_DONE := shell.initMonitor.io.DEVICE_INIT_DONE
    //pf_reset.io.EXT_RST_N := shell.chiplink.getWrappedValue.get.ereset_n
    pf_reset.io.SS_BUSY := false.B
    pf_reset.io.FF_US_RESTORE := false.B
    val sys_reset_n = pf_reset.io.FABRIC_RESET_N
 
    val osc_rc160mhz = pf_osc.io.RCOSC_160MHZ_GL

    pf_clk_div.io.CLK_IN := osc_rc160mhz
    pf_gless_mux.io.CLK0 := pf_clk_div.io.CLK_OUT
    pf_gless_mux.io.CLK1 := pcieClk.clock
    pf_gless_mux.io.SEL  := shell.initMonitor.io.PCIE_INIT_DONE

    refClk.io.REF_CLK_PAD_P := io.REFCLK_rxp
    refClk.io.REF_CLK_PAD_N := io.REFCLK_rxn
    val pcie_fab_ref_clk = refClk.io.FAB_REF_CLK
    pcie_tx_pll.io.REF_CLK := refClk.io.REF_CLK

    val pf_rstb = IO(Output(Bool()))
    pf_rstb.suggestName("pf_rstb")
    val perst_x1_slot = IO(Output(Bool()))
    perst_x1_slot.suggestName("perst_x1_slot")
    val perst_x16_slot = IO(Output(Bool()))
    perst_x16_slot.suggestName("perst_x16_slot")
    val perst_m2_slot = IO(Output(Bool()))
    perst_m2_slot.suggestName("perst_m2_slot")
    val perst_sata_slot = IO(Output(Bool()))
    perst_sata_slot.suggestName("perst_sata_slot")

    withClockAndReset(coreClock, !sys_reset_n) {
      val timer = RegInit(UInt(268435456, width=29))
      timer := timer - timer.orR
      val useReset = (!pf_reset.io.FABRIC_RESET_N || timer.orR)
      val pf_rstb_i = !ResetCatchAndSync(refClk.io.FAB_REF_CLK, useReset, name = Some("rcas"))

      pf_rstb := pf_rstb_i
      perst_x1_slot := pf_rstb_i
      perst_x16_slot := pf_rstb_i
      perst_m2_slot := pf_rstb_i
      perst_sata_slot := pf_rstb_i
    }

    val led_test_0 = IO(Output(Bool()))
    led_test_0.suggestName("led0")
    val led_test_1 = IO(Output(Bool()))
    led_test_1.suggestName("led1")
    val led_test_2 = IO(Output(Bool()))
    led_test_2.suggestName("led2")
    val led_test_3 = IO(Output(Bool()))
    led_test_3.suggestName("led3")
    led_test_0 := coreClock.asUInt
    led_test_1 := shell.initMonitor.io.PCIE_INIT_DONE
    led_test_2 := !sys_reset_n
    led_test_3 := shell.pllFactory.plls.getWrappedValue(1)._1.getLocked

    port.APB_S_PCLK                  := coreClock
    port.APB_S_PRESET_N              := true.B
    port.AXI_CLK                     := coreClock
    port.AXI_CLK_STABLE              := shell.pllFactory.plls.getWrappedValue(1)._1.getLocked //TODO: how to get correct number?
    port.PCIE_1_TL_CLK_125MHz        := pf_gless_mux.io.CLK_OUT
    port.PCIE_1_TX_PLL_REF_CLK       := pcie_tx_pll.io.REF_CLK_TO_LANE
    port.PCIE_1_TX_BIT_CLK           := pcie_tx_pll.io.BIT_CLK
    port.PCIESS_LANE0_CDR_REF_CLK_0  := refClk.io.REF_CLK
    port.PCIESS_LANE1_CDR_REF_CLK_0  := refClk.io.REF_CLK
    port.PCIESS_LANE2_CDR_REF_CLK_0  := refClk.io.REF_CLK
    port.PCIESS_LANE3_CDR_REF_CLK_0  := refClk.io.REF_CLK
    port.PCIE_1_TX_PLL_LOCK          := pcie_tx_pll.io.LOCK

    shell.io_pdc.addPin(led_test_0, "AK17")
    shell.io_pdc.addPin(led_test_1, "AN17")
    shell.io_pdc.addPin(led_test_2, "AM17")
    shell.io_pdc.addPin(led_test_3, "AL18")
    shell.io_pdc.addPin(pf_rstb, "AG15")
    shell.io_pdc.addPin(perst_x1_slot, "B4", ioStandard = "LVCMOS33")
    shell.io_pdc.addPin(perst_x16_slot, "A4", ioStandard = "LVCMOS33")
    shell.io_pdc.addPin(perst_m2_slot, "B5", ioStandard = "LVCMOS33")
    shell.io_pdc.addPin(perst_sata_slot, "A5", ioStandard = "LVCMOS33")
    shell.io_pdc.addPin(io.REFCLK_rxp, "W27")
    shell.io_pdc.addPin(io.REFCLK_rxn, "W28")
    shell.io_pdc.addPin(io.PCIESS_LANE_TXD0_N, "V34")
    shell.io_pdc.addPin(io.PCIESS_LANE_TXD0_P, "V33")
    shell.io_pdc.addPin(io.PCIESS_LANE_TXD1_N, "Y34")
    shell.io_pdc.addPin(io.PCIESS_LANE_TXD1_P, "Y33")
    shell.io_pdc.addPin(io.PCIESS_LANE_TXD2_N, "AA32")
    shell.io_pdc.addPin(io.PCIESS_LANE_TXD2_P, "AA31")
    shell.io_pdc.addPin(io.PCIESS_LANE_TXD3_N, "AB34")
    shell.io_pdc.addPin(io.PCIESS_LANE_TXD3_P, "AB33")
    shell.io_pdc.addPin(io.PCIESS_LANE_RXD0_N, "V30")
    shell.io_pdc.addPin(io.PCIESS_LANE_RXD0_P, "V29")
    shell.io_pdc.addPin(io.PCIESS_LANE_RXD1_N, "W32")
    shell.io_pdc.addPin(io.PCIESS_LANE_RXD1_P, "W31")
    shell.io_pdc.addPin(io.PCIESS_LANE_RXD2_N, "Y30")
    shell.io_pdc.addPin(io.PCIESS_LANE_RXD2_P, "Y29")
    shell.io_pdc.addPin(io.PCIESS_LANE_RXD3_N, "AB30")
    shell.io_pdc.addPin(io.PCIESS_LANE_RXD3_P, "AB29")

    shell.sdc.addClock(s"${name}_ref_clk", io.REFCLK_rxp, 100)
  } }

  shell.sdc.addGroup(clocks = Seq("osc_rc160mhz"))
}

class VeraShell()(implicit p: Parameters) extends PolarFireShell
{
  // PLL reset causes
  val pllReset = InModuleBody { Wire(Bool()) }

  val sys_clock = Overlay(ClockInputOverlayKey)(new SysClockVeraOverlay(_, _, _))
//  val led       = Overlay(LEDOverlayKey)       (new LEDVeraOverlay     (_, _, _))
  val chiplink  = Overlay(ChipLinkOverlayKey)  (new ChipLinkVeraOverlay(_, _, _))
  val pcie      = Overlay(PCIeOverlayKey)      (new PCIeVeraOverlay    (_, _, _))

  val topDesign = LazyModule(p(DesignKey)(designParameters))

  override lazy val module = new LazyRawModuleImp(this) {
    val pf_user_reset_n = IO(Input(Bool()))
    io_pdc.addPin(pf_user_reset_n, "AK18")

    pllReset :=
      !pf_user_reset_n ||
      !initMonitor.io.DEVICE_INIT_DONE ||
      chiplink.map(!_.ereset_n).getOrElse(false.B)

  }
}
