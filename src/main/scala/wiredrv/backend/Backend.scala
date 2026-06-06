package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.CoreParams
import wiredrv.bus._
import wiredrv.frontend.{BranchUpdate, FetchPacket}

class Backend(implicit p: CoreParams) extends Module {
  require(p.xlen == 64, "Backend currently implements RV64 data path")
  require(p.innerBusWidth == 32, "Backend refill path expects the 32-bit inner bus")
  require(p.outerBusWidth == 64, "Backend atomic path expects the 64-bit outer bus")
  require(p.dcacheWays == 2, "Backend currently assumes a 2-way data cache")

  val io = IO(new Bundle {
    val inst = Flipped(Decoupled(new FetchPacket))
    val hartId = Input(UInt(p.maxHartIdBits.W))
    val mtip = Input(Bool())
    val msip = Input(Bool())
    val meip = Input(Bool())
    val debug = Input(Bool())
    val time = Input(Valid(UInt(64.W)))
    val tensorInst = Decoupled(new TensorInst)
    val tensorResp = Flipped(Decoupled(new TensorResp))
    val redirects = Output(Vec(3, Valid(UInt(p.addrBits.W))))
    val branchUpdate = Output(Valid(new BranchUpdate))
    val ifencei = Output(Bool())
    val bus = new BusBundle(p.innerBusWidth)
    val atomicBus = new BusBundle(p.outerBusWidth)

    val commit = Decoupled(new FetchPacket)
    val diffCommitValid = Output(Bool())
    val diffCommitPc = Output(UInt(p.addrBits.W))
    val diffCommitInst = Output(UInt(32.W))
    val diffCommitRfWen = Output(Bool())
    val diffCommitRd = Output(UInt(5.W))
    val diffCommitIsLoad = Output(Bool())
    val diffCommitIsStore = Output(Bool())
    val diffCommitIsDelayedWb = Output(Bool())
    val diffDelayedWbValid = Output(Bool())
    val diffDelayedWbRd = Output(UInt(5.W))
    val diffDelayedWbData = Output(UInt(p.xlen.W))
    val diffRf = Output(Vec(32, UInt(p.xlen.W)))
    val diffCsr = Output(new CoreCsrState)
    val diffTrapValid = Output(Bool())
    val diffTrapIsInterrupt = Output(Bool())
    val diffTrapCode = Output(UInt(p.xlen.W))
    val diffTrapPc = Output(UInt(p.addrBits.W))
    val diffTrapInst = Output(UInt(32.W))
    val diffExit = Output(Bool())
    val simUart = Valid(UInt(8.W))
    val perf = Output(new BackendPerfCounters)
  })

  private val bytesPerWord = p.xlen / 8
  private val wordOffsetBits = log2Ceil(bytesPerWord)
  private val wordsPerLine = p.dcacheLineBytes / bytesPerWord
  private val wordBits = log2Ceil(wordsPerLine)
  private val dataSetCount = p.dcacheSets * wordsPerLine
  private val dataIdxBits = log2Ceil(dataSetCount)
  private val busBytes = p.innerBusWidth / 8
  private val busOffsetBits = log2Ceil(busBytes)
  private val refillBeatsPerWord = bytesPerWord / busBytes
  private val refillBeatShift = log2Ceil(refillBeatsPerWord)
  private val beatsPerLine = p.dcacheLineBytes / busBytes
  private val beatCntBits = log2Ceil(beatsPerLine)
  private val byteIdxBits = log2Ceil(bytesPerWord)
  private val byteCountBits = log2Ceil(bytesPerWord + 1)

  private val lsuUtils = new BackendLsuUtils
  import lsuUtils._

  private def selectSrc(sel: UInt, regData: UInt, pc: UInt, imm: UInt): UInt =
    MuxLookup(sel, 0.U(p.xlen.W))(Seq(
      SrcType.reg -> regData,
      SrcType.pc -> pc,
      SrcType.imm -> imm
    ))

  // ============================================================
  // Registers and wires
  // ============================================================

  val rf = RegInit(VecInit(Seq.fill(32)(0.U(p.xlen.W))))
  val cycleCnt = RegInit(0.U(p.xlen.W))
  val perf = RegInit(0.U.asTypeOf(new BackendPerfCounters))
  cycleCnt := cycleCnt + 1.U
  io.perf := perf

  val e_valid = RegInit(false.B)
  val e_uop = RegInit(0.U.asTypeOf(new BackendUop))
  val e_lsu_base_early_valid = RegInit(false.B)
  val e_lsu_base_early_rd = RegInit(0.U(5.W))
  val e_lsu_base_early_data = RegInit(0.U(p.xlen.W))
  val w_valid = RegInit(false.B)
  val w_uop = RegInit(0.U.asTypeOf(new BackendUop))

  val w_result = RegInit(0.U(p.xlen.W))
  val w_load_data = RegInit(0.U(p.xlen.W))
  val w_store_entry = RegInit(0.U.asTypeOf(new StoreQueueEntry))
  val w_hit = RegInit(false.B)
  val w_lookup_hit = RegInit(false.B)
  val w_lookup_way = RegInit(0.U(log2Ceil(p.dcacheWays).W))
  val w_cache_word = RegInit(0.U(p.xlen.W))
  val w_hit_way = RegInit(0.U(log2Ceil(p.dcacheWays).W))
  val w_replace_way = RegInit(0.U(log2Ceil(p.dcacheWays).W))
  val w_miss = RegInit(false.B)
  val w_is_cmo = RegInit(false.B)
  val w_victim_tag = RegInit(0.U.asTypeOf(new DCTag))
  val w_victim_valid = RegInit(false.B)

  val csr = Module(new BackendCsr)
  val csrWriteCalcAddr = WireDefault(0.U(12.W))
  val csrWriteCalcOp = WireDefault(CsrOp.none)
  val csrWriteCalcOld = WireDefault(0.U(p.xlen.W))
  val csrWriteCalcSrc = WireDefault(0.U(p.xlen.W))
  val csrTrapValid = WireDefault(false.B)
  val csrTrapIsInterrupt = WireDefault(false.B)
  val csrTrapCode = WireDefault(0.U(p.xlen.W))
  val csrTrapPc = WireDefault(0.U(p.addrBits.W))
  val csrTrapMtval = WireDefault(0.U(p.xlen.W))
  val csrMretCommit = WireDefault(false.B)
  val csrWriteCommitValid = WireDefault(false.B)
  val csrWriteCommitAddr = WireDefault(0.U(12.W))
  val csrWriteCommitData = WireDefault(0.U(p.xlen.W))
  csr.io.hartId := io.hartId
  csr.io.time := io.time
  csr.io.cycle := cycleCnt
  csr.io.msip := io.msip
  csr.io.mtip := io.mtip
  csr.io.meip := io.meip
  csr.io.readAddr := e_uop.csrAddr
  csr.io.writeCalc.addr := csrWriteCalcAddr
  csr.io.writeCalc.op := csrWriteCalcOp
  csr.io.writeCalc.old := csrWriteCalcOld
  csr.io.writeCalc.src := csrWriteCalcSrc
  csr.io.trapCommit.valid := csrTrapValid
  csr.io.trapCommit.isInterrupt := csrTrapIsInterrupt
  csr.io.trapCommit.code := csrTrapCode
  csr.io.trapCommit.pc := csrTrapPc
  csr.io.trapCommit.mtval := csrTrapMtval
  csr.io.mretCommit := csrMretCommit
  csr.io.writeCommit.valid := csrWriteCommitValid
  csr.io.writeCommit.addr := csrWriteCommitAddr
  csr.io.writeCommit.data := csrWriteCommitData

  val v_gpr = RegInit(VecInit(Seq.fill(p.longLatencySlots)(0.U(5.W))))
  val div_wb_pending_valid = RegInit(false.B)
  val div_wb_pending = RegInit(0.U.asTypeOf(new MduWriteback))
  val tensorAwaitResp = RegInit(false.B)
  val tensorAwaitBlocksPipe = RegInit(false.B)
  val tensorRespRd = RegInit(0.U(5.W))
  val tensorHasVslot = RegInit(false.B)
  val tensorVslot = RegInit(0.U(p.longLatencySlotsBits.W))

  val vAllocator = Module(new SBAllocator)
  val alu = Module(new ALU)
  val mulPipe = Module(new MulPipeline)
  val divUnit = Module(new DivUnit)
  val decoder = Module(new Decode)
  val lsu = Module(new BackendLsu)
  val tensorIssueQueue = Module(new Queue(new TensorInst, entries = 1))

  val sq_has_entry = lsu.io.sqHasEntry

  val w_redirect = Wire(Valid(UInt(p.addrBits.W)))
  val e_redirect = Wire(Valid(UInt(p.addrBits.W)))
  val d_early_redirect = Wire(Valid(UInt(p.addrBits.W)))

  val w_rfen = WireDefault(false.B)
  val w_rfdata = WireDefault(0.U(p.xlen.W))
  val v_rfen = WireDefault(false.B)
  val v_rf = WireDefault(0.U(5.W))
  val v_rfdata = WireDefault(0.U(p.xlen.W))
  val tensorRespReady = WireDefault(false.B)
  val tensorRespFire = io.tensorResp.valid && tensorRespReady

  val e_release_vslot = WireDefault(false.B)
  val e_release_slot = WireDefault(0.U(p.longLatencySlotsBits.W))
  val w_cancel_release_vslot = WireDefault(false.B)
  val w_cancel_release_slot = WireDefault(0.U(p.longLatencySlotsBits.W))
  val v_release_vslot = WireDefault(false.B)
  val v_release_slot = WireDefault(0.U(p.longLatencySlotsBits.W))
  val cancel_release_pending_valid = RegInit(false.B)
  val cancel_release_pending_slot = RegInit(0.U(p.longLatencySlotsBits.W))

  io.redirects.foreach { r =>
    r.valid := false.B
    r.bits := 0.U
  }
  // Frontend/NPC consume lower indexes as higher redirect priority.
  private val WRedirectIndex = 0
  private val ERedirectIndex = 1
  private val DEarlyRedirectIndex = 2
  io.redirects(WRedirectIndex) := w_redirect
  io.redirects(ERedirectIndex) := e_redirect
  io.redirects(DEarlyRedirectIndex) := d_early_redirect
  io.branchUpdate.valid := false.B
  io.branchUpdate.bits := 0.U.asTypeOf(new BranchUpdate)
  io.ifencei := false.B

  io.bus.a.valid := lsu.io.bus.a.valid
  io.bus.a.bits := lsu.io.bus.a.bits
  lsu.io.bus.a.ready := io.bus.a.ready
  lsu.io.bus.d.valid := io.bus.d.valid
  lsu.io.bus.d.bits := io.bus.d.bits
  io.bus.d.ready := lsu.io.bus.d.ready
  io.atomicBus.a.valid := lsu.io.atomicBus.a.valid
  io.atomicBus.a.bits := lsu.io.atomicBus.a.bits
  lsu.io.atomicBus.a.ready := io.atomicBus.a.ready
  lsu.io.atomicBus.d.valid := io.atomicBus.d.valid
  lsu.io.atomicBus.d.bits := io.atomicBus.d.bits
  io.atomicBus.d.ready := lsu.io.atomicBus.d.ready
  io.tensorResp.ready := tensorRespReady
  io.tensorInst <> tensorIssueQueue.io.deq
  tensorIssueQueue.io.enq.valid := false.B
  tensorIssueQueue.io.enq.bits := 0.U.asTypeOf(new TensorInst)

  io.commit.valid := false.B
  io.commit.bits := 0.U.asTypeOf(new FetchPacket)
  io.diffCommitValid := false.B
  io.diffCommitPc := 0.U
  io.diffCommitInst := 0.U
  io.diffCommitRfWen := false.B
  io.diffCommitRd := 0.U
  io.diffCommitIsLoad := false.B
  io.diffCommitIsStore := false.B
  io.diffCommitIsDelayedWb := false.B
  io.diffDelayedWbValid := false.B
  io.diffDelayedWbRd := 0.U
  io.diffDelayedWbData := 0.U
  io.diffRf := 0.U.asTypeOf(Vec(32, UInt(p.xlen.W)))
  io.diffCsr := csr.io.diff
  io.diffTrapValid := false.B
  io.diffTrapIsInterrupt := false.B
  io.diffTrapCode := 0.U
  io.diffTrapPc := 0.U
  io.diffTrapInst := 0.U
  io.diffExit := false.B
  io.simUart := lsu.io.simUart

  vAllocator.io.allocate.ready := false.B
  val lsu_release_vslot = lsu.io.slow.valid && lsu.io.slow.releaseVslot
  val lsu_release_slot = lsu.io.slow.releaseSlot
  val release0_normal_valid = e_release_vslot || lsu_release_vslot
  val release0_pending_valid = cancel_release_pending_valid
  val release0_cancel_direct = w_cancel_release_vslot && !release0_normal_valid && !release0_pending_valid
  vAllocator.io.release(0).valid := release0_normal_valid || release0_pending_valid || release0_cancel_direct
  vAllocator.io.release(0).bits := Mux(
    lsu_release_vslot,
    lsu_release_slot,
    Mux(e_release_vslot, e_release_slot, Mux(release0_pending_valid, cancel_release_pending_slot, w_cancel_release_slot))
  )
  vAllocator.io.release(1).valid := v_release_vslot
  vAllocator.io.release(1).bits := v_release_slot

  decoder.io.inst := io.inst.bits.inst
  decoder.io.instWord := io.inst.bits.instWord
  alu.io.src1 := 0.U
  alu.io.src2 := 0.U
  alu.io.op := AluOp.add
  mulPipe.io.in.valid := false.B
  mulPipe.io.in.bits := 0.U.asTypeOf(mulPipe.io.in.bits)
  divUnit.io.start := false.B
  divUnit.io.src1 := 0.U
  divUnit.io.src2 := 0.U
  divUnit.io.op := MduOp.div
  divUnit.io.rd := 0.U
  divUnit.io.vslot := 0.U

  // ============================================================
  // D stage
  // ============================================================

  val d_dec = decoder.io.out
  val d_pc = io.inst.bits.pc
  val d_inst = io.inst.bits.inst
  val d_rs1 = d_dec.rs1
  val d_rs2 = d_dec.rs2
  val d_rd = d_dec.rd
  val e_killed_by_w = e_valid && w_redirect.valid
  val eGpr = Mux(e_valid && !e_uop.flushed && !e_uop.vslot_valid && e_uop.info.rwen && nonZeroReg(e_uop.rd), e_uop.rd, 0.U(5.W))
  val wGpr = Mux(w_rfen, w_uop.rd, 0.U(5.W))

  def hitE(reg: UInt): Bool = nonZeroReg(reg) && eGpr === reg
  def hitVar(reg: UInt): Bool =
    nonZeroReg(reg) && VecInit((0 until p.longLatencySlots).map(i => v_gpr(i) === reg)).asUInt.orR
  def longWbHit(reg: UInt): Bool =
    v_rfen && nonZeroReg(reg) && v_rf === reg

  def rfRead(reg: UInt): UInt = Mux(reg === 0.U, 0.U, rf(reg))
  def dForward(reg: UInt, data: UInt): UInt =
    Mux(wGpr === reg && nonZeroReg(reg), w_rfdata, Mux(longWbHit(reg), v_rfdata, data))

  val d_rs1_rf = rfRead(d_rs1)
  val d_rs2_rf = rfRead(d_rs2)
  val d_rd_rf = rfRead(d_rd)
  val d_rs1_data = dForward(d_rs1, d_rs1_rf)
  val d_rs2_data = dForward(d_rs2, d_rs2_rf)
  val d_rd_data = dForward(d_rd, d_rd_rf)

  def eForward(sel: Bool, data: UInt): UInt =
    Mux(sel && w_rfen, w_rfdata, data)

  val e_src1 = eForward(e_uop.src1_forward_w, e_uop.src1)
  val e_src2 = eForward(e_uop.src2_forward_w, e_uop.src2)
  val e_store_data = eForward(e_uop.store_forward_w, e_uop.store_data)

  alu.io.src1 := e_src1
  alu.io.src2 := e_src2
  alu.io.op := e_uop.info.fuOpType(AluOp.width - 1, 0)

  val d_src1 = selectSrc(d_dec.info.src1Type, d_rs1_data, d_pc, d_dec.imm)
  val d_src2 = selectSrc(d_dec.info.src2Type, d_rs2_data, d_pc, d_dec.imm)
  val d_src1_waits_e = d_dec.info.src1Type === SrcType.reg && hitE(d_rs1)
  val d_src2_waits_e = d_dec.info.src2Type === SrcType.reg && hitE(d_rs2)
  val d_add_result_ready =
    d_dec.info.fuType === FuType.alu &&
      d_dec.info.fuOpType === AluOp.add &&
      !d_dec.exceptionValid && !d_dec.info.illegal &&
      d_dec.info.rwen && nonZeroReg(d_rd) &&
      !d_src1_waits_e && !d_src2_waits_e
  val d_add_early_result = (d_src1 + d_src2)(p.xlen - 1, 0)
  val d_lsu_base_e_forward = d_dec.info.isLsu && d_dec.info.rs1en &&
    e_valid && !e_uop.flushed && !e_killed_by_w &&
    e_lsu_base_early_valid && e_lsu_base_early_rd === d_rs1
  val d_lsu_base_data = Mux(d_lsu_base_e_forward, e_lsu_base_early_data, d_rs1_data)
  val d_lsu_addr = (d_lsu_base_data + d_dec.imm)(p.addrBits - 1, 0)
  val d_is_cmo = d_dec.info.isLsu && isCmoOp(d_dec.info.fuOpType)
  val d_is_amo = d_dec.info.isLsu && LSUOp.isAmo(d_dec.info.fuOpType)
  val d_is_amocas = d_dec.info.isLsu && LSUOp.isAmoCas(d_dec.info.fuOpType)
  val d_is_load = d_dec.info.isLsu && (!d_dec.info.isStore || d_is_amo) && !d_is_cmo
  val d_is_store = d_dec.info.isStore
  val d_is_tensor = d_dec.info.fuType === FuType.tensor
  val d_is_fence = d_inst(6, 0) === "b0001111".U && d_inst(14, 12) === "b000".U
  val d_is_nemu_trap = d_inst === RV64IInstructions.NEMU_TRAP.value.U
  val d_load_misaligned = d_is_load && loadNeedsAlign(d_dec.info.fuOpType, d_lsu_addr)
  val d_store_misaligned = d_is_store && storeNeedsAlign(d_dec.info.fuOpType, d_lsu_addr)
  val d_amo_misaligned = d_is_amo && amoNeedsAlign(d_dec.info.fuOpType, d_lsu_addr)
  val d_lsu_misaligned = d_load_misaligned || d_store_misaligned || d_amo_misaligned

  val d_uses_rs1 = d_dec.info.rs1en
  val d_uses_rs2 = d_dec.info.rs2en
  val d_writes_rd = d_dec.info.rwen && nonZeroReg(d_rd)
  val d_is_jal = d_dec.info.fuType === FuType.jmp && d_dec.info.fuOpType === BruOp.jal
  val d_is_jalr = d_dec.info.fuType === FuType.jmp && d_dec.info.fuOpType === BruOp.jalr
  val d_jal_target = (d_pc + d_dec.imm)(p.addrBits - 1, 0)
  val d_jalr_target = ((d_rs1_data + d_dec.imm) & ~1.U(p.addrBits.W))(p.addrBits - 1, 0)
  val d_jump_target_misaligned = (d_is_jal && d_jal_target(1)) || (d_is_jalr && d_jalr_target(1))
  val d_exception_valid = d_dec.exceptionValid || d_jump_target_misaligned || d_amo_misaligned
  val d_exception_code = Mux(d_jump_target_misaligned, 0.U(16.W),
    Mux(d_amo_misaligned, 6.U(16.W), d_dec.exceptionCode))
  val d_is_uncached = d_dec.info.isLsu && !d_is_cmo && (d_is_amo || inRegions(d_lsu_addr, p.uncachedRegions))
  val d_cache_lookup = d_dec.info.isLsu && !d_is_uncached
  val d_cache_access = d_cache_lookup && !d_lsu_misaligned
  val d_is_mext_long_latency = d_dec.info.fuType === FuType.mdu
  val d_is_div = d_is_mext_long_latency && MduCalc.isDivOp(d_dec.info.fuOpType)
  val d_is_tensor_resp = d_is_tensor && d_dec.info.tensorExpectsResp
  val d_need_vslot_raw = d_is_uncached || d_lsu_misaligned ||
    ((d_is_mext_long_latency || d_is_tensor_resp) && d_writes_rd)
  val e_is_div = e_valid && !e_uop.flushed && e_uop.info.fuType === FuType.mdu && MduCalc.isDivOp(e_uop.info.fuOpType)
  val w_is_div_pipe = w_valid && !w_uop.flushed && w_uop.info.fuType === FuType.mdu && MduCalc.isDivOp(w_uop.info.fuOpType)
  val e_is_tensor_pipe = e_valid && !e_uop.flushed && !e_killed_by_w && e_uop.info.fuType === FuType.tensor
  val w_is_tensor_pipe = w_valid && !w_uop.flushed && w_uop.info.fuType === FuType.tensor
  val e_tensor_blocks_pipe = e_is_tensor_pipe && e_uop.info.tensorBlocksPipe
  val w_tensor_blocks_pipe = w_is_tensor_pipe && w_uop.info.tensorBlocksPipe
  val d_tensor_bar_stall = tensorAwaitBlocksPipe || e_tensor_blocks_pipe || w_tensor_blocks_pipe
  val e_result_forwardable_to_w =
    e_valid && !e_uop.flushed && !e_killed_by_w &&
      !e_uop.vslot_valid && e_uop.info.rwen && nonZeroReg(e_uop.rd) &&
      !e_uop.exceptionValid && !e_uop.info.illegal &&
      e_uop.info.fuType === FuType.alu
  def hitEForwardable(reg: UInt): Bool = hitE(reg) && e_result_forwardable_to_w

  val d_rs1_var_haz = d_uses_rs1 && hitVar(d_rs1) && !longWbHit(d_rs1)
  val d_rs2_var_haz = d_uses_rs2 && hitVar(d_rs2) && !longWbHit(d_rs2)
  val d_rd_var_haz = d_writes_rd && hitVar(d_rd)
  val d_rs1_e_raw_haz = d_uses_rs1 && hitE(d_rs1)
  val d_rs1_e_forwardable = d_uses_rs1 && hitEForwardable(d_rs1)
  val d_rs2_e_forwardable = d_uses_rs2 && hitEForwardable(d_rs2)
  val d_rs1_e_haz = d_rs1_e_raw_haz && !d_lsu_base_e_forward && !(d_rs1_e_forwardable && !d_dec.info.isLsu)
  val d_rs2_e_haz = d_uses_rs2 && hitE(d_rs2) && !d_rs2_e_forwardable
  val d_rd_src_e_haz = d_is_amocas && nonZeroReg(d_rd) && hitE(d_rd)
  val d_tensor_rd_src_e_haz = d_dec.info.rdAsSrc && nonZeroReg(d_rd) && hitE(d_rd)
  val d_rd_src_var_haz =
    (d_is_amocas || d_dec.info.rdAsSrc) && nonZeroReg(d_rd) && hitVar(d_rd) && !longWbHit(d_rd)
  val d_e_raw_haz = d_rs1_e_haz || d_rs2_e_haz || d_rd_src_e_haz || d_tensor_rd_src_e_haz
  val d_rs1_w_haz = d_uses_rs1 && nonZeroReg(d_rs1) && wGpr === d_rs1
  val d_rs2_w_haz = d_uses_rs2 && nonZeroReg(d_rs2) && wGpr === d_rs2
  val d_w_raw_haz = d_rs1_w_haz || d_rs2_w_haz
  val d_lsu_base_not_ready = d_dec.info.isLsu && d_rs1_e_raw_haz && !d_lsu_base_e_forward
  val d_jr_not_ready = d_is_jalr && d_uses_rs1 && (hitE(d_rs1) || (hitVar(d_rs1) && !longWbHit(d_rs1)))
  val d_no_vslot = d_need_vslot_raw && !vAllocator.io.allocate.valid
  val d_tensor_busy = d_is_tensor && (tensorAwaitResp || e_is_tensor_pipe || w_is_tensor_pipe || !tensorIssueQueue.io.enq.ready)
  val d_div_resource_busy = d_is_div && d_writes_rd &&
    (e_is_div || w_is_div_pipe || divUnit.io.busy || div_wb_pending_valid)
  val d_cache_busy = lsu.io.cacheBusy
  val d_uncached_busy = lsu.io.uncachedBusy
  val d_inflight_store_wait =
    (e_valid && !e_uop.flushed && !e_killed_by_w &&
      (e_uop.info.isStore || (e_uop.info.isLsu && LSUOp.isAmo(e_uop.info.fuOpType)))) ||
      (w_valid && !w_uop.flushed &&
        (w_uop.info.isStore || (w_uop.info.isLsu && LSUOp.isAmo(w_uop.info.fuOpType))))
  val d_load_can_use_store_forward =
    d_is_load && !d_is_amo && !d_is_uncached && !d_lsu_misaligned && !d_is_cmo
  val d_store_queue_wait =
    d_dec.info.isLsu && !d_load_can_use_store_forward && (sq_has_entry || d_inflight_store_wait)
  val d_fence_wait = d_is_fence && (
    sq_has_entry ||
      (e_valid && !e_uop.flushed && e_uop.info.isLsu) ||
      (w_valid && !w_uop.flushed && w_uop.info.isStore) ||
      d_cache_busy || d_uncached_busy
  )
  val d_misaligned_wait = d_lsu_misaligned && (
    sq_has_entry ||
      (e_valid && !e_uop.flushed && e_uop.info.isStore) ||
      (w_valid && !w_uop.flushed && w_uop.info.isStore) ||
      d_cache_busy || d_uncached_busy
  )
  val d_nemu_trap_wait = d_is_nemu_trap && (
    sq_has_entry ||
      d_inflight_store_wait ||
      (e_valid && !e_uop.flushed) ||
      (w_valid && !w_uop.flushed) ||
      d_cache_busy || d_uncached_busy
  )
  val d_data_hazard_stall =
    d_rs1_var_haz || d_rs2_var_haz || d_rd_var_haz || d_rd_src_var_haz ||
      d_e_raw_haz || d_lsu_base_not_ready || d_jr_not_ready
  val d_long_latency_stall = d_no_vslot || d_tensor_busy || d_div_resource_busy
  val d_lsu_cache_stall = d_cache_busy || d_uncached_busy || d_store_queue_wait
  val d_ordering_stall = d_fence_wait || d_misaligned_wait || d_nemu_trap_wait
  val d_stall =
    d_data_hazard_stall || d_long_latency_stall || d_lsu_cache_stall || d_ordering_stall ||
      d_tensor_bar_stall

  io.inst.ready := !d_stall
  val d_fire = io.inst.valid && io.inst.ready
  val d_interrupt_enabled = csr.io.interruptEnabled
  val d_interrupt_cause = csr.io.interruptCause
  val d_interrupt_take = d_fire && d_interrupt_enabled
  val d_flushed = d_fire && (e_redirect.valid || w_redirect.valid)
  val d_enqueue = d_fire && !d_flushed
  val d_vslot = vAllocator.io.allocate.bits
  val d_need_vslot = d_need_vslot_raw && !d_interrupt_take
  vAllocator.io.allocate.ready := d_enqueue && d_need_vslot

  val d_make_uop = Wire(new BackendUop)
  val d_uop_info = WireDefault(d_dec.info)
  when(d_interrupt_take) {
    d_uop_info := 0.U.asTypeOf(new DecodeInfo)
    d_uop_info.fuType := FuType.csr
  }
  d_make_uop.pc := d_pc
  d_make_uop.inst := d_inst
  d_make_uop.instWord := io.inst.bits.instWord
  d_make_uop.predictedJump := io.inst.bits.jump
  d_make_uop.predictedTarget := io.inst.bits.target
  d_make_uop.info := d_uop_info
  d_make_uop.rs1 := d_rs1
  d_make_uop.rs2 := d_rs2
  d_make_uop.rd := d_rd
  d_make_uop.imm := d_dec.imm
  d_make_uop.csrAddr := d_dec.csrAddr
  d_make_uop.exceptionValid := d_interrupt_take || d_exception_valid
  d_make_uop.exceptionCode := Mux(d_interrupt_take, Cat(0.U(12.W), d_interrupt_cause), d_exception_code)
  d_make_uop.isInterrupt := d_interrupt_take
  d_make_uop.src1 := Mux(d_dec.info.isLsu && d_dec.info.rs1en, d_lsu_base_data, d_src1)
  d_make_uop.src2 := d_src2
  d_make_uop.store_data := d_rs2_data
  d_make_uop.rdData := d_rd_data
  d_make_uop.src1_forward_w := !d_dec.info.isLsu && d_dec.info.src1Type === SrcType.reg && d_rs1_e_forwardable
  d_make_uop.src2_forward_w := d_dec.info.src2Type === SrcType.reg && d_rs2_e_forwardable
  d_make_uop.store_forward_w := d_dec.info.rs2en && d_rs2_e_forwardable
  d_make_uop.flushed := d_flushed
  d_make_uop.vslot_valid := d_need_vslot
  d_make_uop.vslot := d_vslot

  val d_jal_predicted = io.inst.bits.jump && io.inst.bits.target === d_jal_target
  d_early_redirect.valid := d_fire && !d_flushed && !d_interrupt_take && !d_jump_target_misaligned &&
    ((d_is_jal && !d_jal_predicted) || d_is_jalr)
  d_early_redirect.bits := Mux(d_is_jalr, d_jalr_target, d_jal_target)

  val d_read_tag = d_enqueue && !d_interrupt_take && d_cache_lookup
  val d_read_data = d_enqueue && !d_interrupt_take && d_is_load && !d_is_uncached && !d_is_cmo
  lsu.io.d.addr := d_lsu_addr
  lsu.io.d.readTag := d_read_tag
  lsu.io.d.readData := d_read_data

  // ============================================================
  // E stage
  // ============================================================

  val e_can_side_effect = e_valid && !e_uop.flushed && !e_killed_by_w

  val e_addr = (e_src1 + e_uop.imm)(p.addrBits - 1, 0)

  val e_eq = e_src1 === e_src2
  val e_lt = e_src1.asSInt < e_src2.asSInt
  val e_ltu = e_src1 < e_src2
  val e_branch_taken = MuxLookup(e_uop.info.fuOpType, false.B)(Seq(
    BruOp.beq -> e_eq,
    BruOp.bne -> !e_eq,
    BruOp.blt -> e_lt,
    BruOp.bge -> !e_lt,
    BruOp.bltu -> e_ltu,
    BruOp.bgeu -> !e_ltu
  ))
  val e_branch_target = (e_uop.pc + e_uop.imm)(p.addrBits - 1, 0)
  val e_branch_fallthrough = (e_uop.pc + 4.U)(p.addrBits - 1, 0)
  val e_branch_actual_target = Mux(e_branch_taken, e_branch_target, e_branch_fallthrough)
  val e_branch_target_misaligned = e_uop.info.fuType === FuType.bru && e_branch_taken && e_branch_target(1)
  val e_branch_predicted =
    e_uop.predictedJump === e_branch_taken &&
      (!e_branch_taken || e_uop.predictedTarget === e_branch_actual_target)
  val e_branch_needs_redirect = e_uop.info.fuType === FuType.bru && !e_branch_predicted
  e_redirect.valid := e_can_side_effect && e_branch_needs_redirect && !e_branch_target_misaligned
  e_redirect.bits := e_branch_actual_target
  io.branchUpdate.valid := e_can_side_effect && e_uop.info.fuType === FuType.bru
  io.branchUpdate.bits.pc := e_uop.pc
  io.branchUpdate.bits.taken := e_branch_taken

  val w_store_forward = w_valid && !w_uop.flushed && w_uop.info.isStore && w_hit && sameWordAddr(w_store_entry.addr, e_addr)
  lsu.io.e.canSideEffect := e_can_side_effect
  lsu.io.e.uop := e_uop
  lsu.io.e.addr := e_addr
  lsu.io.e.storeData := e_store_data
  lsu.io.e.wStoreForwardValid := w_store_forward
  lsu.io.e.wStoreForwardEntry := w_store_entry

  val e_lsu_resp = lsu.io.eResp
  val e_hit = e_lsu_resp.hit
  val e_lookup_hit = e_lsu_resp.lookupHit
  val e_hit_way = e_lsu_resp.hitWay
  val e_replace_way = e_lsu_resp.replaceWay
  val e_cache_word = e_lsu_resp.cacheWord
  val e_load_data = e_lsu_resp.loadData
  val e_store_entry = e_lsu_resp.storeEntry
  val e_miss = e_lsu_resp.miss
  val e_is_cmo = e_lsu_resp.isCmo
  val e_victim_tag = e_lsu_resp.victimTag
  val e_victim_valid = e_lsu_resp.victimValid
  e_release_vslot := e_lsu_resp.releaseVslot
  e_release_slot := e_lsu_resp.releaseSlot

  val w_csr_op = w_uop.info.fuType === FuType.csr &&
    (w_uop.info.fuOpType === CsrOp.csrrw || w_uop.info.fuOpType === CsrOp.csrrs || w_uop.info.fuOpType === CsrOp.csrrc)
  val w_csr_src = Mux(w_uop.info.src2Type === SrcType.imm, w_uop.src2, w_uop.src1)
  csrWriteCalcAddr := w_uop.csrAddr
  csrWriteCalcOp := w_uop.info.fuOpType(CsrOp.width - 1, 0)
  csrWriteCalcOld := w_result
  csrWriteCalcSrc := w_csr_src
  val w_csr_writes = w_csr_op && csr.io.writeEnable
  val w_csr_write_data = csr.io.writeData
  val w_csr_bypass_valid = w_valid && !w_uop.flushed &&
    !w_uop.isInterrupt && !w_uop.exceptionValid && !w_uop.info.illegal &&
    w_csr_writes && csr.io.writeModeledWritable
  val e_csr_read_data = Mux(
    w_csr_bypass_valid && w_uop.csrAddr === e_uop.csrAddr,
    w_csr_write_data,
    csr.io.readData
  )

  val e_result = MuxCase(alu.io.out, Seq(
    (e_uop.info.fuType === FuType.csr) -> e_csr_read_data
  ))

  // ============================================================
  // W stage
  // ============================================================

  val w_can_commit = w_valid && !w_uop.flushed
  val w_is_nemu_trap = w_uop.inst === RV64IInstructions.NEMU_TRAP.value.U
  val w_trap = w_can_commit && !w_is_nemu_trap && (w_uop.isInterrupt || w_uop.exceptionValid || w_uop.info.illegal)
  val w_is_ecall = w_uop.inst === RV64IInstructions.ECALL.value.U
  val w_ecall_code = Mux(csr.io.privilegeMode === 0.U, 8.U(16.W), Mux(csr.io.privilegeMode === 1.U, 9.U(16.W), 11.U(16.W)))
  val w_trap_code = Mux(
    w_uop.info.illegal && !w_uop.isInterrupt,
    2.U(16.W),
    Mux(w_is_ecall && !w_uop.isInterrupt, w_ecall_code, w_uop.exceptionCode)
  )
  val w_trap_code_xlen = Cat(0.U((p.xlen - 16).W), w_trap_code)
  val w_trap_mtval = Mux(
    !w_uop.isInterrupt && w_trap_code === 3.U,
    Cat(0.U((p.xlen - p.addrBits).W), w_uop.pc),
    0.U(p.xlen.W)
  )
  val w_is_amo = w_uop.info.isLsu && LSUOp.isAmo(w_uop.info.fuOpType)
  val w_is_load = w_uop.info.isLsu && (!w_uop.info.isStore || w_is_amo) && !w_is_cmo
  val w_is_store = w_uop.info.isStore
  val w_lsu_addr = (w_uop.src1 + w_uop.imm)(p.addrBits - 1, 0)
  val w_is_uncached = w_uop.info.isLsu && !w_is_cmo && (w_is_amo || inRegions(w_lsu_addr, p.uncachedRegions))
  val w_load_misaligned = w_is_load && loadNeedsAlign(w_uop.info.fuOpType, w_lsu_addr)
  val w_store_misaligned = w_is_store && storeNeedsAlign(w_uop.info.fuOpType, w_lsu_addr)
  val w_lsu_misaligned = w_load_misaligned || w_store_misaligned
  val w_cached_misaligned = w_lsu_misaligned && !w_is_uncached
  val w_lsu_bypass = w_is_uncached || w_lsu_misaligned
  val long_wb_busy_for_lsu = mulPipe.io.out.valid || div_wb_pending_valid || divUnit.io.out.valid
  lsu.io.w.canCommit := w_can_commit
  lsu.io.w.trap := w_trap
  lsu.io.w.uop := w_uop
  lsu.io.w.lsuAddr := w_lsu_addr
  lsu.io.w.isAmo := w_is_amo
  lsu.io.w.isLoad := w_is_load
  lsu.io.w.isStore := w_is_store
  lsu.io.w.isUncached := w_is_uncached
  lsu.io.w.lsuMisaligned := w_lsu_misaligned
  lsu.io.w.cachedMisaligned := w_cached_misaligned
  lsu.io.w.lsuBypass := w_lsu_bypass
  lsu.io.w.hit := w_hit
  lsu.io.w.lookupHit := w_lookup_hit
  lsu.io.w.lookupWay := w_lookup_way
  lsu.io.w.cacheWord := w_cache_word
  lsu.io.w.hitWay := w_hit_way
  lsu.io.w.replaceWay := w_replace_way
  lsu.io.w.miss := w_miss
  lsu.io.w.isCmo := w_is_cmo
  lsu.io.w.victimTag := w_victim_tag
  lsu.io.w.victimValid := w_victim_valid
  lsu.io.w.storeEntry := w_store_entry
  lsu.io.w.longWbValid := long_wb_busy_for_lsu
  val w_is_fence_i = w_uop.inst === RV64IInstructions.FENCE_I.value.U
  val w_is_mret = w_uop.info.fuType === FuType.csr && w_uop.info.fuOpType === CsrOp.mret
  val w_mret_commit = w_can_commit && !w_trap && w_is_mret
  val w_flush_pipe = w_can_commit && !w_trap && !w_mret_commit && w_uop.info.flushPipe
  val w_store_commit = lsu.io.storeCommit
  val w_start_refill = lsu.io.startRefill
  val w_start_cmo = lsu.io.startCmo
  val w_start_cached_misaligned = lsu.io.startCachedMisaligned
  val w_start_uncached = lsu.io.startUncached
  val w_is_mdu = w_uop.info.fuType === FuType.mdu
  val w_is_tensor = w_uop.info.fuType === FuType.tensor
  val w_mdu_needs_wb = w_uop.vslot_valid && nonZeroReg(w_uop.rd)
  val w_mdu_can_start = w_can_commit && w_is_mdu && w_mdu_needs_wb && !w_trap && !w_miss
  val w_mdu_is_mul = MduCalc.isMulOp(w_uop.info.fuOpType)
  val w_mdu_is_div = MduCalc.isDivOp(w_uop.info.fuOpType)
  csrTrapValid := w_trap
  csrTrapIsInterrupt := w_uop.isInterrupt
  csrTrapCode := w_trap_code_xlen
  csrTrapPc := w_uop.pc
  csrTrapMtval := w_trap_mtval
  csrMretCommit := w_mret_commit
  val w_trap_vector = csr.io.trapVector
  val w_csr_write_commit = w_can_commit && !w_trap && !w_mret_commit && !w_miss &&
    w_csr_writes && csr.io.writeModeledWritable
  csrWriteCommitValid := w_csr_write_commit
  csrWriteCommitAddr := w_uop.csrAddr
  csrWriteCommitData := w_csr_write_data

  mulPipe.io.in.valid := w_mdu_can_start && w_mdu_is_mul
  mulPipe.io.in.bits.src1 := w_uop.src1
  mulPipe.io.in.bits.src2 := w_uop.src2
  mulPipe.io.in.bits.op := w_uop.info.fuOpType(MduOp.width - 1, 0)
  mulPipe.io.in.bits.rd := w_uop.rd
  mulPipe.io.in.bits.vslot := w_uop.vslot

  divUnit.io.start := w_mdu_can_start && w_mdu_is_div && !divUnit.io.busy && !div_wb_pending_valid
  divUnit.io.src1 := w_uop.src1
  divUnit.io.src2 := w_uop.src2
  divUnit.io.op := w_uop.info.fuOpType(MduOp.width - 1, 0)
  divUnit.io.rd := w_uop.rd
  divUnit.io.vslot := w_uop.vslot
  assert(!(w_mdu_can_start && w_mdu_is_div && (divUnit.io.busy || div_wb_pending_valid)),
    "DivUnit accepted a second request while busy")

  w_redirect.valid := w_can_commit &&
    (w_miss || w_is_cmo || w_trap || w_mret_commit || w_flush_pipe || w_start_cached_misaligned || w_start_uncached)
  w_redirect.bits := Mux(
    w_trap,
    w_trap_vector,
    Mux(w_mret_commit, csr.io.mretTarget, Mux(w_miss, w_uop.pc, (w_uop.pc + 4.U)(p.addrBits - 1, 0)))
  )

  val w_tensor_issue = w_can_commit && !w_trap && !w_mret_commit && w_is_tensor
  tensorIssueQueue.io.enq.valid := w_tensor_issue
  tensorIssueQueue.io.enq.bits.instWord := w_uop.instWord
  tensorIssueQueue.io.enq.bits.rs1Data := w_uop.src1
  tensorIssueQueue.io.enq.bits.rs2Data := w_uop.src2
  tensorIssueQueue.io.enq.bits.rdData := w_uop.rdData
  tensorIssueQueue.io.enq.bits.expectsResp := w_uop.info.tensorExpectsResp
  assert(!w_tensor_issue || tensorIssueQueue.io.enq.ready,
    "Tensor issue queue was full at commit")

  w_rfdata := Mux(w_is_load, w_load_data, Mux(w_uop.info.fuType === FuType.jmp, w_uop.pc + 4.U, w_result))
  w_rfen := w_can_commit && !w_trap && !w_mret_commit && w_uop.info.rwen &&
    nonZeroReg(w_uop.rd) && !w_miss && !w_lsu_bypass && !w_is_mdu && !w_is_tensor
  val w_diff_rfwen = w_can_commit && !w_trap && !w_mret_commit && w_uop.info.rwen && nonZeroReg(w_uop.rd) && !w_miss && !w_lsu_bypass

  io.ifencei := w_flush_pipe && w_is_fence_i && !w_miss

  val w_commit_valid = w_can_commit && !w_miss && !w_trap && !w_lsu_bypass && !w_is_cmo
  val w_exit = w_commit_valid && w_is_nemu_trap
  io.commit.valid := w_commit_valid
  io.commit.bits.pc := w_uop.pc
  io.commit.bits.inst := w_uop.inst
  io.commit.bits.instWord := w_uop.instWord
  io.commit.bits.jump := w_uop.predictedJump || w_redirect.valid
  io.commit.bits.target := Mux(w_uop.predictedJump, w_uop.predictedTarget, w_redirect.bits)

  io.diffCommitValid := w_commit_valid
  io.diffCommitPc := w_uop.pc
  io.diffCommitInst := w_uop.inst
  io.diffCommitRfWen := w_diff_rfwen
  io.diffCommitRd := w_uop.rd
  io.diffCommitIsLoad := w_is_load
  io.diffCommitIsStore := w_is_store
  io.diffCommitIsDelayedWb := w_diff_rfwen && (w_is_mdu || w_is_tensor)
  io.diffTrapValid := w_trap
  io.diffTrapIsInterrupt := w_uop.isInterrupt
  io.diffTrapCode := w_trap_code_xlen
  io.diffTrapPc := w_uop.pc
  io.diffTrapInst := w_uop.inst
  io.diffExit := w_exit

  val service_div_pending = div_wb_pending_valid && !mulPipe.io.out.valid
  when(service_div_pending) {
    div_wb_pending_valid := false.B
  }
  when(divUnit.io.out.valid && (mulPipe.io.out.valid || div_wb_pending_valid)) {
    assert(!div_wb_pending_valid, "Div writeback buffer overflow")
    div_wb_pending_valid := true.B
    div_wb_pending := divUnit.io.out.bits
  }

  val long_wb_valid = mulPipe.io.out.valid || div_wb_pending_valid || divUnit.io.out.valid
  val long_wb_bits = Mux(
    mulPipe.io.out.valid,
    mulPipe.io.out.bits,
    Mux(div_wb_pending_valid, div_wb_pending, divUnit.io.out.bits)
  )
  w_cancel_release_vslot := w_valid && w_uop.flushed && w_uop.vslot_valid
  w_cancel_release_slot := w_uop.vslot
  val w_release_vslot =
    (w_start_refill || (w_can_commit && (w_is_mdu || w_is_tensor) && w_trap)) && w_uop.vslot_valid

  tensorRespReady := tensorAwaitResp && !long_wb_valid && !w_release_vslot &&
    !lsu.io.uncachedBusy && !lsu.io.misalignedBusy
  val tensor_wb_valid = tensorRespFire && nonZeroReg(io.tensorResp.bits.rd)
  val tensor_release_vslot = tensorRespFire && tensorHasVslot

  v_release_vslot := long_wb_valid || tensor_release_vslot || w_release_vslot
  v_release_slot := Mux(
    long_wb_valid,
    long_wb_bits.vslot,
    Mux(tensor_release_vslot, tensorVslot, w_uop.vslot)
  )
  v_rfen := (long_wb_valid && nonZeroReg(long_wb_bits.rd)) || tensor_wb_valid
  v_rf := Mux(long_wb_valid, long_wb_bits.rd, io.tensorResp.bits.rd)
  v_rfdata := Mux(long_wb_valid, long_wb_bits.data, io.tensorResp.bits.data)
  io.diffDelayedWbValid := (long_wb_valid && nonZeroReg(long_wb_bits.rd)) || tensor_wb_valid
  io.diffDelayedWbRd := Mux(long_wb_valid, long_wb_bits.rd, io.tensorResp.bits.rd)
  io.diffDelayedWbData := Mux(long_wb_valid, long_wb_bits.data, io.tensorResp.bits.data)

  when(lsu.io.slow.valid) {
    v_rfen := lsu.io.slow.rfWen
    v_rf := lsu.io.slow.rd
    v_rfdata := lsu.io.slow.rfData

    io.commit.valid := true.B
    io.commit.bits := lsu.io.slow.packet
    io.diffCommitValid := true.B
    io.diffCommitPc := lsu.io.slow.packet.pc
    io.diffCommitInst := lsu.io.slow.packet.inst
    io.diffCommitRfWen := lsu.io.slow.rfWen
    io.diffCommitRd := lsu.io.slow.rd
    io.diffCommitIsLoad := lsu.io.slow.isLoad
    io.diffCommitIsStore := lsu.io.slow.isStore
    io.diffCommitIsDelayedWb := false.B
    io.diffExit := lsu.io.slow.exit
  }

  val diff_rf = Wire(Vec(32, UInt(p.xlen.W)))
  for (i <- 0 until 32) {
    val w_hit_rf = w_rfen && w_uop.rd === i.U
    val v_hit_rf = v_rfen && v_rf === i.U
    diff_rf(i) := Mux(i.U === 0.U, 0.U, Mux(w_hit_rf, w_rfdata, Mux(v_hit_rf, v_rfdata, rf(i))))
  }
  io.diffRf := diff_rf

  when(w_tensor_issue && w_uop.info.tensorExpectsResp) {
    assert(!tensorAwaitResp, "Tensor response request issued while another response is pending")
    tensorAwaitResp := true.B
    tensorAwaitBlocksPipe := w_uop.info.tensorBlocksPipe
    tensorRespRd := w_uop.rd
    tensorHasVslot := w_uop.vslot_valid && nonZeroReg(w_uop.rd)
    tensorVslot := w_uop.vslot
  }
  when(tensorRespFire) {
    assert(io.tensorResp.bits.rd === tensorRespRd,
      "Tensor response rd did not match the outstanding Tensor request")
    tensorAwaitResp := false.B
    tensorAwaitBlocksPipe := false.B
    tensorHasVslot := false.B
  }

  when(v_rfen && nonZeroReg(v_rf)) {
    rf(v_rf) := v_rfdata
  }
  when(w_rfen) {
    rf(w_uop.rd) := w_rfdata
  }
  assert(!(w_rfen && v_rfen && w_uop.rd === v_rf),
    "Normal W writeback and long writeback target the same GPR")
  rf(0) := 0.U

  val d_stall_sample = io.inst.valid && d_stall
  perf.cycles := perf.cycles + 1.U
  when(w_commit_valid) {
    perf.commits := perf.commits + 1.U
    when(w_is_load) { perf.loadCommit := perf.loadCommit + 1.U }
    when(w_is_store) { perf.storeCommit := perf.storeCommit + 1.U }
    when(w_uop.info.fuType === FuType.jmp) { perf.jumpCommit := perf.jumpCommit + 1.U }
    when(w_uop.info.fuType === FuType.bru) { perf.branchCommit := perf.branchCommit + 1.U }
  }
  when(d_stall_sample) {
    perf.dStall := perf.dStall + 1.U
    when(d_rs1_var_haz) { perf.dRs1VarHaz := perf.dRs1VarHaz + 1.U }
    when(d_rs2_var_haz) { perf.dRs2VarHaz := perf.dRs2VarHaz + 1.U }
    when(d_rd_var_haz) { perf.dRdVarHaz := perf.dRdVarHaz + 1.U }
    when(d_e_raw_haz) { perf.dERawHaz := perf.dERawHaz + 1.U }
    when(d_w_raw_haz) { perf.dWRawHaz := perf.dWRawHaz + 1.U }
    when(d_lsu_base_not_ready) { perf.dLsuBaseNotReady := perf.dLsuBaseNotReady + 1.U }
    when(d_jr_not_ready) { perf.dJrNotReady := perf.dJrNotReady + 1.U }
    when(d_no_vslot) { perf.dNoVslot := perf.dNoVslot + 1.U }
    when(d_div_resource_busy) { perf.dDivResourceBusy := perf.dDivResourceBusy + 1.U }
    when(d_cache_busy) { perf.dCacheBusy := perf.dCacheBusy + 1.U }
    when(d_uncached_busy) { perf.dUncachedBusy := perf.dUncachedBusy + 1.U }
    when(d_store_queue_wait) { perf.dStoreQueueWait := perf.dStoreQueueWait + 1.U }
    when(d_inflight_store_wait) { perf.dInflightStoreWait := perf.dInflightStoreWait + 1.U }
    when(d_fence_wait) { perf.dFenceWait := perf.dFenceWait + 1.U }
    when(d_misaligned_wait) { perf.dMisalignedWait := perf.dMisalignedWait + 1.U }
    when(d_nemu_trap_wait) { perf.dNemuTrapWait := perf.dNemuTrapWait + 1.U }
  }
  when(e_redirect.valid) {
    perf.branchRedirect := perf.branchRedirect + 1.U
  }
  when(e_lsu_resp.dcacheLoadHit) {
    perf.dcacheLoadHit := perf.dcacheLoadHit + 1.U
  }
  when(e_lsu_resp.dcacheStoreHit) {
    perf.dcacheStoreHit := perf.dcacheStoreHit + 1.U
  }
  when(w_start_refill) {
    perf.dcacheMiss := perf.dcacheMiss + 1.U
    when(w_is_load) { perf.dcacheMissLoad := perf.dcacheMissLoad + 1.U }
    when(w_is_store) { perf.dcacheMissStore := perf.dcacheMissStore + 1.U }
    when(w_victim_valid && w_victim_tag.dirty) {
      perf.dcacheVictimWriteback := perf.dcacheVictimWriteback + 1.U
    }
  }
  val lsuPerf = lsu.io.perf
  when(lsuPerf.dcacheRefillCycle) {
    perf.dcacheRefillCycles := perf.dcacheRefillCycles + 1.U
  }
  when(lsuPerf.dcacheRefillRespWait) {
    perf.dcacheRefillRespWait := perf.dcacheRefillRespWait + 1.U
  }
  when(lsuPerf.dcacheVictimWritebackCycle) {
    perf.dcacheVictimWritebackCycles := perf.dcacheVictimWritebackCycles + 1.U
  }
  when(lsuPerf.cmoStart) {
    perf.cmoStart := perf.cmoStart + 1.U
  }
  when(lsuPerf.cmoCycle) {
    perf.cmoCycles := perf.cmoCycles + 1.U
  }
  when(lsuPerf.uncachedAccess) {
    perf.uncachedAccess := perf.uncachedAccess + 1.U
  }
  when(lsuPerf.uncachedCycle) {
    perf.uncachedCycles := perf.uncachedCycles + 1.U
  }
  when(lsuPerf.storeQueueEnq =/= 0.U) {
    perf.storeQueueEnq := perf.storeQueueEnq + lsuPerf.storeQueueEnq
  }
  when(lsuPerf.storeQueueDrain) {
    perf.storeQueueDrain := perf.storeQueueDrain + 1.U
  }
  when(lsuPerf.storeQueueFullCycle) {
    perf.storeQueueFullCycles := perf.storeQueueFullCycles + 1.U
  }
  when(lsuPerf.storeQueueOneEntryCycle) {
    perf.storeQueueOneEntryCycles := perf.storeQueueOneEntryCycles + 1.U
  }
  when(lsuPerf.dBusAWait) {
    perf.dBusAWait := perf.dBusAWait + 1.U
  }
  when(lsuPerf.dBusDWait) {
    perf.dBusDWait := perf.dBusDWait + 1.U
  }
  when(lsuPerf.dBusDWaitRefill) {
    perf.dBusDWaitRefill := perf.dBusDWaitRefill + 1.U
  }
  when(lsuPerf.dBusDWaitUncached) {
    perf.dBusDWaitUncached := perf.dBusDWaitUncached + 1.U
  }

  // ============================================================
  // Pipeline and scoreboard updates
  // ============================================================

  assert(!(e_release_vslot && lsu_release_vslot), "E release and LSU slow release share release(0)")
  assert(!(vAllocator.io.release(0).fire && vAllocator.io.release(1).fire &&
    vAllocator.io.release(0).bits === vAllocator.io.release(1).bits))

  val release0_pending_fire = cancel_release_pending_valid && !release0_normal_valid &&
    vAllocator.io.release(0).fire
  val cancel_release_to_pending = w_cancel_release_vslot && !release0_cancel_direct
  when(release0_pending_fire) {
    cancel_release_pending_valid := false.B
  }
  when(cancel_release_to_pending) {
    assert(!cancel_release_pending_valid || release0_pending_fire, "Flush vslot release pending buffer overflow")
    cancel_release_pending_valid := true.B
    cancel_release_pending_slot := w_cancel_release_slot
  }

  when(vAllocator.io.release(0).fire) {
    v_gpr(vAllocator.io.release(0).bits) := 0.U
  }
  when(vAllocator.io.release(1).fire) {
    v_gpr(vAllocator.io.release(1).bits) := 0.U
  }
  when(d_enqueue && d_need_vslot) {
    v_gpr(d_vslot) := Mux(d_dec.info.rwen && nonZeroReg(d_rd), d_rd, 0.U)
  }

  val e_uop_to_w = WireDefault(e_uop)
  e_uop_to_w.src1 := e_src1
  e_uop_to_w.src2 := e_src2
  e_uop_to_w.store_data := e_store_data
  e_uop_to_w.exceptionValid := e_uop.exceptionValid || (e_can_side_effect && e_branch_target_misaligned)
  e_uop_to_w.exceptionCode := Mux(e_can_side_effect && e_branch_target_misaligned, 0.U(16.W), e_uop.exceptionCode)
  e_uop_to_w.flushed := e_uop.flushed || w_redirect.valid

  w_valid := e_valid
  w_uop := e_uop_to_w
  w_result := e_result
  w_load_data := e_load_data
  w_store_entry := e_store_entry
  w_hit := e_hit
  w_lookup_hit := e_lookup_hit
  w_lookup_way := e_hit_way
  w_cache_word := e_cache_word
  w_hit_way := e_hit_way
  w_replace_way := e_replace_way
  w_miss := e_miss
  w_is_cmo := e_is_cmo
  w_victim_tag := e_victim_tag
  w_victim_valid := e_victim_valid

  when(d_enqueue) {
    e_valid := true.B
    e_uop := d_make_uop
    e_lsu_base_early_valid := d_add_result_ready && !d_flushed
    e_lsu_base_early_rd := d_rd
    e_lsu_base_early_data := d_add_early_result
  }.otherwise {
    e_valid := false.B
    e_uop := 0.U.asTypeOf(new BackendUop)
    e_lsu_base_early_valid := false.B
  }
}
