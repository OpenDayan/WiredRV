package wiredrv.harness

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import difftest._
import difftest.common.DifftestMem
import lmss.axi._
import wiredrv._
import wiredrv.backend.TensorResp
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

class SimPerfPrinter extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val enable = Input(Bool())
    val exit_pc = Input(UInt(64.W))
    val exit_code = Input(UInt(64.W))

    val frontend_cycles = Input(UInt(64.W))
    val frontend_instFire = Input(UInt(64.W))
    val frontend_backpressure = Input(UInt(64.W))
    val frontend_frontStall = Input(UInt(64.W))
    val frontend_fetchQueueFull = Input(UInt(64.W))
    val frontend_icacheHit = Input(UInt(64.W))
    val frontend_icacheRefill = Input(UInt(64.W))
    val frontend_icacheRefillDone = Input(UInt(64.W))
    val frontend_icacheRefillCycles = Input(UInt(64.W))
    val frontend_icacheRefillReqStall = Input(UInt(64.W))
    val frontend_icacheRefillRespWait = Input(UInt(64.W))
    val frontend_icacheReplay = Input(UInt(64.W))
    val frontend_respQueueFlush = Input(UInt(64.W))
    val frontend_icacheMissSetHasValid = Input(UInt(64.W))
    val frontend_icacheMissSetFull = Input(UInt(64.W))
    val frontend_jalPred = Input(UInt(64.W))
    val frontend_branchPred = Input(UInt(64.W))
    val frontend_ifencei = Input(UInt(64.W))
    val frontend_backendRedirect = Input(UInt(64.W))

    val backend_cycles = Input(UInt(64.W))
    val backend_commits = Input(UInt(64.W))
    val backend_dStall = Input(UInt(64.W))
    val backend_dRs1VarHaz = Input(UInt(64.W))
    val backend_dRs2VarHaz = Input(UInt(64.W))
    val backend_dRdVarHaz = Input(UInt(64.W))
    val backend_dERawHaz = Input(UInt(64.W))
    val backend_dWRawHaz = Input(UInt(64.W))
    val backend_dLsuBaseNotReady = Input(UInt(64.W))
    val backend_dJrNotReady = Input(UInt(64.W))
    val backend_dNoVslot = Input(UInt(64.W))
    val backend_dDivResourceBusy = Input(UInt(64.W))
    val backend_dCacheBusy = Input(UInt(64.W))
    val backend_dUncachedBusy = Input(UInt(64.W))
    val backend_dStoreQueueWait = Input(UInt(64.W))
    val backend_dInflightStoreWait = Input(UInt(64.W))
    val backend_dFenceWait = Input(UInt(64.W))
    val backend_dMisalignedWait = Input(UInt(64.W))
    val backend_dNemuTrapWait = Input(UInt(64.W))
    val backend_loadCommit = Input(UInt(64.W))
    val backend_storeCommit = Input(UInt(64.W))
    val backend_jumpCommit = Input(UInt(64.W))
    val backend_branchCommit = Input(UInt(64.W))
    val backend_branchRedirect = Input(UInt(64.W))
    val backend_dcacheLoadHit = Input(UInt(64.W))
    val backend_dcacheStoreHit = Input(UInt(64.W))
    val backend_dcacheMiss = Input(UInt(64.W))
    val backend_dcacheMissLoad = Input(UInt(64.W))
    val backend_dcacheMissStore = Input(UInt(64.W))
    val backend_dcacheRefillCycles = Input(UInt(64.W))
    val backend_dcacheRefillRespWait = Input(UInt(64.W))
    val backend_dcacheVictimWriteback = Input(UInt(64.W))
    val backend_dcacheVictimWritebackCycles = Input(UInt(64.W))
    val backend_cmoStart = Input(UInt(64.W))
    val backend_cmoCycles = Input(UInt(64.W))
    val backend_storeQueueEnq = Input(UInt(64.W))
    val backend_storeQueueDrain = Input(UInt(64.W))
    val backend_storeQueueFullCycles = Input(UInt(64.W))
    val backend_storeQueueOneEntryCycles = Input(UInt(64.W))
    val backend_uncachedAccess = Input(UInt(64.W))
    val backend_uncachedCycles = Input(UInt(64.W))
    val backend_dBusAWait = Input(UInt(64.W))
    val backend_dBusDWait = Input(UInt(64.W))
    val backend_dBusDWaitRefill = Input(UInt(64.W))
    val backend_dBusDWaitUncached = Input(UInt(64.W))
    val backend_mulStart = Input(UInt(64.W))
    val backend_divStart = Input(UInt(64.W))
    val backend_divBusyCycles = Input(UInt(64.W))
  })

  setInline("SimPerfPrinter.v",
    """module SimPerfPrinter(
      |  input         clock,
      |  input         reset,
      |  input         enable,
      |  input  [63:0] exit_pc,
      |  input  [63:0] exit_code,
      |
      |  input  [63:0] frontend_cycles,
      |  input  [63:0] frontend_instFire,
      |  input  [63:0] frontend_backpressure,
      |  input  [63:0] frontend_frontStall,
      |  input  [63:0] frontend_fetchQueueFull,
      |  input  [63:0] frontend_icacheHit,
      |  input  [63:0] frontend_icacheRefill,
      |  input  [63:0] frontend_icacheRefillDone,
      |  input  [63:0] frontend_icacheRefillCycles,
      |  input  [63:0] frontend_icacheRefillReqStall,
      |  input  [63:0] frontend_icacheRefillRespWait,
      |  input  [63:0] frontend_icacheReplay,
      |  input  [63:0] frontend_respQueueFlush,
      |  input  [63:0] frontend_icacheMissSetHasValid,
      |  input  [63:0] frontend_icacheMissSetFull,
      |  input  [63:0] frontend_jalPred,
      |  input  [63:0] frontend_branchPred,
      |  input  [63:0] frontend_ifencei,
      |  input  [63:0] frontend_backendRedirect,
      |
      |  input  [63:0] backend_cycles,
      |  input  [63:0] backend_commits,
      |  input  [63:0] backend_dStall,
      |  input  [63:0] backend_dRs1VarHaz,
      |  input  [63:0] backend_dRs2VarHaz,
      |  input  [63:0] backend_dRdVarHaz,
      |  input  [63:0] backend_dERawHaz,
      |  input  [63:0] backend_dWRawHaz,
      |  input  [63:0] backend_dLsuBaseNotReady,
      |  input  [63:0] backend_dJrNotReady,
      |  input  [63:0] backend_dNoVslot,
      |  input  [63:0] backend_dDivResourceBusy,
      |  input  [63:0] backend_dCacheBusy,
      |  input  [63:0] backend_dUncachedBusy,
      |  input  [63:0] backend_dStoreQueueWait,
      |  input  [63:0] backend_dInflightStoreWait,
      |  input  [63:0] backend_dFenceWait,
      |  input  [63:0] backend_dMisalignedWait,
      |  input  [63:0] backend_dNemuTrapWait,
      |  input  [63:0] backend_loadCommit,
      |  input  [63:0] backend_storeCommit,
      |  input  [63:0] backend_jumpCommit,
      |  input  [63:0] backend_branchCommit,
      |  input  [63:0] backend_branchRedirect,
      |  input  [63:0] backend_dcacheLoadHit,
      |  input  [63:0] backend_dcacheStoreHit,
      |  input  [63:0] backend_dcacheMiss,
      |  input  [63:0] backend_dcacheMissLoad,
      |  input  [63:0] backend_dcacheMissStore,
      |  input  [63:0] backend_dcacheRefillCycles,
      |  input  [63:0] backend_dcacheRefillRespWait,
      |  input  [63:0] backend_dcacheVictimWriteback,
      |  input  [63:0] backend_dcacheVictimWritebackCycles,
      |  input  [63:0] backend_cmoStart,
      |  input  [63:0] backend_cmoCycles,
      |  input  [63:0] backend_storeQueueEnq,
      |  input  [63:0] backend_storeQueueDrain,
      |  input  [63:0] backend_storeQueueFullCycles,
      |  input  [63:0] backend_storeQueueOneEntryCycles,
      |  input  [63:0] backend_uncachedAccess,
      |  input  [63:0] backend_uncachedCycles,
      |  input  [63:0] backend_dBusAWait,
      |  input  [63:0] backend_dBusDWait,
      |  input  [63:0] backend_dBusDWaitRefill,
      |  input  [63:0] backend_dBusDWaitUncached,
      |  input  [63:0] backend_mulStart,
      |  input  [63:0] backend_divStart,
      |  input  [63:0] backend_divBusyCycles
      |);
      |
      |reg printed;
      |
      |always @(posedge clock) begin
      |  if (reset) begin
      |    printed <= 1'b0;
      |  end else if (enable && !printed) begin
      |    printed <= 1'b1;
      |    $display("EXIT at pc = 0x%0x", exit_pc);
      |    if (exit_code == 64'd0) begin
      |      $display("HIT GOOD TRAP");
      |    end else begin
      |      $display("HIT BAD TRAP code = 0x%0x", exit_code);
      |    end
      |    $display("[perf] frontend cycles=%0d instFire=%0d backpressure=%0d icacheRefill=%0d icacheRefillCycles=%0d backendRedirect=%0d",
      |      frontend_cycles, frontend_instFire, frontend_backpressure, frontend_icacheRefill,
      |      frontend_icacheRefillCycles, frontend_backendRedirect);
      |    $display("[perf] frontend-detail frontStall=%0d fetchQueueFull=%0d refillReqStall=%0d refillRespWait=%0d respQueueFlush=%0d",
      |      frontend_frontStall, frontend_fetchQueueFull, frontend_icacheRefillReqStall,
      |      frontend_icacheRefillRespWait, frontend_respQueueFlush);
      |    $display("[perf] icache hit=%0d refillDone=%0d replay=%0d missSetHasValid=%0d missSetFull=%0d jalPred=%0d branchPred=%0d ifencei=%0d",
      |      frontend_icacheHit, frontend_icacheRefillDone, frontend_icacheReplay,
      |      frontend_icacheMissSetHasValid, frontend_icacheMissSetFull, frontend_jalPred,
      |      frontend_branchPred, frontend_ifencei);
      |    $display("[perf] backend cycles=%0d commits=%0d dStall=%0d branchRedirect=%0d dcacheMiss=%0d uncachedAccess=%0d mulStart=%0d divStart=%0d",
      |      backend_cycles, backend_commits, backend_dStall, backend_branchRedirect, backend_dcacheMiss,
      |      backend_uncachedAccess, backend_mulStart, backend_divStart);
      |    $display("[perf] commit load=%0d store=%0d branch=%0d jump=%0d dcacheLoadHit=%0d dcacheStoreHit=%0d missLoad=%0d missStore=%0d",
      |      backend_loadCommit, backend_storeCommit, backend_branchCommit, backend_jumpCommit,
      |      backend_dcacheLoadHit, backend_dcacheStoreHit, backend_dcacheMissLoad, backend_dcacheMissStore);
      |    $display("[perf] stall rs1Var=%0d rs2Var=%0d rdVar=%0d lsuBase=%0d jr=%0d noVslot=%0d divBusy=%0d cacheBusy=%0d uncachedBusy=%0d",
      |      backend_dRs1VarHaz, backend_dRs2VarHaz, backend_dRdVarHaz, backend_dLsuBaseNotReady,
      |      backend_dJrNotReady, backend_dNoVslot, backend_dDivResourceBusy, backend_dCacheBusy,
      |      backend_dUncachedBusy);
      |    $display("[perf] stall-detail eRaw=%0d wRaw=%0d sqWait=%0d inflightStore=%0d fence=%0d misaligned=%0d trapWait=%0d",
      |      backend_dERawHaz, backend_dWRawHaz, backend_dStoreQueueWait, backend_dInflightStoreWait,
      |      backend_dFenceWait, backend_dMisalignedWait, backend_dNemuTrapWait);
      |    $display("[perf] dcache refillCycles=%0d refillRespWait=%0d victimWriteback=%0d victimWritebackCycles=%0d cmoStart=%0d cmoCycles=%0d",
      |      backend_dcacheRefillCycles, backend_dcacheRefillRespWait, backend_dcacheVictimWriteback,
      |      backend_dcacheVictimWritebackCycles, backend_cmoStart, backend_cmoCycles);
      |    $display("[perf] storeq enq=%0d drain=%0d oneEntryCycles=%0d fullCycles=%0d uncachedCycles=%0d dBusAWait=%0d dBusDWait=%0d divBusyCycles=%0d",
      |      backend_storeQueueEnq, backend_storeQueueDrain, backend_storeQueueOneEntryCycles,
      |      backend_storeQueueFullCycles, backend_uncachedCycles, backend_dBusAWait,
      |      backend_dBusDWait, backend_divBusyCycles);
      |    $display("[perf] bus-detail dWaitRefill=%0d dWaitUncached=%0d",
      |      backend_dBusDWaitRefill, backend_dBusDWaitUncached);
      |    $fflush();
      |  end
      |end
      |
      |endmodule
      |""".stripMargin)
}

class AxiDiffMem(axiP: AxiParams)(implicit p: CoreParams) extends Module {
  require(axiP.dataBits > 0 && (axiP.dataBits % 64 == 0 || axiP.dataBits == 32))

  val io = IO(new Bundle {
    val axi = Flipped(new AxiBundle(axiP))
  })

  require(axiP.dataBits <= 256, "rts difftest memory supports accesses within one 256-bit beat")
  private val beatBytes = axiP.dataBits / 8
  private val memBase = p.cachedRegions.head.base.U(p.addrBits.W)
  private val memTop = (p.cachedRegions.head.base + p.cachedRegions.head.size).U(p.addrBits.W)
  private val memBytes = p.cachedRegions.head.size
  private val mem = DifftestMem(memBytes, 32, 8, singlePort = false)

  def memIndex(addr: UInt): UInt = (addr - memBase) >> 5
  def inMem(addr: UInt): Bool = addr >= memBase && addr < memTop
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
  val writeInMem = inMem(writeAlignedAddr)
  val writeBytes = writeData.data.asTypeOf(Vec(axiP.dataBits / 8, UInt(8.W)))
  val writeByteOffset = writeAlignedAddr(4, 0)
  val writeDataBeat = Wire(Vec(32, UInt(8.W)))
  val writeMaskBeat = Wire(Vec(32, Bool()))

  for (i <- 0 until 32) {
    val hits = (0 until beatBytes).map(j => writeByteOffset + j.U === i.U)
    writeDataBeat(i) := MuxCase(0.U(8.W), hits.zip((0 until beatBytes).map(j => writeBytes(j))))
    writeMaskBeat(i) := MuxCase(false.B, hits.zip((0 until beatBytes).map(j => writeData.strb(j))))
  }
  mem.write.valid := writeFire && writeInMem
  mem.write.index := memIndex(writeAlignedAddr)
  mem.write.data := writeDataBeat.asTypeOf(mem.write.data)
  mem.write.mask := VecInit(writeMaskBeat.map(Fill(8, _))).asTypeOf(mem.write.mask)

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
  val readReqAddr = Mux(rState === rResp && io.axi.r.fire && rLenLeft =/= 0.U, nextReadAddr, rAddr)
  val readReqAlignedAddr = Cat(readReqAddr(p.addrBits - 1, log2Ceil(beatBytes)), 0.U(log2Ceil(beatBytes).W))
  val readReqInMem = inMem(readReqAlignedAddr)
  val respAlignedAddr = Cat(rAddr(p.addrBits - 1, log2Ceil(beatBytes)), 0.U(log2Ceil(beatBytes).W))
  val respInMem = inMem(respAlignedAddr)
  val readByteOffset = respAlignedAddr(4, 0)
  val readBytes = mem.readAndHold(memIndex(readReqAlignedAddr), (rState === rWait || rState === rResp) && readReqInMem)
  val readDataBytes = Wire(Vec(beatBytes, UInt(8.W)))
  for (i <- 0 until beatBytes) {
    readDataBytes(i) := Mux(respInMem, readBytes(readByteOffset + i.U), 0.U)
  }
  val readDataWide = Cat((beatBytes - 1 to 0 by -1).map(readDataBytes(_)))

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

class WiredCoreHarnessTop(implicit p: CoreParams) extends Module {

  val io = IO(new Bundle {
    val inst = Valid(UInt(32.W))
    val pc = UInt(p.addrBits.W)
    val clusterInterrupt = Input(Bool())
  })
  val uart = IO(new UARTIO)
  val simExitOut = IO(Output(Bool()))

  val d0 = DifftestModule(new DiffArchEvent, delay = 1, dontCare = true)
  val d1 = DifftestModule(new DiffArchIntRegState, delay = 1, dontCare = true)
  val d2 = DifftestModule(new DiffCSRState, delay = 1, dontCare = true)
  val d3 = DifftestModule(new DiffInstrCommit, delay = 1, dontCare = true)
  val d4 = DifftestModule(new DiffTrapEvent, delay = 1, dontCare = true)
  val d5 = DifftestModule(new DiffArchIntDelayedUpdate, delay = 1, dontCare = true)
  val d6 = DifftestModule(new DiffArchFpRegState, delay = 1, dontCare = true)

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
  core.io.clusterInterrupt := io.clusterInterrupt
  core.io.debug := false.B
  core.io.time.valid := true.B
  core.io.time.bits := cycleCnt
  core.io.dft.lgc_rst_n := true.B
  core.io.dft.mode := false.B
  core.io.dft.scan_mode := false.B
  core.io.dft.cgen := false.B
  core.io.dft.ram.hold := false.B
  core.io.dft.ram.bypass := false.B
  core.io.dft.ram.bp_clken := false.B
  core.io.dft.ram.aux_clk := false.B
  core.io.dft.ram.aux_ckbp := false.B
  core.io.dft.ram.mcp_hold := false.B
  core.io.ramctl.cfg := 0.U
  val tensorRespStub = Module(new Queue(new TensorResp, entries = 1))
  val tensor32IssueInst = "h000000002462838b".U(64.W)
  val tensor64IssueInst = "h0000006f266283bf".U(64.W)
  val tensorMatTcsrWriteInst = "h000000000000038b".U(64.W)
  val tensorVecTcsrWriteInst = "h000000000200038b".U(64.W)
  val tensorLsuTcsrWriteInst = "h000000000400038b".U(64.W)
  val tensorMatTcsrReadInst = "h000000000000438b".U(64.W)
  val tensorVecTcsrReadInst = "h000000000200438b".U(64.W)
  val tensorLsuTcsrReadInst = "h000000000400438b".U(64.W)
  val tensorMatSbProduceInst = "h000000000800038b".U(64.W)
  val tensorVecSbProduceInst = "h000000000a00038b".U(64.W)
  val tensorLsuSbProduceInst = "h000000000c00038b".U(64.W)
  val tensorMatSbBarInst = "h000000000800538b".U(64.W)
  val tensorVecSbBarInst = "h000000000a00538b".U(64.W)
  val tensorLsuSbBarInst = "h000000000c00538b".U(64.W)
  val tensorSbGetAnyInst = "h000000000e00438b".U(64.W)
  val tensor64TcsrWriteInst = "h800000000002803f".U(64.W)
  val tensor64TcsrXchgInst = "h80000000000283bf".U(64.W)
  val tensor64TcsrMaskInst = "h81000000300283bf".U(64.W)
  val tensor64SbProduceInst = "hff0000003800003f".U(64.W)
  val tensor64SbBarInst = "hff100000280002bf".U(64.W)
  val tensor32Rs1Data = "h0123456789abcdef".U(64.W)
  val tensor32Rs2Data = "hfedcba9876543210".U(64.W)
  val tensor32RdData = "h0badc0ffee0ddf00".U(64.W)
  val tensor64Rs1Data = "h13579bdf2468ace0".U(64.W)
  val tensor64Rs2Data = "hf0e1d2c3b4a59687".U(64.W)
  val tensor64RdData = "hdeadbeefcafebabe".U(64.W)
  val tensor64ControlRs1Data = "h13579bdf2468ace0".U(64.W)
  val tensor64ControlRs3Data = "h0f1e2d3c4b5a6978".U(64.W)
  val tensor64SbProduceData = "h0feedface1234567".U(64.W)
  val tensorControlRdData = "h0feedface1234567".U(64.W)
  val tensorMatTcsrReadData = "h1111222233334444".U(64.W)
  val tensorVecTcsrReadData = "h5555666677778888".U(64.W)
  val tensorLsuTcsrReadData = "h9999aaaabbbbcccc".U(64.W)
  val tensorMatSbBarData = "h1020304050607080".U(64.W)
  val tensorVecSbBarData = "h1122334455667788".U(64.W)
  val tensorLsuSbBarData = "h8877665544332211".U(64.W)
  val tensorSbGetAnyData = "h0f0e0d0c0b0a0908".U(64.W)
  val tensor64TcsrXchgData = "h2468ace013579bdf".U(64.W)
  val tensor64TcsrMaskData = "h0a0b0c0d0e0f1011".U(64.W)
  val tensor32Issue = core.io.tensorInst.bits.instWord === tensor32IssueInst
  val tensor64Issue = core.io.tensorInst.bits.instWord === tensor64IssueInst
  val tensorMatTcsrWriteIssue = core.io.tensorInst.bits.instWord === tensorMatTcsrWriteInst
  val tensorVecTcsrWriteIssue = core.io.tensorInst.bits.instWord === tensorVecTcsrWriteInst
  val tensorLsuTcsrWriteIssue = core.io.tensorInst.bits.instWord === tensorLsuTcsrWriteInst
  val tensorMatTcsrReadIssue = core.io.tensorInst.bits.instWord === tensorMatTcsrReadInst
  val tensorVecTcsrReadIssue = core.io.tensorInst.bits.instWord === tensorVecTcsrReadInst
  val tensorLsuTcsrReadIssue = core.io.tensorInst.bits.instWord === tensorLsuTcsrReadInst
  val tensorMatSbProduceIssue = core.io.tensorInst.bits.instWord === tensorMatSbProduceInst
  val tensorVecSbProduceIssue = core.io.tensorInst.bits.instWord === tensorVecSbProduceInst
  val tensorLsuSbProduceIssue = core.io.tensorInst.bits.instWord === tensorLsuSbProduceInst
  val tensorMatSbBarIssue = core.io.tensorInst.bits.instWord === tensorMatSbBarInst
  val tensorVecSbBarIssue = core.io.tensorInst.bits.instWord === tensorVecSbBarInst
  val tensorLsuSbBarIssue = core.io.tensorInst.bits.instWord === tensorLsuSbBarInst
  val tensorSbGetAnyIssue = core.io.tensorInst.bits.instWord === tensorSbGetAnyInst
  val tensor64TcsrWriteIssue = core.io.tensorInst.bits.instWord === tensor64TcsrWriteInst
  val tensor64TcsrXchgIssue = core.io.tensorInst.bits.instWord === tensor64TcsrXchgInst
  val tensor64TcsrMaskIssue = core.io.tensorInst.bits.instWord === tensor64TcsrMaskInst
  val tensor64SbProduceIssue = core.io.tensorInst.bits.instWord === tensor64SbProduceInst
  val tensor64SbBarIssue = core.io.tensorInst.bits.instWord === tensor64SbBarInst
  val tensorSbBarIssue = tensorMatSbBarIssue || tensorVecSbBarIssue || tensorLsuSbBarIssue
  val tensorTcsrReadIssue =
    tensorMatTcsrReadIssue || tensorVecTcsrReadIssue || tensorLsuTcsrReadIssue ||
      tensor64TcsrXchgIssue || tensor64TcsrMaskIssue
  val tensorSbReadIssue =
    tensorMatSbBarIssue || tensorVecSbBarIssue || tensorLsuSbBarIssue || tensorSbGetAnyIssue
  val tensorControlWriteIssue =
    tensorMatTcsrWriteIssue || tensorVecTcsrWriteIssue || tensorLsuTcsrWriteIssue ||
      tensorMatSbProduceIssue || tensorVecSbProduceIssue || tensorLsuSbProduceIssue
  val tensorRespData = MuxCase(0.U(64.W), Seq(
    tensorMatTcsrReadIssue -> tensorMatTcsrReadData,
    tensorVecTcsrReadIssue -> tensorVecTcsrReadData,
    tensorLsuTcsrReadIssue -> tensorLsuTcsrReadData,
    tensorSbBarIssue -> core.io.tensorInst.bits.rdData,
    tensorSbGetAnyIssue -> tensorSbGetAnyData,
    tensor64TcsrXchgIssue -> tensor64TcsrXchgData,
    tensor64TcsrMaskIssue -> tensor64TcsrMaskData
  ))
  val tensorRespDelayValid = RegInit(false.B)
  val tensorRespDelayCount = RegInit(0.U(2.W))
  val tensorRespDelayBits = RegInit(0.U.asTypeOf(new TensorResp))
  val tensorRespImmediate = core.io.tensorInst.fire && core.io.tensorInst.bits.expectsResp && !tensorSbBarIssue
  val tensorRespDelayStart = core.io.tensorInst.fire && core.io.tensorInst.bits.expectsResp && tensorSbBarIssue
  val tensorRespDelayReady = tensorRespDelayValid && tensorRespDelayCount === 0.U
  val tensorRespDelayEnq = tensorRespDelayReady && tensorRespStub.io.enq.ready
  core.io.tensorInst.ready := !core.io.tensorInst.bits.expectsResp ||
    (!tensorRespDelayValid && tensorRespStub.io.enq.ready)
  tensorRespStub.io.enq.valid := tensorRespImmediate || tensorRespDelayReady
  val tensorRespNowBits = Wire(new TensorResp)
  tensorRespNowBits.data := tensorRespData
  tensorRespNowBits.rd := core.io.tensorInst.bits.instWord(11, 7)
  tensorRespStub.io.enq.bits := Mux(tensorRespDelayValid, tensorRespDelayBits, tensorRespNowBits)
  when(tensorRespDelayStart) {
    tensorRespDelayValid := true.B
    tensorRespDelayCount := 2.U
    tensorRespDelayBits.data := tensorRespData
    tensorRespDelayBits.rd := core.io.tensorInst.bits.instWord(11, 7)
  }.elsewhen(tensorRespDelayValid && tensorRespDelayCount =/= 0.U) {
    tensorRespDelayCount := tensorRespDelayCount - 1.U
  }.elsewhen(tensorRespDelayEnq) {
    tensorRespDelayValid := false.B
  }
  when(core.io.tensorInst.fire && tensor32Issue) {
    assert(!core.io.tensorInst.bits.expectsResp,
      "Tensor 32-bit async issue unexpectedly requested a response")
    assert(core.io.tensorInst.bits.rs1Data === tensor32Rs1Data,
      "Tensor 32-bit issue rs1Data mismatch")
    assert(core.io.tensorInst.bits.rs2Data === tensor32Rs2Data,
      "Tensor 32-bit issue rs2Data mismatch")
    assert(core.io.tensorInst.bits.rdData === tensor32RdData,
      "Tensor 32-bit issue rdData mismatch")
  }
  when(core.io.tensorInst.fire && tensor64Issue) {
    assert(!core.io.tensorInst.bits.expectsResp,
      "Tensor 64-bit async issue unexpectedly requested a response")
    assert(core.io.tensorInst.bits.rs1Data === tensor64Rs1Data,
      "Tensor 64-bit issue rs1Data mismatch")
    assert(core.io.tensorInst.bits.rs2Data === tensor64Rs2Data,
      "Tensor 64-bit issue rs2Data mismatch")
    assert(core.io.tensorInst.bits.rdData === tensor64RdData,
      "Tensor 64-bit issue rdData mismatch")
    assert(core.io.tensorInst.bits.instWord(63, 32) === "h0000006f".U,
      "Tensor 64-bit issue high word mismatch")
  }
  when(core.io.tensorInst.fire && tensorTcsrReadIssue) {
    assert(core.io.tensorInst.bits.expectsResp,
      "Tensor TCSR read did not request a response")
  }
  when(core.io.tensorInst.fire && tensorSbReadIssue) {
    assert(core.io.tensorInst.bits.expectsResp,
      "Tensor scoreboard read did not request a response")
  }
  when(core.io.tensorInst.fire && tensorControlWriteIssue) {
    assert(!core.io.tensorInst.bits.expectsResp,
      "Tensor control write unexpectedly requested a response")
    assert(core.io.tensorInst.bits.rdData === tensorControlRdData,
      "Tensor control write rdData mismatch")
  }
  when(core.io.tensorInst.fire && tensor64TcsrWriteIssue) {
    assert(!core.io.tensorInst.bits.expectsResp,
      "Tensor 64-bit TCSR write unexpectedly requested a response")
    assert(core.io.tensorInst.bits.rs1Data === tensor64ControlRs1Data,
      "Tensor 64-bit TCSR write rs1Data mismatch")
  }
  when(core.io.tensorInst.fire && tensor64TcsrXchgIssue) {
    assert(core.io.tensorInst.bits.expectsResp,
      "Tensor 64-bit TCSR xchg did not request a response")
    assert(core.io.tensorInst.bits.rs1Data === tensor64ControlRs1Data,
      "Tensor 64-bit TCSR xchg rs1Data mismatch")
  }
  when(core.io.tensorInst.fire && tensor64TcsrMaskIssue) {
    assert(core.io.tensorInst.bits.expectsResp,
      "Tensor 64-bit TCSR masked xchg did not request a response")
    assert(core.io.tensorInst.bits.rs1Data === tensor64ControlRs1Data,
      "Tensor 64-bit TCSR masked xchg rs1Data mismatch")
    assert(core.io.tensorInst.bits.rs2Data === tensor64ControlRs3Data,
      "Tensor 64-bit TCSR masked xchg rs3Data mismatch")
  }
  when(core.io.tensorInst.fire && tensor64SbProduceIssue) {
    assert(!core.io.tensorInst.bits.expectsResp,
      "Tensor 64-bit scoreboard produce unexpectedly requested a response")
    assert(core.io.tensorInst.bits.rs2Data === tensor64SbProduceData,
      "Tensor 64-bit scoreboard produce rs3Data mismatch")
  }
  when(core.io.tensorInst.fire && tensor64SbBarIssue) {
    assert(!core.io.tensorInst.bits.expectsResp,
      "Tensor 64-bit scoreboard barrier unexpectedly requested a response")
  }
  core.io.tensorResp <> tensorRespStub.io.deq
  mem.io.axi <> core.io.mem

  val commitValid = core.io.diffCommitValid
  when(tensorRespDelayValid && !tensorRespDelayEnq) {
    assert(!commitValid, "Core committed after blocking Tensor scoreboard barrier before completion")
  }
  val simExit = core.io.diffExit
  val simExitDelayed = RegInit(false.B)
  when(simExit) {
    simExitDelayed := true.B
  }
  simExitOut := simExitDelayed
  val instrCnt = RegInit(0.U(64.W))
  val instrCntNext = instrCnt + commitValid.asUInt
  val lastCommitPc = RegInit(0.U(p.addrBits.W))
  val trapReportPc = Mux(commitValid, core.io.diffCommitPc, lastCommitPc)

  cycleCnt := cycleCnt + 1.U
  when(commitValid) {
    instrCnt := instrCntNext
    lastCommitPc := core.io.diffCommitPc
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
  d3.otherwpdest := VecInit(Seq.fill(8)(0.U(8.W)))
  d3.otherV0Wen := VecInit(Seq.fill(8)(false.B))
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
  d4.pc := trapReportPc

  val perfPrinter = Module(new SimPerfPrinter)
  perfPrinter.io.clock := clock
  perfPrinter.io.reset := reset.asBool
  perfPrinter.io.enable := simExit
  perfPrinter.io.exit_pc := Cat(0.U((64 - p.addrBits).W), trapReportPc)
  perfPrinter.io.exit_code := core.io.diffRf(10)
  perfPrinter.io.frontend_cycles := core.io.frontendPerf.cycles
  perfPrinter.io.frontend_instFire := core.io.frontendPerf.instFire
  perfPrinter.io.frontend_backpressure := core.io.frontendPerf.backpressure
  perfPrinter.io.frontend_frontStall := core.io.frontendPerf.frontStall
  perfPrinter.io.frontend_fetchQueueFull := core.io.frontendPerf.fetchQueueFull
  perfPrinter.io.frontend_icacheHit := core.io.frontendPerf.icacheHit
  perfPrinter.io.frontend_icacheRefill := core.io.frontendPerf.icacheRefill
  perfPrinter.io.frontend_icacheRefillDone := core.io.frontendPerf.icacheRefillDone
  perfPrinter.io.frontend_icacheRefillCycles := core.io.frontendPerf.icacheRefillCycles
  perfPrinter.io.frontend_icacheRefillReqStall := core.io.frontendPerf.icacheRefillReqStall
  perfPrinter.io.frontend_icacheRefillRespWait := core.io.frontendPerf.icacheRefillRespWait
  perfPrinter.io.frontend_icacheReplay := core.io.frontendPerf.icacheReplay
  perfPrinter.io.frontend_respQueueFlush := core.io.frontendPerf.respQueueFlush
  perfPrinter.io.frontend_icacheMissSetHasValid := core.io.frontendPerf.icacheMissSetHasValid
  perfPrinter.io.frontend_icacheMissSetFull := core.io.frontendPerf.icacheMissSetFull
  perfPrinter.io.frontend_jalPred := core.io.frontendPerf.jalPred
  perfPrinter.io.frontend_branchPred := core.io.frontendPerf.branchPred
  perfPrinter.io.frontend_ifencei := core.io.frontendPerf.ifencei
  perfPrinter.io.frontend_backendRedirect := core.io.frontendPerf.backendRedirect
  perfPrinter.io.backend_cycles := core.io.backendPerf.cycles
  perfPrinter.io.backend_commits := core.io.backendPerf.commits
  perfPrinter.io.backend_dStall := core.io.backendPerf.dStall
  perfPrinter.io.backend_dRs1VarHaz := core.io.backendPerf.dRs1VarHaz
  perfPrinter.io.backend_dRs2VarHaz := core.io.backendPerf.dRs2VarHaz
  perfPrinter.io.backend_dRdVarHaz := core.io.backendPerf.dRdVarHaz
  perfPrinter.io.backend_dERawHaz := core.io.backendPerf.dERawHaz
  perfPrinter.io.backend_dWRawHaz := core.io.backendPerf.dWRawHaz
  perfPrinter.io.backend_dLsuBaseNotReady := core.io.backendPerf.dLsuBaseNotReady
  perfPrinter.io.backend_dJrNotReady := core.io.backendPerf.dJrNotReady
  perfPrinter.io.backend_dNoVslot := core.io.backendPerf.dNoVslot
  perfPrinter.io.backend_dDivResourceBusy := core.io.backendPerf.dDivResourceBusy
  perfPrinter.io.backend_dCacheBusy := core.io.backendPerf.dCacheBusy
  perfPrinter.io.backend_dUncachedBusy := core.io.backendPerf.dUncachedBusy
  perfPrinter.io.backend_dStoreQueueWait := core.io.backendPerf.dStoreQueueWait
  perfPrinter.io.backend_dInflightStoreWait := core.io.backendPerf.dInflightStoreWait
  perfPrinter.io.backend_dFenceWait := core.io.backendPerf.dFenceWait
  perfPrinter.io.backend_dMisalignedWait := core.io.backendPerf.dMisalignedWait
  perfPrinter.io.backend_dNemuTrapWait := core.io.backendPerf.dNemuTrapWait
  perfPrinter.io.backend_loadCommit := core.io.backendPerf.loadCommit
  perfPrinter.io.backend_storeCommit := core.io.backendPerf.storeCommit
  perfPrinter.io.backend_jumpCommit := core.io.backendPerf.jumpCommit
  perfPrinter.io.backend_branchCommit := core.io.backendPerf.branchCommit
  perfPrinter.io.backend_branchRedirect := core.io.backendPerf.branchRedirect
  perfPrinter.io.backend_dcacheLoadHit := core.io.backendPerf.dcacheLoadHit
  perfPrinter.io.backend_dcacheStoreHit := core.io.backendPerf.dcacheStoreHit
  perfPrinter.io.backend_dcacheMiss := core.io.backendPerf.dcacheMiss
  perfPrinter.io.backend_dcacheMissLoad := core.io.backendPerf.dcacheMissLoad
  perfPrinter.io.backend_dcacheMissStore := core.io.backendPerf.dcacheMissStore
  perfPrinter.io.backend_dcacheRefillCycles := core.io.backendPerf.dcacheRefillCycles
  perfPrinter.io.backend_dcacheRefillRespWait := core.io.backendPerf.dcacheRefillRespWait
  perfPrinter.io.backend_dcacheVictimWriteback := core.io.backendPerf.dcacheVictimWriteback
  perfPrinter.io.backend_dcacheVictimWritebackCycles := core.io.backendPerf.dcacheVictimWritebackCycles
  perfPrinter.io.backend_cmoStart := core.io.backendPerf.cmoStart
  perfPrinter.io.backend_cmoCycles := core.io.backendPerf.cmoCycles
  perfPrinter.io.backend_storeQueueEnq := core.io.backendPerf.storeQueueEnq
  perfPrinter.io.backend_storeQueueDrain := core.io.backendPerf.storeQueueDrain
  perfPrinter.io.backend_storeQueueFullCycles := core.io.backendPerf.storeQueueFullCycles
  perfPrinter.io.backend_storeQueueOneEntryCycles := core.io.backendPerf.storeQueueOneEntryCycles
  perfPrinter.io.backend_uncachedAccess := core.io.backendPerf.uncachedAccess
  perfPrinter.io.backend_uncachedCycles := core.io.backendPerf.uncachedCycles
  perfPrinter.io.backend_dBusAWait := core.io.backendPerf.dBusAWait
  perfPrinter.io.backend_dBusDWait := core.io.backendPerf.dBusDWait
  perfPrinter.io.backend_dBusDWaitRefill := core.io.backendPerf.dBusDWaitRefill
  perfPrinter.io.backend_dBusDWaitUncached := core.io.backendPerf.dBusDWaitUncached
  perfPrinter.io.backend_mulStart := core.io.backendPerf.mulStart
  perfPrinter.io.backend_divStart := core.io.backendPerf.divStart
  perfPrinter.io.backend_divBusyCycles := core.io.backendPerf.divBusyCycles

  d5.coreid := 0.U
  d5.index := 0.U
  d5.valid := core.io.diffDelayedWbValid
  d5.address := core.io.diffDelayedWbRd
  d5.data := core.io.diffDelayedWbData
  d5.nack := false.B

  d6.coreid := 0.U
  d6.value := VecInit(Seq.fill(32)(0.U(64.W)))

  io.pc := core.io.inst.bits.pc
  io.inst.valid := core.io.inst.fire
  io.inst.bits := core.io.inst.bits.inst

  uart.out.valid := core.io.simUart.valid
  uart.out.ch := core.io.simUart.bits
  uart.in.valid := false.B

}

class SimTop(implicit p: CoreParams) extends Module {
  val dut = Module(new WiredCoreHarnessTop)
  val difftest = DifftestModule.finish("WiredCoreHarnessTop")

  dut.io.clusterInterrupt := false.B
  difftest.exit := Mux(dut.simExitOut, Fill(64, 1.U), 0.U)
  difftest.uart <> dut.uart
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
    new SimTop(),
    firtoolOpts = Array(
      "--mlir-disable-threading",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=build/rtl"
    )
  )
}
