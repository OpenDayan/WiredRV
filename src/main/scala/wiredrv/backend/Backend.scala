package wiredrv.backend

import chisel3._
import chisel3.util._
import difftest._
import wiredrv.common._

class BackendIO(implicit p: CoreParams) extends Bundle {
  val in = Flipped(Decoupled(new FetchPacket))
  val redirects = Output(Vec(5, Valid(new RedirectReq)))
  val icacheInvalidate = Output(Bool())
  val dmem = new TLBundleUH
  val commit = Output(new CommitInfo)
  val gpr = Output(Vec(32, UInt(p.xlen.W)))
  val trap = Output(new TrapInfo)
  val csrState = Output(new CsrStateBundle)
}

class Backend(implicit p: CoreParams) extends Module {
  val io = IO(new BackendIO)
  val nemuTrapInst = "h0000006b".U(32.W)

  val decode = Module(new Decode)
  val regfile = Module(new RegFile)
  val scoreboard = Module(new Scoreboard)
  val execute = Module(new ExecuteUnit)
  val dcacheTop = Module(new DCacheTop)
  val uncached = Module(new Uncached)
  val csr = Module(new CSRFile)
  val wb = Module(new Writeback)
  val mulDiv = Module(new MulDiv)

  val dStage = Wire(new StageD)
  val dValid = io.in.valid
  val eValid = RegInit(false.B)
  val eStage = Reg(new StageE)
  val wValid = RegInit(false.B)
  val wStage = Reg(new StageW)
  val cycleCnt = RegInit(0.U(64.W))
  val instrCnt = RegInit(0.U(64.W))
  val diffArch = DifftestModule(new DiffArchEvent, delay = 1, dontCare = true)
  val diffCommit = DifftestModule(new DiffInstrCommit, delay = 1, dontCare = true)
  val diffTrap = DifftestModule(new DiffTrapEvent, delay = 1, dontCare = true)

  cycleCnt := cycleCnt + 1.U
  decode.io.inst := io.in.bits.inst

  val rs1Addr = decode.io.out.rs1
  val rs2Addr = decode.io.out.rs2
  val dRs1Raw = regfile.io.rdata(0)
  val dRs2Raw = regfile.io.rdata(1)
  val dRdRaw = regfile.io.rdata(2)
  val dRdAddr = decode.io.out.rd
  val dAddrCandidate = dRs1Raw + decode.io.out.imm
  val dMemOp = decode.io.out.info.isLoad || decode.io.out.info.isStore || decode.io.out.info.isCacheMaintain
  val dPipeCacheOp = (decode.io.out.info.isLoad || decode.io.out.info.isStore) &&
    !MemoryMap.hit(dAddrCandidate(p.addrBits - 1, 0), p.uncachedRegions)
  val dIsUncached = dMemOp && MemoryMap.hit(dAddrCandidate(p.addrBits - 1, 0), p.uncachedRegions)

  dStage := 0.U.asTypeOf(new StageD)
  dStage.fetch := io.in.bits
  dStage.decoded := decode.io.out
  dStage.decoded.pc := io.in.bits.pc
  dStage.decoded.isUncached := dIsUncached
  dStage.src1 := dRs1Raw
  dStage.src2 := dRs2Raw
  dStage.rdData := dRdRaw

  regfile.io.raddr := VecInit(Seq(rs1Addr, rs2Addr, Mux(decode.io.out.info.readRd, dRdAddr, 0.U(5.W))))

  scoreboard.io.probeRs1 := 0.U
  scoreboard.io.probeRs2 := 0.U

  val dJumpRedirect = Wire(Valid(new RedirectReq))
  dJumpRedirect.valid := dValid && decode.io.out.info.isJump && decode.io.out.info.fuOpType === BruOp.jal
  dJumpRedirect.bits.target := (io.in.bits.pc + decode.io.out.imm)(p.addrBits - 1, 0)
  dJumpRedirect.bits.cause := RedirectCause.jumpEarlyDecode

  val dcacheLookupReq = WireDefault(0.U.asTypeOf(new LSUReq))
  dcacheLookupReq.addr := dAddrCandidate(p.addrBits - 1, 0)
  dcacheLookupReq.funct3 := decode.io.out.info.fuOpType(2, 0)
  dcacheLookupReq.isLoad := decode.io.out.info.isLoad
  dcacheLookupReq.isStore := decode.io.out.info.isStore
  dcacheLookupReq.isCacheMaintain := false.B
  dcacheLookupReq.cacheMaintainOp := CacheMaintainOp.none
  dcacheLookupReq.miss := false.B
  dcacheLookupReq.waymask := 0.U
  dcacheLookupReq.data := dRs2Raw
  dcacheLookupReq.uncached := false.B
  dcacheTop.io.dReq.valid := dValid && dPipeCacheOp
  dcacheTop.io.dReq.bits := dcacheLookupReq

  execute.io.in := 0.U.asTypeOf(new ExecuteIn)
  val eRs1HazW = wValid && eStage.decoded.rs1.orR && eStage.decoded.rs1 === wStage.decoded.rd
  val eRs2HazW = wValid && eStage.decoded.rs2.orR && eStage.decoded.rs2 === wStage.decoded.rd
  val eRdHazW = wValid && eStage.decoded.info.readRd && eStage.decoded.rd.orR && eStage.decoded.rd === wStage.decoded.rd

  val cachedLoadHitData = Mux(
    wStage.decoded.isUncached || wStage.dcache.miss,
    0.U,
    wStage.dcache.data
  )

  val wCachedReq = wValid && !wStage.decoded.isUncached &&
    (wStage.decoded.info.isStore || wStage.dcache.miss || wStage.decoded.info.isCacheMaintain) &&
    !wStage.dcache.exception
  val wUncachedReq = wValid && wStage.decoded.isUncached &&
    (wStage.decoded.info.isLoad || wStage.decoded.info.isStore || wStage.decoded.info.isCacheMaintain) &&
    !wStage.dcache.exception

  val cachedRespValid = dcacheTop.io.wResp.valid
  val uncachedRespValid = uncached.io.resp.valid
  val memHold = (wCachedReq && !cachedRespValid) || (wUncachedReq && !uncachedRespValid)

  val wbLoadData = Mux(wStage.decoded.isUncached,
    uncached.io.resp.bits.data,
    Mux(wStage.dcache.miss, dcacheTop.io.wResp.bits.data, cachedLoadHitData)
  )
  val wbData = Mux(wStage.decoded.info.isLoad, wbLoadData, wStage.exec.result)
  val wBypassEn = wValid && !memHold && wStage.decoded.info.rfWen && wStage.decoded.rd.orR

  val eSrc1Raw = Mux(eRs1HazW && wBypassEn, wbData, eStage.src1)
  val eSrc2Raw = Mux(eRs2HazW && wBypassEn, wbData, eStage.src2)
  val eRdRaw = Mux(eRdHazW && wBypassEn, wbData, eStage.rdData)

  execute.io.in.pc := eStage.decoded.pc
  execute.io.in.inst := eStage.decoded.inst
  execute.io.in.info := eStage.decoded.info
  execute.io.in.src1 := eSrc1Raw
  execute.io.in.src2 := eSrc2Raw
  execute.io.in.rdData := eRdRaw
  execute.io.in.csrReadData := csr.io.readData
  execute.io.in.imm := eStage.decoded.imm
  execute.io.in.rd := eStage.decoded.rd
  execute.io.in.isUncached := eStage.decoded.isUncached

  csr.io.readAddr := eStage.decoded.csrAddr
  wb.io.inValid := false.B
  wb.io.rd := 0.U
  wb.io.data := 0.U
  mulDiv.io.reqValid := false.B

  val wCacheReqBits = WireDefault(0.U.asTypeOf(new LSUReq))
  wCacheReqBits.addr := wStage.exec.result(p.addrBits - 1, 0)
  wCacheReqBits.funct3 := wStage.decoded.info.fuOpType(2, 0)
  wCacheReqBits.isLoad := wStage.decoded.info.isLoad
  wCacheReqBits.isStore := wStage.decoded.info.isStore
  wCacheReqBits.isCacheMaintain := wStage.decoded.info.isCacheMaintain
  wCacheReqBits.cacheMaintainOp := wStage.decoded.info.cacheMaintainOp
  wCacheReqBits.miss := wStage.dcache.miss
  wCacheReqBits.waymask := wStage.dcache.waymask
  wCacheReqBits.data := wStage.src2
  wCacheReqBits.uncached := false.B
  dcacheTop.io.wReq.valid := wCachedReq
  dcacheTop.io.wReq.bits := wCacheReqBits

  val wUncachedReqBits = WireDefault(wCacheReqBits)
  wUncachedReqBits.uncached := true.B
  uncached.io.req.valid := wUncachedReq
  uncached.io.req.bits := wUncachedReqBits

  val dcachePredictHold = dValid && dPipeCacheOp && !dcacheTop.io.dReady
  val globalHold = memHold || dcachePredictHold

  val eUncachedFlush = eValid && eStage.decoded.isUncached &&
    (eStage.decoded.info.isLoad || eStage.decoded.info.isStore || eStage.decoded.info.isCacheMaintain)
  val eUncachedRedirect = Wire(Valid(new RedirectReq))
  eUncachedRedirect.valid := eUncachedFlush
  eUncachedRedirect.bits.target := (eStage.decoded.pc + 4.U)(p.addrBits - 1, 0)
  eUncachedRedirect.bits.cause := RedirectCause.exuFlush

  val wReplay = wCachedReq && wStage.dcache.miss && cachedRespValid
  val wReplayRedirect = Wire(Valid(new RedirectReq))
  wReplayRedirect.valid := wReplay
  wReplayRedirect.bits.target := wStage.decoded.pc
  wReplayRedirect.bits.cause := RedirectCause.backendReplayOrException

  val killD = (eValid && execute.io.out.redirect.valid) || eUncachedRedirect.valid || wReplayRedirect.valid
  io.in.ready := !globalHold && !killD

  when(killD) {
    eValid := false.B
  }.elsewhen(!globalHold) {
    eValid := dValid
  }

  when(dValid && !globalHold) {
    eStage.decoded := decode.io.out
    eStage.decoded.pc := dStage.fetch.pc
    eStage.decoded.isUncached := dIsUncached
    eStage.src1 := MuxLookup(decode.io.out.info.src1Type, dStage.src1)(Seq(
      SrcType.imm -> decode.io.out.imm,
      SrcType.pc -> dStage.fetch.pc
    ))
    eStage.src2 := MuxLookup(decode.io.out.info.src2Type, dStage.src2)(Seq(
      SrcType.none -> 0.U,
      SrcType.imm -> decode.io.out.imm,
      SrcType.pc -> dStage.fetch.pc
    ))
    eStage.rdData := dStage.rdData
    eStage.dcache := 0.U.asTypeOf(new DCachePipeResp)
    eStage.exec := execute.io.out
  }

  val ePipeResp = WireDefault(0.U.asTypeOf(new DCachePipeResp))
  ePipeResp := dcacheTop.io.eResp.bits

  when(wReplayRedirect.valid) {
    wValid := false.B
  }.elsewhen(!globalHold) {
    wValid := eValid
    when(eValid) {
      wStage.decoded := eStage.decoded
      wStage.src1 := eStage.src1
      wStage.src2 := eStage.src2
      wStage.rdData := eStage.rdData
      wStage.dcache := Mux(eStage.decoded.info.isLoad || eStage.decoded.info.isStore,
        ePipeResp,
        0.U.asTypeOf(new DCachePipeResp)
      )
      wStage.exec := execute.io.out
    }
  }

  val wMemException = wStage.dcache.exception ||
    (wCachedReq && dcacheTop.io.wResp.bits.exception) ||
    (wUncachedReq && uncached.io.resp.bits.exception)
  val wMemExceptionCode = Mux(wStage.dcache.exception, wStage.dcache.exceptionCode,
    Mux(wStage.decoded.isUncached, uncached.io.resp.bits.exceptionCode, dcacheTop.io.wResp.bits.exceptionCode))

  val wRetire = wValid && !memHold && !wReplay && !wMemException

  regfile.io.wen := VecInit(Seq(wRetire && wStage.decoded.info.rfWen && !wStage.decoded.info.illegal, false.B))
  regfile.io.waddr := VecInit(Seq(wStage.decoded.rd, 0.U(5.W)))
  regfile.io.wdata := VecInit(Seq(wbData, 0.U(p.xlen.W)))

  val csrOperand = Mux(wStage.decoded.info.src2Type === SrcType.imm, wStage.decoded.imm, wStage.src2)
  val csrWriteData = WireDefault(wStage.exec.result)
  when(wStage.decoded.info.isCsr) {
    switch(wStage.decoded.info.fuOpType) {
      is(CsrOp.csrrw) { csrWriteData := csrOperand }
      is(CsrOp.csrrs) { csrWriteData := wStage.exec.result | csrOperand }
      is(CsrOp.csrrc) { csrWriteData := wStage.exec.result & (~csrOperand).asUInt }
    }
  }

  val trapInW = wValid && (
    wStage.decoded.info.illegal ||
    (wStage.decoded.info.isCsr && wStage.decoded.exceptionCode.orR) ||
    wStage.exec.exception ||
    wMemException
  ) && !memHold

  csr.io.writeValid := wRetire && wStage.decoded.info.isCsr && wStage.decoded.info.fuOpType =/= CsrOp.ecall
  csr.io.writeAddr := wStage.decoded.csrAddr
  csr.io.writeData := csrWriteData
  csr.io.trapValid := trapInW
  csr.io.trapPc := wStage.decoded.pc
  csr.io.trapCode := Mux(wMemException, wMemExceptionCode,
    Mux(wStage.exec.exception, wStage.exec.exceptionCode, wStage.decoded.exceptionCode))
  csr.io.trapValue := 0.U

  val simExitInW = wRetire && wStage.decoded.inst === nemuTrapInst

  io.redirects := VecInit(Seq.fill(5)(0.U.asTypeOf(Valid(new RedirectReq))))
  io.redirects(RedirectCause.jumpEarlyDecode) := dJumpRedirect
  io.redirects(RedirectCause.exuFlush) := Mux(eUncachedRedirect.valid, eUncachedRedirect, execute.io.out.redirect)
  io.redirects(RedirectCause.backendReplayOrException).valid := trapInW || wReplayRedirect.valid
  io.redirects(RedirectCause.backendReplayOrException).bits.target := Mux(trapInW, csr.io.trapVector, wReplayRedirect.bits.target)
  io.redirects(RedirectCause.backendReplayOrException).bits.cause := RedirectCause.backendReplayOrException
  io.icacheInvalidate := eValid && execute.io.out.invalidateIcache && !globalHold

  io.commit := 0.U.asTypeOf(new CommitInfo)
  io.commit.valid := wRetire && !trapInW
  io.commit.pc := wStage.decoded.pc
  io.commit.inst := wStage.decoded.inst
  io.commit.rfWen := wRetire && wStage.decoded.info.rfWen && wStage.decoded.rd.orR
  io.commit.rd := wStage.decoded.rd
  io.commit.data := wbData
  io.commit.isLoad := wStage.decoded.info.isLoad
  io.commit.isStore := wStage.decoded.info.isStore
  io.commit.skip := false.B

  when(io.commit.valid) {
    instrCnt := instrCnt + 1.U
  }

  io.trap := 0.U.asTypeOf(new TrapInfo)
  io.trap.valid := trapInW
  io.trap.code := Mux(wMemException, wMemExceptionCode,
    Mux(wStage.exec.exception, wStage.exec.exceptionCode, wStage.decoded.exceptionCode))
  io.trap.pc := wStage.decoded.pc

  io.gpr := regfile.io.debug
  io.csrState := csr.io.state

  val dcacheGrant = RegInit(false.B)
  val useUncached = uncached.io.bus.a.valid
  io.dmem.a.valid := uncached.io.bus.a.valid || dcacheTop.io.bus.a.valid
  io.dmem.a.bits := Mux(useUncached, uncached.io.bus.a.bits, dcacheTop.io.bus.a.bits)
  uncached.io.bus.a.ready := io.dmem.a.ready && useUncached
  dcacheTop.io.bus.a.ready := io.dmem.a.ready && !useUncached && dcacheTop.io.bus.a.valid

  when(io.dmem.a.fire) {
    dcacheGrant := !useUncached
  }

  uncached.io.bus.d.valid := io.dmem.d.valid && !dcacheGrant
  uncached.io.bus.d.bits := io.dmem.d.bits
  dcacheTop.io.bus.d.valid := io.dmem.d.valid && dcacheGrant
  dcacheTop.io.bus.d.bits := io.dmem.d.bits
  io.dmem.d.ready := Mux(dcacheGrant, dcacheTop.io.bus.d.ready, uncached.io.bus.d.ready)

  diffArch.valid := io.trap.valid
  diffArch.coreid := 0.U
  diffArch.interrupt := 0.U
  diffArch.exception := Mux(io.trap.valid, io.trap.code(31, 0), 0.U)
  diffArch.exceptionPC := io.trap.pc
  diffArch.exceptionInst := wStage.decoded.inst
  diffArch.hasNMI := false.B
  diffArch.virtualInterruptIsHvictlInject := false.B
  diffArch.irToHS := false.B
  diffArch.irToVS := false.B

  diffCommit.coreid := 0.U
  diffCommit.index := 0.U
  diffCommit.valid := io.commit.valid
  diffCommit.skip := io.commit.skip
  diffCommit.isRVC := false.B
  diffCommit.rfwen := io.commit.rfWen
  diffCommit.fpwen := false.B
  diffCommit.vecwen := false.B
  diffCommit.v0wen := false.B
  diffCommit.wpdest := io.commit.rd
  diffCommit.wdest := io.commit.rd
  diffCommit.otherwpdest := VecInit(Seq.fill(16)(0.U(diffCommit.wpdest.getWidth.W)))
  diffCommit.pc := io.commit.pc
  diffCommit.instr := io.commit.inst
  diffCommit.robIdx := 0.U
  diffCommit.lqIdx := 0.U
  diffCommit.sqIdx := 0.U
  diffCommit.isLoad := io.commit.isLoad
  diffCommit.isStore := io.commit.isStore
  diffCommit.nFused := 0.U
  diffCommit.setSpecial(isExit = simExitInW)

  diffTrap.coreid := 0.U
  diffTrap.hasTrap := simExitInW
  diffTrap.cycleCnt := cycleCnt
  diffTrap.instrCnt := instrCnt
  diffTrap.hasWFI := false.B
  diffTrap.code := 0.U
  diffTrap.pc := Mux(simExitInW, wStage.decoded.pc, Mux(io.commit.valid, io.commit.pc, wStage.decoded.pc))
}
