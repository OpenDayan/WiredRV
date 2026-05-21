package wiredrv.harness

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import difftest._
import difftest.common.DifftestMem
import lmss.axi._
import wiredrv._
import wiredrv.frontend._

class SimInterruptInjector extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val msip = Output(Bool())
    val mtip = Output(Bool())
    val meip = Output(Bool())
  })

  setInline("SimInterruptInjector.v",
    """module SimInterruptInjector(
      |  input         clock,
      |  input         reset,
      |  output        msip,
      |  output        mtip,
      |  output        meip
      |);
      |
      |reg [63:0] cycle;
      |reg [63:0] interrupt_start;
      |reg [63:0] interrupt_len;
      |reg [31:0] interrupt_source;
      |reg        interrupt_enable;
      |
      |initial begin
      |  cycle = 64'd0;
      |  interrupt_start = 64'd0;
      |  interrupt_len = 64'd1;
      |  interrupt_source = 32'd11;
      |  interrupt_enable = 1'b0;
      |  if ($value$plusargs("interrupt-start=%d", interrupt_start)) begin
      |    interrupt_enable = 1'b1;
      |  end
      |  if (!$value$plusargs("interrupt-len=%d", interrupt_len)) begin
      |    interrupt_len = 64'd1;
      |  end
      |  if (!$value$plusargs("interrupt-source=%d", interrupt_source)) begin
      |    interrupt_source = 32'd11;
      |  end
      |end
      |
      |always @(posedge clock) begin
      |  if (reset) begin
      |    cycle <= 64'd0;
      |  end else begin
      |    cycle <= cycle + 64'd1;
      |  end
      |end
      |
      |wire interrupt_valid = interrupt_enable &&
      |  (cycle >= interrupt_start) &&
      |  (cycle < (interrupt_start + interrupt_len));
      |
      |assign msip = interrupt_valid && interrupt_source == 32'd3;
      |assign mtip = interrupt_valid && interrupt_source == 32'd7;
      |assign meip = interrupt_valid && interrupt_source == 32'd11;
      |
      |endmodule
      |""".stripMargin)
}

class AxiDiffMem(axiP: AxiParams)(implicit p: CoreParams) extends Module {
  require(axiP.dataBits > 0 && (axiP.dataBits % 64 == 0 || axiP.dataBits == 32))

  val io = IO(new Bundle {
    val axi = Flipped(new AxiBundle(axiP))
  })

  private val lanes = if (axiP.dataBits <= 64) 1 else axiP.dataBits / 64
  private val beatBytes = axiP.dataBits / 8
  private val memBase = p.cachedRegions.head.base.U(p.addrBits.W)
  private val memBytes = p.cachedRegions.head.size
  private val mem = DifftestMem(memBytes, 8, 8, lanes, lanes)

  def memIndex(addr: UInt): UInt = (addr - memBase) >> 3
  def beatStep(size: UInt): UInt = (1.U(p.addrBits.W) << size)

  val wAwValid = RegInit(false.B)
  val wAw = Reg(new AWFlit(axiP))
  val wDataValid = RegInit(false.B)
  val wData = Reg(new WFlit(axiP))
  val wBValid = RegInit(false.B)
  val wBId = Reg(UInt(axiP.idBits.W))
  val wBUser = Reg(UInt(axiP.userBits.W))

  io.axi.aw.ready := !wAwValid && !wBValid
  io.axi.w.ready := !wDataValid && !wBValid

  when(io.axi.aw.fire) {
    wAwValid := true.B
    wAw := io.axi.aw.bits
  }
  when(io.axi.w.fire) {
    wDataValid := true.B
    wData := io.axi.w.bits
  }

  val writeAwValid = wAwValid || io.axi.aw.fire
  val writeDataValid = wDataValid || io.axi.w.fire
  val writeAw = Mux(wAwValid, wAw, io.axi.aw.bits)
  val writeData = Mux(wDataValid, wData, io.axi.w.bits)
  val writeFire = !wBValid && writeAwValid && writeDataValid
  val writeAlignedAddr = Cat(writeAw.addr(p.addrBits - 1, log2Ceil(beatBytes)), 0.U(log2Ceil(beatBytes).W))
  val writeBytes = writeData.data.asTypeOf(Vec(axiP.dataBits / 8, UInt(8.W)))

  for (i <- 0 until lanes) {
    mem.write(i).valid := writeFire
    mem.write(i).index := memIndex(writeAlignedAddr + (i * 8).U)
    mem.write(i).data := VecInit((0 until 8).map(j => writeBytes(i * 8 + j))).asTypeOf(mem.write(i).data)
    mem.write(i).mask := VecInit((0 until 8).map(j => Fill(8, writeData.strb(i * 8 + j)))).asTypeOf(mem.write(i).mask)
  }

  when(writeFire) {
    wAwValid := false.B
    wDataValid := false.B
    wBValid := true.B
    wBId := writeAw.id
    wBUser := writeAw.user
  }
  when(io.axi.b.fire) {
    wBValid := false.B
  }

  io.axi.b.valid := wBValid
  io.axi.b.bits.id := wBId
  io.axi.b.bits.resp := 0.U
  io.axi.b.bits.user := wBUser

  val rIdle :: rWait :: rResp :: Nil = Enum(3)
  val rState = RegInit(rIdle)
  val rAddr = Reg(UInt(p.addrBits.W))
  val rLenLeft = Reg(UInt(axiP.lenBits.W))
  val rSize = Reg(UInt(axiP.sizeBits.W))
  val rId = Reg(UInt(axiP.idBits.W))
  val rUser = Reg(UInt(axiP.userBits.W))

  io.axi.ar.ready := rState === rIdle
  when(io.axi.ar.fire) {
    rState := rWait
    rAddr := io.axi.ar.bits.addr
    rLenLeft := io.axi.ar.bits.len
    rSize := io.axi.ar.bits.size
    rId := io.axi.ar.bits.id
    rUser := io.axi.ar.bits.user
  }
  when(rState === rWait) {
    rState := rResp
  }

  val nextReadAddr = rAddr + beatStep(rSize)
  val readAddr = Mux(rState === rResp && io.axi.r.fire && rLenLeft =/= 0.U, nextReadAddr, rAddr)
  val alignedAddr = Cat(readAddr(p.addrBits - 1, log2Ceil(beatBytes)), 0.U(log2Ceil(beatBytes).W))
  val readDataLanes = Wire(Vec(lanes, UInt(64.W)))
  for (i <- 0 until lanes) {
    readDataLanes(i) := mem.readAndHold(memIndex(alignedAddr + (i * 8).U), rState === rWait || rState === rResp).asUInt
  }
  val readDataWide = Cat((lanes - 1 to 0 by -1).map(readDataLanes(_)))

  io.axi.r.valid := rState === rResp
  io.axi.r.bits.id := rId
  io.axi.r.bits.data := readDataWide(axiP.dataBits - 1, 0)
  io.axi.r.bits.resp := 0.U
  io.axi.r.bits.last := Mux(rLenLeft === 0.U, 1.U, 0.U)
  io.axi.r.bits.user := rUser

  when(io.axi.r.fire) {
    when(rLenLeft === 0.U) {
      rState := rIdle
    }.otherwise {
      rAddr := nextReadAddr
      rLenLeft := rLenLeft - 1.U
    }
  }
}

class WiredCoreHarnessTop(implicit p: CoreParams) extends Module with HasDiffTestInterfaces {

  val io = IO(new Bundle {
    val inst = Valid(UInt(32.W))
    val pc = UInt(p.addrBits.W)
  })
  val uart = IO(new UARTIO)
  val simExitOut = IO(Output(Bool()))

  val d0 = DifftestModule(new DiffArchEvent, delay = 1, dontCare = true)
  val d1 = DifftestModule(new DiffPhyIntRegState(32), delay = 1, dontCare = true)
  val d2 = DifftestModule(new DiffCSRState, delay = 1, dontCare = true)
  val d3 = DifftestModule(new DiffInstrCommit, delay = 1, dontCare = true)
  val d4 = DifftestModule(new DiffTrapEvent, delay = 1, dontCare = true)
  val d5 = DifftestModule(new DiffArchIntDelayedUpdate, delay = 1, dontCare = true)

  val core = Module(new WiredCore)
  val interruptInjector = Module(new SimInterruptInjector)
  val mem = Module(new AxiDiffMem(AxiSlvParamsCalc(Seq(
    AxiParams(addrBits = p.addrBits, idBits = 4, dataBits = p.outerBusWidth),
    AxiParams(addrBits = p.addrBits, idBits = 4, dataBits = p.outerBusWidth)
  ))))
  val r = RegInit(0.B)
  val cycleCnt = RegInit(0.U(64.W))
  r := !r
  // core.io.inst.ready := r
  core.io.inst.ready := 1.B
  interruptInjector.io.clock := clock
  interruptInjector.io.reset := reset.asBool
  core.io.hartId := 0.U
  core.io.msip := interruptInjector.io.msip
  core.io.mtip := interruptInjector.io.mtip
  core.io.meip := interruptInjector.io.meip
  core.io.debug := false.B
  core.io.time.valid := true.B
  core.io.time.bits := cycleCnt
  mem.io.axi <> core.io.mem

  val commitValid = core.io.diffCommitValid
  val simExit = commitValid && core.io.diffCommitInst === "h0000006b".U
  simExitOut := simExit
  val instrCnt = RegInit(0.U(64.W))
  val instrCntNext = instrCnt + commitValid.asUInt

  cycleCnt := cycleCnt + 1.U
  when(commitValid) {
    instrCnt := instrCntNext
  }

  d0.coreid := 0.U
  d0.valid := core.io.diffTrapValid
  d0.interrupt := Mux(core.io.diffTrapIsInterrupt, core.io.diffTrapCode(31, 0), 0.U)
  d0.exception := Mux(core.io.diffTrapIsInterrupt, 0.U, core.io.diffTrapCode(31, 0))
  d0.exceptionPC := core.io.diffTrapPc
  d0.exceptionInst := core.io.diffTrapInst
  d0.hasNMI := false.B
  d0.virtualInterruptIsHvictlInject := false.B
  d0.irToHS := false.B
  d0.irToVS := false.B

  d1.coreid := 0.U
  d1.value := core.io.diffRf

  d2.coreid := 0.U
  d2.privilegeMode := core.io.diffCsr.privilegeMode
  d2.mstatus := core.io.diffCsr.mstatus
  d2.sstatus := core.io.diffCsr.sstatus
  d2.mepc := core.io.diffCsr.mepc
  d2.sepc := core.io.diffCsr.sepc
  d2.mtval := core.io.diffCsr.mtval
  d2.stval := core.io.diffCsr.stval
  d2.mtvec := core.io.diffCsr.mtvec
  d2.stvec := core.io.diffCsr.stvec
  d2.mcause := core.io.diffCsr.mcause
  d2.scause := core.io.diffCsr.scause
  d2.satp := core.io.diffCsr.satp
  d2.mip := core.io.diffCsr.mip
  d2.mie := core.io.diffCsr.mie
  d2.mscratch := core.io.diffCsr.mscratch
  d2.sscratch := core.io.diffCsr.sscratch
  d2.mideleg := core.io.diffCsr.mideleg
  d2.medeleg := core.io.diffCsr.medeleg

  d3.coreid := 0.U
  d3.index := 0.U
  d3.valid := commitValid
  d3.skip := false.B
  d3.isRVC := false.B
  d3.rfwen := core.io.diffCommitRfWen
  d3.fpwen := false.B
  d3.vecwen := false.B
  d3.v0wen := false.B
  d3.wpdest := core.io.diffCommitRd
  d3.wdest := core.io.diffCommitRd
  d3.otherwpdest := VecInit(Seq.fill(16)(0.U(d3.wpdest.getWidth.W)))
  d3.pc := core.io.diffCommitPc
  d3.instr := core.io.diffCommitInst
  d3.robIdx := 0.U
  d3.lqIdx := 0.U
  d3.sqIdx := 0.U
  d3.isLoad := core.io.diffCommitIsLoad
  d3.isStore := core.io.diffCommitIsStore
  d3.nFused := 0.U
  d3.setSpecial(isDelayedWb = core.io.diffCommitIsDelayedWb, isExit = simExit)

  d4.coreid := 0.U
  d4.hasTrap := simExit
  d4.cycleCnt := cycleCnt
  d4.instrCnt := instrCntNext
  d4.hasWFI := false.B
  d4.code := core.io.diffRf(10)
  d4.pc := core.io.diffCommitPc

  when(simExit) {
    printf("[perf] frontend cycles=%d instFire=%d backpressure=%d icacheRefill=%d icacheRefillCycles=%d backendRedirect=%d\n",
      core.io.frontendPerf.cycles,
      core.io.frontendPerf.instFire,
      core.io.frontendPerf.backpressure,
      core.io.frontendPerf.icacheRefill,
      core.io.frontendPerf.icacheRefillCycles,
      core.io.frontendPerf.backendRedirect)
    printf("[perf] icache hit=%d refillDone=%d replay=%d missSetHasValid=%d missSetFull=%d jalPred=%d branchPred=%d ifencei=%d\n",
      core.io.frontendPerf.icacheHit,
      core.io.frontendPerf.icacheRefillDone,
      core.io.frontendPerf.icacheReplay,
      core.io.frontendPerf.icacheMissSetHasValid,
      core.io.frontendPerf.icacheMissSetFull,
      core.io.frontendPerf.jalPred,
      core.io.frontendPerf.branchPred,
      core.io.frontendPerf.ifencei)
    printf("[perf] backend cycles=%d commits=%d dStall=%d branchRedirect=%d dcacheMiss=%d uncachedAccess=%d mulStart=%d divStart=%d\n",
      core.io.backendPerf.cycles,
      core.io.backendPerf.commits,
      core.io.backendPerf.dStall,
      core.io.backendPerf.branchRedirect,
      core.io.backendPerf.dcacheMiss,
      core.io.backendPerf.uncachedAccess,
      core.io.backendPerf.mulStart,
      core.io.backendPerf.divStart)
    printf("[perf] stall rs1Var=%d rs2Var=%d rdVar=%d lsuBase=%d jr=%d noVslot=%d divBusy=%d cacheBusy=%d uncachedBusy=%d\n",
      core.io.backendPerf.dRs1VarHaz,
      core.io.backendPerf.dRs2VarHaz,
      core.io.backendPerf.dRdVarHaz,
      core.io.backendPerf.dLsuBaseNotReady,
      core.io.backendPerf.dJrNotReady,
      core.io.backendPerf.dNoVslot,
      core.io.backendPerf.dDivResourceBusy,
      core.io.backendPerf.dCacheBusy,
      core.io.backendPerf.dUncachedBusy)
  }

  d5.coreid := 0.U
  d5.index := 0.U
  d5.valid := core.io.diffDelayedWbValid
  d5.address := core.io.diffDelayedWbRd
  d5.data := core.io.diffDelayedWbData
  d5.nack := false.B

  io.pc := core.io.inst.bits.pc
  io.inst.valid := core.io.inst.fire
  io.inst.bits := core.io.inst.bits.inst

  uart.out.valid := core.io.simUart.valid
  uart.out.ch := core.io.simUart.bits
  uart.in.valid := false.B

  override def connectTopIOs(difftest: DifftestTopIO): Unit = {
    difftest.exit := Mux(simExitOut, Fill(64, 1.U), 0.U)
    difftest.uart <> uart
  }
}

object WiredCoreHarnessTopMain extends App {
  private def isEnabled(value: String): Boolean =
    value == "1" || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes")

  private val simUncachedInRam =
    sys.props.get("sim-uncached-in-ram").exists(isEnabled) ||
      sys.env.get("SIM_UNCACHED_IN_RAM").exists(isEnabled)

  implicit val p: CoreParams = {
    val base = CoreParams()
    if (simUncachedInRam) {
      base.copy(uncachedRegions = Seq(AddrRegion(0x80001000L, 0x1000L)))
    } else {
      base
    }
  }
  ChiselStage.emitSystemVerilog(
    DifftestModule.top(new WiredCoreHarnessTop()),
    firtoolOpts = Array(
      "--mlir-disable-threading",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=build/rtl"
    )
  )
}
