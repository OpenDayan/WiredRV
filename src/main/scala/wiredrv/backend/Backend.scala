package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.CoreParams
import wiredrv.bus._
import wiredrv.bus.BusOp.{ReadOnly, StoreOnly}
import wiredrv.frontend.FetchPacket
import xs.utils.sram.SRAMTemplate

class BackendUop(implicit p: CoreParams) extends Bundle {
  val pc = UInt(p.addrBits.W)
  val inst = UInt(32.W)
  val info = new DecodeInfo

  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rd = UInt(5.W)
  val imm = UInt(p.xlen.W)
  val csrAddr = UInt(12.W)
  val exceptionCode = UInt(16.W)

  val src1 = UInt(p.xlen.W)
  val src2 = UInt(p.xlen.W)
  val store_data = UInt(p.xlen.W)
  val src1_forward_w = Bool()
  val src2_forward_w = Bool()
  val store_forward_w = Bool()

  val flushed = Bool()
  val vslot_valid = Bool()
  val vslot = UInt(p.longLatencySlotsBits.W)
}

class DCTag(implicit p: CoreParams) extends Bundle {
  val tag = UInt(p.dcacheTagBits.W)
  val dirty = Bool()
}

class StoreQueueEntry(implicit p: CoreParams) extends Bundle {
  val valid = Bool()
  val way = UInt(log2Ceil(p.dcacheWays).W)
  val idx = UInt((p.dcacheSetBits + log2Ceil(p.dcacheLineBytes / (p.xlen / 8))).W)
  val addr = UInt(p.addrBits.W)
  val mask = UInt((p.xlen / 8).W)
  val data = UInt(p.xlen.W)
  val set = UInt(p.dcacheSetBits.W)
}

object DCacheRefillState {
  val idle :: sendA :: recvD :: writeMeta :: cmoRead :: cmoWriteback :: cmoUpdate :: Nil = Enum(7)
}

object UncachedState {
  val idle :: sendA :: recvD :: Nil = Enum(3)
}

class Backend(implicit p: CoreParams) extends Module {
  require(p.xlen == 64, "Backend currently implements RV64 data path")
  require(p.innerBusWidth == 32, "Backend refill path expects the 32-bit inner bus")
  require(p.dcacheWays == 2, "Backend currently assumes a 2-way data cache")

  val io = IO(new Bundle {
    val inst = Flipped(Decoupled(new FetchPacket))
    val redirects = Output(Vec(3, Valid(UInt(p.addrBits.W))))
    val ifencei = Output(Bool())
    val bus = new BusBundle(p.innerBusWidth)

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

  private def nonZeroReg(reg: UInt): Bool = reg =/= 0.U

  private def inRegions(addr: UInt, regions: Seq[wiredrv.AddrRegion]): Bool =
    regions
      .map(r => addr >= r.base.U(p.addrBits.W) && addr < (r.base + r.size).U(p.addrBits.W))
      .reduceOption(_ || _)
      .getOrElse(false.B)

  private def dcacheDataIdx(set: UInt, word: UInt): UInt = set ## word

  private def dcacheDataIdx(addr: UInt): UInt =
    dcacheDataIdx(p.dcacheGetSet(addr), p.dcacheGetWord(addr))

  private def lineBase(addr: UInt): UInt =
    Cat(addr(p.addrBits - 1, p.dcacheLineBits), 0.U(p.dcacheLineBits.W))

  private def lineBaseFromTag(tag: UInt, set: UInt): UInt =
    Cat(tag, set, 0.U(p.dcacheLineBits.W))

  private def sameWordAddr(a: UInt, b: UInt): Bool =
    a(p.addrBits - 1, wordOffsetBits) === b(p.addrBits - 1, wordOffsetBits)

  private def byteMask(mask: UInt): UInt =
    Cat(mask.asBools.reverse.map(bit => Fill(8, bit)))

  private def selectSrc(sel: UInt, regData: UInt, pc: UInt, imm: UInt): UInt =
    MuxLookup(sel, 0.U(p.xlen.W))(Seq(
      SrcType.reg -> regData,
      SrcType.pc -> pc,
      SrcType.imm -> imm
    ))

  private def isCmoOp(op: UInt): Bool =
    op === LSUOp.cboInval || op === LSUOp.cboClean || op === LSUOp.cboFlush

  private def loadNeedsAlign(op: UInt, addr: UInt): Bool =
    MuxLookup(op, false.B)(Seq(
      LSUOp.lh -> addr(0),
      LSUOp.lhu -> addr(0),
      LSUOp.lw -> addr(1, 0).orR,
      LSUOp.lwu -> addr(1, 0).orR,
      LSUOp.ld -> addr(2, 0).orR
    ))

  private def storeNeedsAlign(op: UInt, addr: UInt): Bool =
    MuxLookup(op, false.B)(Seq(
      LSUOp.sh -> addr(0),
      LSUOp.sw -> addr(1, 0).orR,
      LSUOp.sd -> addr(2, 0).orR
    ))

  private def lsuSize(op: UInt): UInt =
    MuxLookup(op, 0.U(4.W))(Seq(
      LSUOp.lb -> 0.U,
      LSUOp.lbu -> 0.U,
      LSUOp.sb -> 0.U,
      LSUOp.lh -> 1.U,
      LSUOp.lhu -> 1.U,
      LSUOp.sh -> 1.U,
      LSUOp.lw -> 2.U,
      LSUOp.lwu -> 2.U,
      LSUOp.sw -> 2.U,
      LSUOp.ld -> 3.U,
      LSUOp.sd -> 3.U
    ))

  private def storeMask(op: UInt, addr: UInt): UInt = {
    val off = addr(wordOffsetBits - 1, 0)
    MuxLookup(op, 0.U(bytesPerWord.W))(Seq(
      LSUOp.sb -> (1.U(bytesPerWord.W) << off),
      LSUOp.sh -> (3.U(bytesPerWord.W) << off),
      LSUOp.sw -> ("hf".U(bytesPerWord.W) << off),
      LSUOp.sd -> "hff".U(bytesPerWord.W)
    ))
  }

  private def storeData(data: UInt, addr: UInt): UInt =
    data << (addr(wordOffsetBits - 1, 0) << 3)

  private def mergeByMask(base: UInt, data: UInt, mask: UInt): UInt = {
    val bitMask = byteMask(mask)
    (base & ~bitMask) | (data & bitMask)
  }

  private def loadExtend(word: UInt, addr: UInt, op: UInt): UInt = {
    val byteShift = addr(2, 0) << 3
    val halfShift = addr(2, 1) << 4
    val wordShift = addr(2) << 5
    val byte = word >> byteShift
    val half = word >> halfShift
    val w = word >> wordShift
    MuxLookup(op, word)(Seq(
      LSUOp.lb -> Cat(Fill(56, byte(7)), byte(7, 0)),
      LSUOp.lbu -> Cat(0.U(56.W), byte(7, 0)),
      LSUOp.lh -> Cat(Fill(48, half(15)), half(15, 0)),
      LSUOp.lhu -> Cat(0.U(48.W), half(15, 0)),
      LSUOp.lw -> Cat(Fill(32, w(31)), w(31, 0)),
      LSUOp.lwu -> Cat(0.U(32.W), w(31, 0)),
      LSUOp.ld -> word
    ))
  }

  // ============================================================
  // Registers and wires
  // ============================================================

  val rf = RegInit(VecInit(Seq.fill(32)(0.U(p.xlen.W))))

  val e_valid = RegInit(false.B)
  val e_uop = RegInit(0.U.asTypeOf(new BackendUop))
  val w_valid = RegInit(false.B)
  val w_uop = RegInit(0.U.asTypeOf(new BackendUop))

  val w_result = RegInit(0.U(p.xlen.W))
  val w_load_data = RegInit(0.U(p.xlen.W))
  val w_store_entry = RegInit(0.U.asTypeOf(new StoreQueueEntry))
  val w_hit = RegInit(false.B)
  val w_hit_way = RegInit(0.U(log2Ceil(p.dcacheWays).W))
  val w_replace_way = RegInit(0.U(log2Ceil(p.dcacheWays).W))
  val w_miss = RegInit(false.B)
  val w_is_cmo = RegInit(false.B)
  val w_victim_tag = RegInit(0.U.asTypeOf(new DCTag))

  val v_gpr = RegInit(VecInit(Seq.fill(p.longLatencySlots)(0.U(5.W))))
  val div_wb_pending_valid = RegInit(false.B)
  val div_wb_pending = RegInit(0.U.asTypeOf(new MduWriteback))

  val vAllocator = Module(new SBAllocator)
  val alu = Module(new ALU)
  val mulPipe = Module(new MulPipeline)
  val divUnit = Module(new DivUnit)
  val decoder = Module(new Decode)

  val dc_valid = RegInit(0.U.asTypeOf(Vec(p.dcacheWays, Vec(p.dcacheSets, Bool()))))
  val dc_tags = Seq.fill(p.dcacheWays)(Module(new SRAMTemplate(new DCTag, p.dcacheSets, singlePort = true)))
  val dc_datas = Seq.fill(p.dcacheWays)(
    Module(new SRAMTemplate(UInt(p.xlen.W), dataSetCount, singlePort = true, useBitmask = true))
  )
  val store_queue = RegInit(VecInit(Seq.fill(2)(0.U.asTypeOf(new StoreQueueEntry))))
  val next_replace_way = RegInit(0.U(log2Ceil(p.dcacheWays).W))

  val r_state = RegInit(DCacheRefillState.idle)
  val r_a_valid = RegInit(false.B)
  val r_pc = RegInit(0.U(p.addrBits.W))
  val r_addr = RegInit(0.U(p.addrBits.W))
  val r_line_addr = RegInit(0.U(p.addrBits.W))
  val r_wb_addr = RegInit(0.U(p.addrBits.W))
  val r_set = RegInit(0.U(p.dcacheSetBits.W))
  val r_tag = RegInit(0.U(p.dcacheTagBits.W))
  val r_way = RegInit(0.U(log2Ceil(p.dcacheWays).W))
  val r_is_cmo = RegInit(false.B)
  val r_cmo_hit = RegInit(false.B)
  val r_cmo_op = RegInit(LSUOp.none)
  val r_after_wb_refill = RegInit(false.B)
  val r_refill_half = RegInit(0.U(p.innerBusWidth.W))
  val r_wb_word = RegInit(0.U(p.xlen.W))
  val r_wb_word_valid = RegInit(false.B)
  val r_wb_beat = RegInit(0.U(refillBeatShift.W))
  val r_cnt = RegInit(0.U(beatCntBits.W))

  val u_state = RegInit(UncachedState.idle)
  val u_a_valid = RegInit(false.B)
  val u_pc = RegInit(0.U(p.addrBits.W))
  val u_inst = RegInit(0.U(32.W))
  val u_addr = RegInit(0.U(p.addrBits.W))
  val u_op = RegInit(LSUOp.none)
  val u_rd = RegInit(0.U(5.W))
  val u_vslot = RegInit(0.U(p.longLatencySlotsBits.W))
  val u_is_load = RegInit(false.B)
  val u_is_store = RegInit(false.B)
  val u_size = RegInit(0.U(4.W))
  val u_beats = RegInit(1.U(2.W))
  val u_beat = RegInit(0.U(2.W))
  val u_mask = RegInit(0.U(bytesPerWord.W))
  val u_data = RegInit(0.U(p.xlen.W))
  val u_load_word = RegInit(0.U(p.xlen.W))

  val w_redirect = Wire(Valid(UInt(p.addrBits.W)))
  val e_redirect = Wire(Valid(UInt(p.addrBits.W)))
  val d_early_redirect = Wire(Valid(UInt(p.addrBits.W)))

  val w_rfen = WireDefault(false.B)
  val w_rfdata = WireDefault(0.U(p.xlen.W))
  val v_rfen = WireDefault(false.B)
  val v_rf = WireDefault(0.U(5.W))
  val v_rfdata = WireDefault(0.U(p.xlen.W))

  val e_release_vslot = WireDefault(false.B)
  val e_release_slot = WireDefault(0.U(p.longLatencySlotsBits.W))
  val u_release_vslot = WireDefault(false.B)
  val u_release_slot = WireDefault(0.U(p.longLatencySlotsBits.W))
  val w_cancel_release_vslot = WireDefault(false.B)
  val w_cancel_release_slot = WireDefault(0.U(p.longLatencySlotsBits.W))
  val v_release_vslot = WireDefault(false.B)
  val v_release_slot = WireDefault(0.U(p.longLatencySlotsBits.W))
  val cancel_release_pending_valid = RegInit(false.B)
  val cancel_release_pending_slot = RegInit(0.U(p.longLatencySlotsBits.W))

  val dc_tag_w_valid = WireInit(VecInit(Seq.fill(p.dcacheWays)(false.B)))
  val dc_tag_w_data = Wire(Vec(p.dcacheWays, new DCTag))
  val dc_tag_w_set = Wire(Vec(p.dcacheWays, UInt(p.dcacheSetBits.W)))
  val dc_data_w_valid = WireInit(VecInit(Seq.fill(p.dcacheWays)(false.B)))
  val dc_data_w_data = Wire(Vec(p.dcacheWays, UInt(p.xlen.W)))
  val dc_data_w_idx = Wire(Vec(p.dcacheWays, UInt(dataIdxBits.W)))
  val dc_data_w_mask = Wire(Vec(p.dcacheWays, UInt(p.xlen.W)))

  for (i <- 0 until p.dcacheWays) {
    dc_tag_w_data(i) := 0.U.asTypeOf(new DCTag)
    dc_tag_w_set(i) := 0.U
    dc_data_w_data(i) := 0.U
    dc_data_w_idx(i) := 0.U
    dc_data_w_mask(i) := 0.U
  }

  io.redirects.foreach { r =>
    r.valid := false.B
    r.bits := 0.U
  }
  io.redirects(0) := w_redirect
  io.redirects(1) := e_redirect
  io.redirects(2) := d_early_redirect
  io.ifencei := false.B

  io.bus.a.valid := false.B
  io.bus.a.bits := 0.U.asTypeOf(io.bus.a.bits)
  io.bus.d.ready := false.B

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

  vAllocator.io.allocate.ready := false.B
  val release0_normal_valid = e_release_vslot || u_release_vslot
  val release0_pending_valid = cancel_release_pending_valid
  val release0_cancel_direct = w_cancel_release_vslot && !release0_normal_valid && !release0_pending_valid
  vAllocator.io.release(0).valid := release0_normal_valid || release0_pending_valid || release0_cancel_direct
  vAllocator.io.release(0).bits := Mux(
    u_release_vslot,
    u_release_slot,
    Mux(e_release_vslot, e_release_slot, Mux(release0_pending_valid, cancel_release_pending_slot, w_cancel_release_slot))
  )
  vAllocator.io.release(1).valid := v_release_vslot
  vAllocator.io.release(1).bits := v_release_slot

  decoder.io.inst := io.inst.bits.inst
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

  for (i <- 0 until p.dcacheWays) {
    dc_tags(i).io.r.req.valid := false.B
    dc_tags(i).io.r.req.bits := 0.U.asTypeOf(dc_tags(i).io.r.req.bits)
    dc_tags(i).io.w.req.valid := dc_tag_w_valid(i)
    dc_tags(i).io.w.req.bits.apply(dc_tag_w_data(i), dc_tag_w_set(i), 1.U)

    dc_datas(i).io.r.req.valid := false.B
    dc_datas(i).io.r.req.bits := 0.U.asTypeOf(dc_datas(i).io.r.req.bits)
    dc_datas(i).io.w.req.valid := dc_data_w_valid(i)
    dc_datas(i).io.w.req.bits.apply(dc_data_w_data(i), dc_data_w_idx(i), 1.U, dc_data_w_mask(i))
  }

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

  def rfRead(reg: UInt): UInt = Mux(reg === 0.U, 0.U, rf(reg))
  def dForward(reg: UInt, data: UInt): UInt =
    Mux(wGpr === reg && nonZeroReg(reg), w_rfdata, data)

  val d_rs1_rf = rfRead(d_rs1)
  val d_rs2_rf = rfRead(d_rs2)
  val d_rs1_data = dForward(d_rs1, d_rs1_rf)
  val d_rs2_data = dForward(d_rs2, d_rs2_rf)

  val d_src1 = selectSrc(d_dec.info.src1Type, d_rs1_data, d_pc, d_dec.imm)
  val d_src2 = selectSrc(d_dec.info.src2Type, d_rs2_data, d_pc, d_dec.imm)
  val d_lsu_addr = (d_rs1_data + d_dec.imm)(p.addrBits - 1, 0)
  val d_is_cmo = d_dec.info.isLsu && isCmoOp(d_dec.info.fuOpType)
  val d_is_load = d_dec.info.isLsu && !d_dec.info.isStore && !d_is_cmo
  val d_is_store = d_dec.info.isStore
  val d_load_misaligned = d_is_load && loadNeedsAlign(d_dec.info.fuOpType, d_lsu_addr)
  val d_store_misaligned = d_is_store && storeNeedsAlign(d_dec.info.fuOpType, d_lsu_addr)
  val d_lsu_misaligned = d_load_misaligned || d_store_misaligned
  val d_exception_code = Mux(
    d_load_misaligned,
    4.U(16.W),
    Mux(d_store_misaligned, 6.U(16.W), d_dec.exceptionCode)
  )

  def hitE(reg: UInt): Bool = nonZeroReg(reg) && eGpr === reg
  def hitVar(reg: UInt): Bool =
    nonZeroReg(reg) && VecInit((0 until p.longLatencySlots).map(i => v_gpr(i) === reg)).asUInt.orR

  val d_uses_rs1 = d_dec.info.rs1en
  val d_uses_rs2 = d_dec.info.rs2en
  val d_writes_rd = d_dec.info.rwen && nonZeroReg(d_rd)
  val d_is_jal = d_dec.info.fuType === FuType.jmp && d_dec.info.fuOpType === BruOp.jal
  val d_is_jalr = d_dec.info.fuType === FuType.jmp && d_dec.info.fuOpType === BruOp.jalr
  val d_is_uncached = d_dec.info.isLsu && !d_is_cmo && inRegions(d_lsu_addr, p.uncachedRegions)
  val d_is_mext_long_latency = d_dec.info.fuType === FuType.mdu
  val d_is_div = d_is_mext_long_latency && MduCalc.isDivOp(d_dec.info.fuOpType)
  val d_need_vslot = d_is_uncached || (d_is_mext_long_latency && d_writes_rd)
  val e_is_div = e_valid && !e_uop.flushed && e_uop.info.fuType === FuType.mdu && MduCalc.isDivOp(e_uop.info.fuOpType)
  val w_is_div_pipe = w_valid && !w_uop.flushed && w_uop.info.fuType === FuType.mdu && MduCalc.isDivOp(w_uop.info.fuOpType)

  val d_rs1_var_haz = d_uses_rs1 && hitVar(d_rs1)
  val d_rs2_var_haz = d_uses_rs2 && hitVar(d_rs2)
  val d_rd_var_haz = d_writes_rd && hitVar(d_rd)
  val d_rs1_e_haz = d_uses_rs1 && hitE(d_rs1)
  val d_lsu_base_not_ready = d_dec.info.isLsu && d_rs1_e_haz
  val d_jr_not_ready = d_is_jalr && d_uses_rs1 && (hitE(d_rs1) || hitVar(d_rs1))
  val d_no_vslot = d_need_vslot && !vAllocator.io.allocate.valid
  val d_div_resource_busy = d_is_div && d_writes_rd &&
    (e_is_div || w_is_div_pipe || divUnit.io.busy || div_wb_pending_valid)
  val d_cache_busy = r_state =/= DCacheRefillState.idle
  val d_uncached_busy = u_state =/= UncachedState.idle
  val d_stall =
    d_rs1_var_haz || d_rs2_var_haz || d_rd_var_haz ||
      d_lsu_base_not_ready || d_jr_not_ready ||
      d_no_vslot || d_div_resource_busy || d_cache_busy || d_uncached_busy

  io.inst.ready := !d_stall
  val d_fire = io.inst.valid && io.inst.ready
  val d_flushed = d_fire && (e_redirect.valid || w_redirect.valid)
  val d_vslot = vAllocator.io.allocate.bits
  vAllocator.io.allocate.ready := d_fire && d_need_vslot

  val d_make_uop = Wire(new BackendUop)
  d_make_uop.pc := d_pc
  d_make_uop.inst := d_inst
  d_make_uop.info := d_dec.info
  d_make_uop.rs1 := d_rs1
  d_make_uop.rs2 := d_rs2
  d_make_uop.rd := d_rd
  d_make_uop.imm := d_dec.imm
  d_make_uop.csrAddr := d_dec.csrAddr
  d_make_uop.exceptionCode := d_exception_code
  d_make_uop.src1 := d_src1
  d_make_uop.src2 := d_src2
  d_make_uop.store_data := d_rs2_data
  d_make_uop.src1_forward_w := d_dec.info.src1Type === SrcType.reg && hitE(d_rs1)
  d_make_uop.src2_forward_w := d_dec.info.src2Type === SrcType.reg && hitE(d_rs2)
  d_make_uop.store_forward_w := d_dec.info.rs2en && hitE(d_rs2)
  d_make_uop.flushed := d_flushed
  d_make_uop.vslot_valid := d_need_vslot
  d_make_uop.vslot := d_vslot

  val d_jal_target = (d_pc + d_dec.imm)(p.addrBits - 1, 0)
  val d_jalr_target = ((d_rs1_data + d_dec.imm) & ~1.U(p.addrBits.W))(p.addrBits - 1, 0)
  d_early_redirect.valid := d_fire && !d_flushed && (d_is_jal || d_is_jalr)
  d_early_redirect.bits := Mux(d_is_jalr, d_jalr_target, d_jal_target)

  val d_dcache_set = p.dcacheGetSet(d_lsu_addr)
  val d_dcache_idx = dcacheDataIdx(d_lsu_addr)
  val d_cache_access = d_dec.info.isLsu && !d_is_uncached
  val d_read_tag = d_fire && d_cache_access && !d_lsu_misaligned
  val d_read_data = d_fire && d_is_load && !d_is_uncached && !d_lsu_misaligned
  for (i <- 0 until p.dcacheWays) {
    dc_tags(i).io.r.req.valid := d_read_tag
    dc_tags(i).io.r.req.bits.apply(d_dcache_set)
    dc_datas(i).io.r.req.valid := d_read_data
    dc_datas(i).io.r.req.bits.apply(d_dcache_idx)
  }

  // ============================================================
  // E stage
  // ============================================================

  val e_can_side_effect = e_valid && !e_uop.flushed && !e_killed_by_w

  def eForward(sel: Bool, data: UInt): UInt =
    Mux(sel && w_rfen, w_rfdata, data)

  val e_src1 = eForward(e_uop.src1_forward_w, e_uop.src1)
  val e_src2 = eForward(e_uop.src2_forward_w, e_uop.src2)
  val e_store_data = eForward(e_uop.store_forward_w, e_uop.store_data)
  val e_addr = (e_src1 + e_uop.imm)(p.addrBits - 1, 0)

  alu.io.src1 := e_src1
  alu.io.src2 := e_src2
  alu.io.op := e_uop.info.fuOpType(AluOp.width - 1, 0)

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
  e_redirect.valid := e_can_side_effect && e_uop.info.fuType === FuType.bru && e_branch_taken
  e_redirect.bits := (e_uop.pc + e_uop.imm)(p.addrBits - 1, 0)

  val e_set = p.dcacheGetSet(e_addr)
  val e_tag = p.dcacheGetTag(e_addr)
  val e_word = p.dcacheGetWord(e_addr)
  val e_idx = dcacheDataIdx(e_set, e_word)
  val e_is_cmo = e_uop.info.isLsu && isCmoOp(e_uop.info.fuOpType)
  val e_is_uncached = e_uop.info.isLsu && !e_is_cmo && inRegions(e_addr, p.uncachedRegions)
  val e_is_load = e_uop.info.isLsu && !e_uop.info.isStore && !e_is_cmo
  val e_is_store = e_uop.info.isStore
  val e_exception = e_uop.exceptionCode =/= 0.U || e_uop.info.illegal
  val e_way_hits = VecInit((0 until p.dcacheWays).map { i =>
    dc_valid(i)(e_set) && dc_tags(i).io.r.resp.data(0).tag === e_tag
  })
  val e_raw_hit = e_way_hits.asUInt.orR
  val e_hit = e_can_side_effect && e_uop.info.isLsu && !e_is_uncached && !e_exception && e_raw_hit
  val e_hit_way = PriorityEncoder(e_way_hits)
  val e_replace_way = next_replace_way
  val e_hit_tag = Mux(e_hit_way === 0.U, dc_tags(0).io.r.resp.data(0), dc_tags(1).io.r.resp.data(0))
  val e_victim_tag = Mux(e_replace_way === 0.U, dc_tags(0).io.r.resp.data(0), dc_tags(1).io.r.resp.data(0))
  val e_victim_valid = dc_valid(e_replace_way)(e_set)
  val e_cache_word = Mux(e_way_hits(0), dc_datas(0).io.r.resp.data(0), dc_datas(1).io.r.resp.data(0))

  def storeHitsLoad(s: StoreQueueEntry, loadAddr: UInt): Bool =
    s.valid && sameWordAddr(s.addr, loadAddr)

  val e_store_mask = storeMask(e_uop.info.fuOpType, e_addr)
  val e_store_shifted = storeData(e_store_data, e_addr)
  val w_store_forward = w_valid && !w_uop.flushed && w_uop.info.isStore && w_hit && sameWordAddr(w_store_entry.addr, e_addr)
  val after_sq0 = Mux(storeHitsLoad(store_queue(0), e_addr), mergeByMask(e_cache_word, store_queue(0).data, store_queue(0).mask), e_cache_word)
  val after_sq1 = Mux(storeHitsLoad(store_queue(1), e_addr), mergeByMask(after_sq0, store_queue(1).data, store_queue(1).mask), after_sq0)
  val after_w_store = Mux(w_store_forward, mergeByMask(after_sq1, w_store_entry.data, w_store_entry.mask), after_sq1)
  val e_load_data = loadExtend(after_w_store, e_addr, e_uop.info.fuOpType)

  val e_store_entry = Wire(new StoreQueueEntry)
  e_store_entry.valid := e_can_side_effect && e_is_store && e_hit
  e_store_entry.way := e_hit_way
  e_store_entry.idx := e_idx
  e_store_entry.addr := e_addr
  e_store_entry.mask := e_store_mask
  e_store_entry.data := e_store_shifted
  e_store_entry.set := e_set

  val e_miss = e_can_side_effect && e_uop.info.isLsu && !e_is_uncached && !e_exception && !e_is_cmo && !e_hit
  val e_cachable_hit_no_long_latency = e_uop.info.isLsu && (e_exception || (!e_is_uncached && (e_is_cmo || e_hit)))
  e_release_vslot := e_valid && !e_uop.flushed && !e_killed_by_w &&
    e_uop.vslot_valid && e_cachable_hit_no_long_latency
  e_release_slot := e_uop.vslot

  val e_result = MuxCase(alu.io.out, Seq(
    (e_uop.info.fuType === FuType.csr) -> 0.U(p.xlen.W)
  ))

  // ============================================================
  // W stage
  // ============================================================

  val w_can_commit = w_valid && !w_uop.flushed
  val w_exception = w_can_commit && (w_uop.exceptionCode =/= 0.U || w_uop.info.illegal)
  val w_is_load = w_uop.info.isLsu && !w_uop.info.isStore && !w_is_cmo
  val w_is_store = w_uop.info.isStore
  val w_lsu_addr = (w_uop.src1 + w_uop.imm)(p.addrBits - 1, 0)
  val w_is_uncached = w_uop.info.isLsu && !w_is_cmo && inRegions(w_lsu_addr, p.uncachedRegions)
  val w_is_fence_i = w_uop.inst === RV64IInstructions.FENCE_I.value.U
  val w_flush_pipe = w_can_commit && !w_exception && w_uop.info.flushPipe
  val w_store_commit = w_can_commit && !w_is_uncached && w_is_store && w_hit && !w_miss && !w_exception
  val w_start_refill = w_can_commit && !w_is_uncached && w_miss && r_state === DCacheRefillState.idle && u_state === UncachedState.idle
  val w_start_cmo = w_can_commit && !w_is_uncached && w_is_cmo && r_state === DCacheRefillState.idle && u_state === UncachedState.idle
  val w_start_uncached = w_can_commit && w_is_uncached && !w_exception && r_state === DCacheRefillState.idle && u_state === UncachedState.idle
  val w_is_mdu = w_uop.info.fuType === FuType.mdu
  val w_mdu_needs_wb = w_uop.vslot_valid && nonZeroReg(w_uop.rd)
  val w_mdu_can_start = w_can_commit && w_is_mdu && w_mdu_needs_wb && !w_exception && !w_miss
  val w_mdu_is_mul = MduCalc.isMulOp(w_uop.info.fuOpType)
  val w_mdu_is_div = MduCalc.isDivOp(w_uop.info.fuOpType)
  val w_trap_vector = p.resetPc.U(p.addrBits.W)

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

  w_redirect.valid := w_can_commit && (w_miss || w_is_cmo || w_exception || w_flush_pipe || w_start_uncached)
  w_redirect.bits := Mux(
    w_miss,
    w_uop.pc,
    Mux(w_exception, w_trap_vector, (w_uop.pc + 4.U)(p.addrBits - 1, 0))
  )

  w_rfdata := Mux(w_is_load, w_load_data, Mux(w_uop.info.fuType === FuType.jmp, w_uop.pc + 4.U, w_result))
  w_rfen := w_can_commit && !w_exception && w_uop.info.rwen && nonZeroReg(w_uop.rd) && !w_miss && !w_is_uncached && !w_is_mdu
  val w_diff_rfwen = w_can_commit && !w_exception && w_uop.info.rwen && nonZeroReg(w_uop.rd) && !w_miss && !w_is_uncached

  io.ifencei := w_flush_pipe && w_is_fence_i && !w_miss

  val w_commit_valid = w_can_commit && !w_miss && !w_exception && !w_is_uncached
  io.commit.valid := w_commit_valid
  io.commit.bits.pc := w_uop.pc
  io.commit.bits.inst := w_uop.inst
  io.commit.bits.jump := w_redirect.valid
  io.commit.bits.target := w_redirect.bits

  io.diffCommitValid := w_commit_valid
  io.diffCommitPc := w_uop.pc
  io.diffCommitInst := w_uop.inst
  io.diffCommitRfWen := w_diff_rfwen
  io.diffCommitRd := w_uop.rd
  io.diffCommitIsLoad := w_is_load
  io.diffCommitIsStore := w_is_store
  io.diffCommitIsDelayedWb := w_diff_rfwen && w_is_mdu

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
    (w_start_refill || (w_can_commit && w_is_mdu && w_exception)) && w_uop.vslot_valid

  v_release_vslot := long_wb_valid || w_release_vslot
  v_release_slot := Mux(long_wb_valid, long_wb_bits.vslot, w_uop.vslot)
  v_rfen := long_wb_valid && nonZeroReg(long_wb_bits.rd)
  v_rf := long_wb_bits.rd
  v_rfdata := long_wb_bits.data
  io.diffDelayedWbValid := long_wb_valid && nonZeroReg(long_wb_bits.rd)
  io.diffDelayedWbRd := long_wb_bits.rd
  io.diffDelayedWbData := long_wb_bits.data

  val diff_rf = Wire(Vec(32, UInt(p.xlen.W)))
  for (i <- 0 until 32) {
    val w_hit_rf = w_rfen && w_uop.rd === i.U
    val v_hit_rf = v_rfen && v_rf === i.U
    diff_rf(i) := Mux(i.U === 0.U, 0.U, Mux(w_hit_rf, w_rfdata, Mux(v_hit_rf, v_rfdata, rf(i))))
  }
  io.diffRf := diff_rf

  when(v_rfen && nonZeroReg(v_rf)) {
    rf(v_rf) := v_rfdata
  }
  when(w_rfen) {
    rf(w_uop.rd) := w_rfdata
  }
  assert(!(w_rfen && v_rfen && w_uop.rd === v_rf),
    "Normal W writeback and long writeback target the same GPR")
  rf(0) := 0.U

  // ============================================================
  // Store queue
  // ============================================================

  val sq_has_entry = store_queue(0).valid || store_queue(1).valid
  val sq_drain_sel = Mux(store_queue(0).valid, 0.U(1.W), 1.U(1.W))
  val sq_drain_entry = Mux(sq_drain_sel === 0.U, store_queue(0), store_queue(1))
  val sq_can_drain = r_state === DCacheRefillState.idle && !d_read_data && !w_start_refill && !w_start_cmo
  val sq_drain = sq_can_drain && sq_has_entry

  when(sq_drain) {
    val tag_data = Wire(new DCTag)
    tag_data.tag := p.dcacheGetTag(sq_drain_entry.addr)
    tag_data.dirty := true.B
    for (i <- 0 until p.dcacheWays) {
      when(sq_drain_entry.way === i.U) {
        dc_data_w_valid(i) := true.B
        dc_data_w_idx(i) := sq_drain_entry.idx
        dc_data_w_data(i) := sq_drain_entry.data
        dc_data_w_mask(i) := byteMask(sq_drain_entry.mask)
        dc_tag_w_valid(i) := true.B
        dc_tag_w_set(i) := sq_drain_entry.set
        dc_tag_w_data(i) := tag_data
      }
    }
  }

  val sq_after_drain = WireDefault(store_queue)
  when(sq_drain) {
    sq_after_drain(sq_drain_sel).valid := false.B
  }

  val sq_after_clear = Wire(Vec(2, new StoreQueueEntry))
  for (i <- 0 until 2) {
    val clear_mask = Mux(
      w_store_commit && sq_after_drain(i).valid && sameWordAddr(sq_after_drain(i).addr, w_store_entry.addr),
      w_store_entry.mask,
      0.U(bytesPerWord.W)
    )
    val next_mask = sq_after_drain(i).mask & ~clear_mask
    sq_after_clear(i) := sq_after_drain(i)
    sq_after_clear(i).mask := next_mask
    when(sq_after_drain(i).valid && next_mask === 0.U) {
      sq_after_clear(i).valid := false.B
    }
  }

  val sq_final = WireDefault(sq_after_clear)
  when(w_store_commit) {
    when(!sq_after_clear(0).valid) {
      sq_final(0) := w_store_entry
      sq_final(0).valid := true.B
    }.elsewhen(!sq_after_clear(1).valid) {
      sq_final(1) := w_store_entry
      sq_final(1).valid := true.B
    }.otherwise {
      assert(false.B, "Store Queue overflow")
    }
  }
  store_queue := sq_final

  // ============================================================
  // DCache refill and CMO
  // ============================================================

  val r_send_write = r_state === DCacheRefillState.cmoWriteback && r_wb_word_valid
  val r_send_read = r_state === DCacheRefillState.sendA
  val r_write_beat_data = Mux(r_wb_beat === 0.U, r_wb_word(31, 0), r_wb_word(63, 32))
  val r_write_beat_addr = r_wb_addr + (r_cnt << wordOffsetBits) + (r_wb_beat << busOffsetBits)
  val u_send = u_state === UncachedState.sendA
  val u_recv = u_state === UncachedState.recvD
  val u_bus_addr = Cat(u_addr(p.addrBits - 1, busOffsetBits), 0.U(busOffsetBits.W)) + (u_beat << busOffsetBits)
  val u_bus_half = u_bus_addr(wordOffsetBits - 1)
  val u_bus_data = Mux(u_bus_half, u_data(63, 32), u_data(31, 0))
  val u_bus_mask = Mux(u_bus_half, u_mask(7, 4), u_mask(3, 0))
  val u_bus_size = Mux(u_size <= busOffsetBits.U, u_size, busOffsetBits.U)
  val u_resp_word = Mux(
    u_bus_half,
    Cat(io.bus.d.bits.data, u_load_word(31, 0)),
    Cat(u_load_word(63, 32), io.bus.d.bits.data)
  )
  val u_last_beat = u_beat === (u_beats - 1.U)
  val u_complete_can_write = !long_wb_valid

  io.bus.a.valid := Mux(
    r_state =/= DCacheRefillState.idle,
    (r_send_read || r_send_write) && r_a_valid,
    u_send && u_a_valid
  )
  io.bus.a.bits.op := Mux(r_state =/= DCacheRefillState.idle, Mux(r_send_write, StoreOnly, ReadOnly), Mux(u_is_store, StoreOnly, ReadOnly))
  io.bus.a.bits.id := 1.U
  io.bus.a.bits.size := Mux(r_state =/= DCacheRefillState.idle, Mux(r_send_write, log2Ceil(busBytes).U, log2Ceil(p.dcacheLineBytes).U), u_bus_size)
  io.bus.a.bits.address := Mux(r_state =/= DCacheRefillState.idle, Mux(r_send_write, r_write_beat_addr, r_line_addr), u_bus_addr)
  io.bus.a.bits.mask := Mux(r_state =/= DCacheRefillState.idle, Fill(busBytes, 1.U), u_bus_mask)
  io.bus.a.bits.data := Mux(r_state =/= DCacheRefillState.idle, r_write_beat_data, u_bus_data)
  io.bus.d.ready := Mux(
    r_state =/= DCacheRefillState.idle,
    r_state === DCacheRefillState.recvD || r_send_write,
    u_recv && (!u_last_beat || u_complete_can_write)
  )

  when(w_start_refill) {
    r_pc := w_uop.pc
    r_addr := (w_uop.src1 + w_uop.imm)(p.addrBits - 1, 0)
    r_line_addr := lineBase((w_uop.src1 + w_uop.imm)(p.addrBits - 1, 0))
    r_wb_addr := lineBaseFromTag(w_victim_tag.tag, p.dcacheGetSet((w_uop.src1 + w_uop.imm)(p.addrBits - 1, 0)))
    r_set := p.dcacheGetSet((w_uop.src1 + w_uop.imm)(p.addrBits - 1, 0))
    r_tag := p.dcacheGetTag((w_uop.src1 + w_uop.imm)(p.addrBits - 1, 0))
    r_way := w_replace_way
    r_is_cmo := false.B
    r_cmo_hit := false.B
    r_cmo_op := LSUOp.none
    r_cnt := 0.U
    r_wb_beat := 0.U
    r_wb_word_valid := false.B
    r_after_wb_refill := true.B
    next_replace_way := w_replace_way + 1.U
    r_state := DCacheRefillState.sendA
    r_a_valid := true.B
  }.elsewhen(w_start_cmo) {
    val cmo_addr = (w_uop.src1 + w_uop.imm)(p.addrBits - 1, 0)
    r_pc := w_uop.pc
    r_addr := cmo_addr
    r_line_addr := lineBase(cmo_addr)
    r_wb_addr := lineBase(cmo_addr)
    r_set := p.dcacheGetSet(cmo_addr)
    r_tag := p.dcacheGetTag(cmo_addr)
    r_way := w_hit_way
    r_is_cmo := true.B
    r_cmo_hit := w_hit
    r_cmo_op := w_uop.info.fuOpType
    r_cnt := 0.U
    r_wb_beat := 0.U
    r_wb_word_valid := false.B
    r_after_wb_refill := false.B
    when(w_hit && w_victim_tag.dirty && (w_uop.info.fuOpType === LSUOp.cboClean || w_uop.info.fuOpType === LSUOp.cboFlush)) {
      r_state := DCacheRefillState.cmoRead
      r_a_valid := false.B
    }.otherwise {
      r_state := DCacheRefillState.cmoUpdate
      r_a_valid := false.B
    }
  }

  when(io.bus.a.fire) {
    r_a_valid := false.B
  }

  when(r_state === DCacheRefillState.sendA && io.bus.a.fire) {
    r_state := DCacheRefillState.recvD
    r_cnt := 0.U
  }

  when(r_state === DCacheRefillState.recvD && io.bus.d.fire) {
    val refill_word = (r_cnt >> refillBeatShift)(wordBits - 1, 0)
    when(r_cnt(0) === 0.U) {
      r_refill_half := io.bus.d.bits.data
    }.otherwise {
      val refill_data = Cat(io.bus.d.bits.data, r_refill_half)
      val refill_idx = dcacheDataIdx(r_set, refill_word)
      for (i <- 0 until p.dcacheWays) {
        when(r_way === i.U) {
          dc_data_w_valid(i) := true.B
          dc_data_w_idx(i) := refill_idx
          dc_data_w_data(i) := refill_data
          dc_data_w_mask(i) := Fill(p.xlen, 1.U)
        }
      }
    }
    r_cnt := r_cnt + 1.U
    when(r_cnt === (beatsPerLine - 1).U) {
      r_state := DCacheRefillState.writeMeta
    }
  }

  when(r_state === DCacheRefillState.writeMeta) {
    val tag_data = Wire(new DCTag)
    tag_data.tag := r_tag
    tag_data.dirty := false.B
    for (i <- 0 until p.dcacheWays) {
      when(r_way === i.U) {
        dc_tag_w_valid(i) := true.B
        dc_tag_w_set(i) := r_set
        dc_tag_w_data(i) := tag_data
        dc_valid(i)(r_set) := true.B
      }
    }
    r_state := DCacheRefillState.idle
  }

  when(r_state === DCacheRefillState.cmoRead) {
    for (i <- 0 until p.dcacheWays) {
      when(r_way === i.U) {
        dc_datas(i).io.r.req.valid := true.B
        dc_datas(i).io.r.req.bits.apply(dcacheDataIdx(r_set, r_cnt(wordBits - 1, 0)))
      }
    }
    r_wb_word_valid := false.B
    r_state := DCacheRefillState.cmoWriteback
  }

  when(r_state === DCacheRefillState.cmoWriteback) {
    when(!r_wb_word_valid) {
      r_wb_word := Mux(r_way === 0.U, dc_datas(0).io.r.resp.data(0), dc_datas(1).io.r.resp.data(0))
      r_wb_word_valid := true.B
      r_a_valid := true.B
      r_wb_beat := 0.U
    }.elsewhen(io.bus.d.fire) {
      when(r_wb_beat === (refillBeatsPerWord - 1).U) {
        r_wb_beat := 0.U
        r_wb_word_valid := false.B
        when(r_cnt === (wordsPerLine - 1).U) {
          r_cnt := 0.U
          when(r_after_wb_refill) {
            r_state := DCacheRefillState.sendA
            r_a_valid := true.B
          }.otherwise {
            r_state := DCacheRefillState.cmoUpdate
            r_a_valid := false.B
          }
        }.otherwise {
          r_cnt := r_cnt + 1.U
          r_state := DCacheRefillState.cmoRead
          r_a_valid := false.B
        }
      }.otherwise {
        r_wb_beat := r_wb_beat + 1.U
        r_a_valid := true.B
      }
    }
  }

  when(r_state === DCacheRefillState.cmoUpdate) {
    when(r_is_cmo && r_cmo_hit) {
      when(r_cmo_op === LSUOp.cboInval || r_cmo_op === LSUOp.cboFlush) {
        dc_valid(r_way)(r_set) := false.B
      }.elsewhen(r_cmo_op === LSUOp.cboClean) {
        val tag_data = Wire(new DCTag)
        tag_data.tag := r_tag
        tag_data.dirty := false.B
        for (i <- 0 until p.dcacheWays) {
          when(r_way === i.U && dc_valid(i)(r_set)) {
            dc_tag_w_valid(i) := true.B
            dc_tag_w_set(i) := r_set
            dc_tag_w_data(i) := tag_data
          }
        }
      }
    }
    r_state := DCacheRefillState.idle
  }

  // ============================================================
  // Uncached LSU
  // ============================================================

  when(w_start_uncached) {
    val uncached_size = lsuSize(w_uop.info.fuOpType)
    u_pc := w_uop.pc
    u_inst := w_uop.inst
    u_addr := w_lsu_addr
    u_op := w_uop.info.fuOpType
    u_rd := w_uop.rd
    u_vslot := w_uop.vslot
    u_is_load := w_is_load
    u_is_store := w_is_store
    u_size := uncached_size
    u_beats := Mux(uncached_size > busOffsetBits.U, refillBeatsPerWord.U(2.W), 1.U(2.W))
    u_beat := 0.U
    u_mask := Mux(w_is_store, storeMask(w_uop.info.fuOpType, w_lsu_addr), 0.U)
    u_data := Mux(w_is_store, storeData(w_uop.store_data, w_lsu_addr), 0.U)
    u_load_word := 0.U
    u_state := UncachedState.sendA
    u_a_valid := true.B
  }

  when(u_send && io.bus.a.fire) {
    u_a_valid := false.B
    u_state := UncachedState.recvD
  }

  when(u_recv && io.bus.d.fire) {
    when(u_is_load) {
      u_load_word := u_resp_word
    }

    when(u_last_beat) {
      u_state := UncachedState.idle
      u_a_valid := false.B

      u_release_vslot := true.B
      u_release_slot := u_vslot

      v_rfen := u_is_load && nonZeroReg(u_rd)
      v_rf := u_rd
      v_rfdata := loadExtend(u_resp_word, u_addr, u_op)

      when(u_is_load && nonZeroReg(u_rd)) {
        rf(u_rd) := loadExtend(u_resp_word, u_addr, u_op)
      }

      io.commit.valid := true.B
      io.commit.bits.pc := u_pc
      io.commit.bits.inst := u_inst
      io.commit.bits.jump := true.B
      io.commit.bits.target := (u_pc + 4.U)(p.addrBits - 1, 0)

      io.diffCommitValid := true.B
      io.diffCommitPc := u_pc
      io.diffCommitInst := u_inst
      io.diffCommitRfWen := u_is_load && nonZeroReg(u_rd)
      io.diffCommitRd := u_rd
      io.diffCommitIsLoad := u_is_load
      io.diffCommitIsStore := u_is_store
      io.diffCommitIsDelayedWb := false.B
    }.otherwise {
      u_beat := u_beat + 1.U
      u_state := UncachedState.sendA
      u_a_valid := true.B
    }
  }

  // ============================================================
  // Pipeline and scoreboard updates
  // ============================================================

  assert(!(e_release_vslot && u_release_vslot), "E release and uncached release share release(0)")
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
  when(d_fire && d_need_vslot) {
    v_gpr(d_vslot) := Mux(d_dec.info.rwen && nonZeroReg(d_rd), d_rd, 0.U)
  }

  val e_uop_to_w = WireDefault(e_uop)
  e_uop_to_w.src1 := e_src1
  e_uop_to_w.src2 := e_src2
  e_uop_to_w.store_data := e_store_data
  e_uop_to_w.flushed := e_uop.flushed || w_redirect.valid

  w_valid := e_valid
  w_uop := e_uop_to_w
  w_result := e_result
  w_load_data := e_load_data
  w_store_entry := e_store_entry
  w_hit := e_hit
  w_hit_way := e_hit_way
  w_replace_way := e_replace_way
  w_miss := e_miss
  w_is_cmo := e_is_cmo
  w_victim_tag := e_victim_tag
  when(e_is_cmo) {
    w_victim_tag := e_hit_tag
  }

  when(d_fire) {
    e_valid := true.B
    e_uop := d_make_uop
  }.otherwise {
    e_valid := false.B
    e_uop := 0.U.asTypeOf(new BackendUop)
  }
}
