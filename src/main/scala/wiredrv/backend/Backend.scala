package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.CoreParams
import wiredrv.bus._
import wiredrv.bus.BusOp.ReadOnly
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
  val src1 = UInt(p.xlen.W)
  val src2 = UInt(p.xlen.W)
  val storeData = UInt(p.xlen.W)
  val lsuAddr = UInt(p.addrBits.W)
  val csrAddr = UInt(12.W)
  val exceptionCode = UInt(16.W)
  val flushed = Bool()
  val isUncached = Bool()
  val vslotValid = Bool()
  val vslot = UInt(p.longLatencySlotsBits.W)
}

class DCacheTag(implicit p: CoreParams) extends Bundle {
  val tag = UInt(p.dcacheTagBits.W)
  val dirty = Bool()
}

class StoreQueueEntry(implicit p: CoreParams) extends Bundle {
  val valid = Bool()
  val way = UInt(log2Ceil(p.dcacheWays).W)
  val set = UInt(p.dcacheSetBits.W)
  val word = UInt(log2Ceil(p.dcacheLineBytes / (p.xlen / 8)).W)
  val addr = UInt(p.addrBits.W)
  val mask = UInt((p.xlen / 8).W)
  val data = UInt(p.xlen.W)
}

class Backend(implicit p: CoreParams) extends Module {
  require(p.xlen == 64, "Backend currently implements RV64 data path")
  require(p.innerBusWidth == 32, "Backend refill path currently uses 32-bit bus beats")
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
    val diffRf = Output(Vec(32, UInt(p.xlen.W)))
  })

  private val bytesPerWord = p.xlen / 8
  private val wordOffsetBits = log2Ceil(bytesPerWord)
  private val wordsPerLine = p.dcacheLineBytes / bytesPerWord
  private val dataSetCount = p.dcacheSets * wordsPerLine
  private val dataIdxBits = log2Ceil(dataSetCount)
  private val busBytes = p.innerBusWidth / 8
  private val beatsPerLine = p.dcacheLineBytes / busBytes
  private val beatCntBits = log2Ceil(beatsPerLine)
  private val refillWordBeat = p.xlen / p.innerBusWidth
  require(refillWordBeat == 2, "Backend refill currently packs two bus beats into one XLEN word")

  private def dcacheGetTag(addr: UInt): UInt =
    addr(p.addrBits - 1, p.dcacheSetBits + p.dcacheLineBits)

  private def dcacheGetSet(addr: UInt): UInt =
    addr(p.dcacheSetBits + p.dcacheLineBits - 1, p.dcacheLineBits)

  private def dcacheGetWord(addr: UInt): UInt =
    addr(p.dcacheLineBits - 1, wordOffsetBits)

  private def dcacheDataIdx(addr: UInt): UInt =
    dcacheGetSet(addr) ## dcacheGetWord(addr)

  private def lineBase(addr: UInt): UInt =
    Cat(addr(p.addrBits - 1, p.dcacheLineBits), 0.U(p.dcacheLineBits.W))

  private def inRegions(addr: UInt, regions: Seq[wiredrv.AddrRegion]): Bool =
    regions
      .map(r => addr >= r.base.U(p.addrBits.W) && addr < (r.base + r.size).U(p.addrBits.W))
      .reduceOption(_ || _)
      .getOrElse(false.B)

  private def nonZeroReg(reg: UInt): Bool = reg =/= 0.U

  private def sameWordAddr(a: UInt, b: UInt): Bool =
    a(p.addrBits - 1, wordOffsetBits) === b(p.addrBits - 1, wordOffsetBits)

  private def byteMask(mask: UInt): UInt =
    Cat(mask.asBools.reverse.map(bit => Fill(8, bit)))

  private def storeMask(op: UInt, addr: UInt): UInt = {
    val shift = addr(wordOffsetBits - 1, 0)
    val base = WireDefault(0.U(bytesPerWord.W))
    switch(op) {
      is(LSUOp.sb) { base := 1.U }
      is(LSUOp.sh) { base := 3.U }
      is(LSUOp.sw) { base := 15.U }
      is(LSUOp.sd) { base := 255.U }
    }
    (base << shift)(bytesPerWord - 1, 0)
  }

  private def shiftStoreData(op: UInt, addr: UInt, data: UInt): UInt = {
    val shift = addr(wordOffsetBits - 1, 0) << 3
    val narrow = WireDefault(0.U(p.xlen.W))
    switch(op) {
      is(LSUOp.sb) { narrow := data(7, 0) }
      is(LSUOp.sh) { narrow := data(15, 0) }
      is(LSUOp.sw) { narrow := data(31, 0) }
      is(LSUOp.sd) { narrow := data }
    }
    narrow << shift
  }

  private def loadExtend(op: UInt, addr: UInt, data: UInt): UInt = {
    val shift = addr(wordOffsetBits - 1, 0) << 3
    val shifted = data >> shift
    val out = WireDefault(shifted)
    switch(op) {
      is(LSUOp.lb)  { out := Cat(Fill(56, shifted(7)), shifted(7, 0)) }
      is(LSUOp.lh)  { out := Cat(Fill(48, shifted(15)), shifted(15, 0)) }
      is(LSUOp.lw)  { out := Cat(Fill(32, shifted(31)), shifted(31, 0)) }
      is(LSUOp.ld)  { out := shifted }
      is(LSUOp.lbu) { out := shifted(7, 0) }
      is(LSUOp.lhu) { out := shifted(15, 0) }
      is(LSUOp.lwu) { out := shifted(31, 0) }
    }
    out
  }

  private def mergeByMask(base: UInt, data: UInt, mask: UInt): UInt = {
    val bitMask = byteMask(mask)
    (base & ~bitMask) | (data & bitMask)
  }

  //==========================================================================
  // 寄存器和线网定义
  //==========================================================================

  val rf = RegInit(VecInit(Seq.fill(32)(0.U(p.xlen.W))))

  val e_valid = RegInit(false.B)
  val e_uop = Reg(new BackendUop)

  val w_valid = RegInit(false.B)
  val w_uop = Reg(new BackendUop)
  val w_result = Reg(UInt(p.xlen.W))
  val w_loadData = Reg(UInt(p.xlen.W))
  val w_storeEntry = Reg(new StoreQueueEntry)
  val w_cacheHit = Reg(Bool())
  val w_hitWay = Reg(UInt(log2Ceil(p.dcacheWays).W))
  val w_cacheMiss = Reg(Bool())
  val w_isCmo = Reg(Bool())

  val v_valid = RegInit(VecInit(Seq.fill(p.longLatencySlots)(false.B)))
  val v_gpr = Reg(Vec(p.longLatencySlots, UInt(5.W)))
  val vAllocator = Module(new SBAllocator)

  val alu = Module(new ALU)
  val decoder = Module(new Decode)

  val dcValid = RegInit(0.U.asTypeOf(Vec(p.dcacheWays, Vec(p.dcacheSets, Bool()))))
  val dcTags = Seq.fill(p.dcacheWays)(Module(new SRAMTemplate(new DCacheTag, p.dcacheSets, singlePort = true)))
  val dcDatas = Seq.fill(p.dcacheWays)(Module(new SRAMTemplate(UInt(p.xlen.W), dataSetCount, singlePort = true, useBitmask = true)))

  val sq = RegInit(VecInit(Seq.fill(2)(0.U.asTypeOf(new StoreQueueEntry))))

  val rIdle :: rRefill :: rCmo :: Nil = Enum(3)
  val r_state = RegInit(rIdle)
  val r_aValid = RegInit(false.B)
  val r_way = Reg(UInt(log2Ceil(p.dcacheWays).W))
  val r_set = Reg(UInt(p.dcacheSetBits.W))
  val r_tag = Reg(UInt(p.dcacheTagBits.W))
  val r_addr = Reg(UInt(p.addrBits.W))
  val r_cnt = Reg(UInt(beatCntBits.W))
  val r_half = Reg(UInt(p.innerBusWidth.W))
  val r_cmoHit = Reg(Bool())
  val r_cmoOp = Reg(UInt(LSUOp.width.W))
  val nextReplaceWay = RegInit(0.U(log2Ceil(p.dcacheWays).W))

  val w_redirect = Wire(Valid(UInt(p.addrBits.W)))
  val e_redirect = Wire(Valid(UInt(p.addrBits.W)))
  val d_redirect = Wire(Valid(UInt(p.addrBits.W)))

  val w_rfen = Wire(Bool())
  val w_rfdata = Wire(UInt(p.xlen.W))
  val e_forwardValid = Wire(Bool())
  val e_forwardData = Wire(UInt(p.xlen.W))
  val v_rfen = WireDefault(false.B)
  val v_rf = WireDefault(0.U(5.W))
  val v_rfdata = WireDefault(0.U(p.xlen.W))

  val e_release0 = Wire(Bool())
  val e_release0Slot = Wire(UInt(p.longLatencySlotsBits.W))
  val v_release1 = WireDefault(false.B)
  val v_release1Slot = WireDefault(0.U(p.longLatencySlotsBits.W))

  val dc_tag_w_valid = WireInit(VecInit(Seq.fill(p.dcacheWays)(false.B)))
  val dc_tag_w_data = Wire(Vec(p.dcacheWays, new DCacheTag))
  val dc_tag_w_set = Wire(Vec(p.dcacheWays, UInt(p.dcacheSetBits.W)))
  val dc_data_w_valid = WireInit(VecInit(Seq.fill(p.dcacheWays)(false.B)))
  val dc_data_w_data = Wire(Vec(p.dcacheWays, UInt(p.xlen.W)))
  val dc_data_w_idx = Wire(Vec(p.dcacheWays, UInt(dataIdxBits.W)))
  val dc_data_w_mask = Wire(Vec(p.dcacheWays, UInt(p.xlen.W)))

  for (i <- 0 until p.dcacheWays) {
    dc_tag_w_data(i) := DontCare
    dc_tag_w_set(i) := DontCare
    dc_data_w_data(i) := DontCare
    dc_data_w_idx(i) := DontCare
    dc_data_w_mask(i) := DontCare
  }

  decoder.io.inst := io.inst.bits.inst

  io.redirects(0) := w_redirect
  io.redirects(1) := e_redirect
  io.redirects(2) := d_redirect
  io.ifencei := false.B

  io.bus.a.valid := false.B
  io.bus.a.bits := DontCare
  io.bus.d.ready := false.B

  io.commit.valid := false.B
  io.commit.bits := DontCare
  io.diffCommitValid := false.B
  io.diffCommitPc := DontCare
  io.diffCommitInst := DontCare
  io.diffCommitRfWen := false.B
  io.diffCommitRd := DontCare
  io.diffCommitIsLoad := false.B
  io.diffCommitIsStore := false.B
  io.diffRf := DontCare

  vAllocator.io.allocate.ready := false.B
  vAllocator.io.release(0).valid := e_release0
  vAllocator.io.release(0).bits := e_release0Slot
  vAllocator.io.release(1).valid := v_release1
  vAllocator.io.release(1).bits := v_release1Slot

  for (i <- 0 until p.dcacheWays) {
    dcTags(i).io.r.req.valid := false.B
    dcTags(i).io.r.req.bits := DontCare
    dcTags(i).io.w.req.valid := dc_tag_w_valid(i)
    dcTags(i).io.w.req.bits.apply(dc_tag_w_data(i), dc_tag_w_set(i), 1.U)

    dcDatas(i).io.r.req.valid := false.B
    dcDatas(i).io.r.req.bits := DontCare
    dcDatas(i).io.w.req.valid := dc_data_w_valid(i)
    dcDatas(i).io.w.req.bits.apply(dc_data_w_data(i), dc_data_w_idx(i), 1.U, dc_data_w_mask(i))
  }

  //==========================================================================
  // D 级：译码、冒险检查、前递和早期重定向
  //==========================================================================

  val d_dec = decoder.io.out
  val d_pc = io.inst.bits.pc
  val d_inst = io.inst.bits.inst
  val d_rs1 = d_dec.rs1
  val d_rs2 = d_dec.rs2
  val d_rd = d_dec.rd
  val d_isCmo = d_dec.info.isLsu && (
    d_dec.info.fuOpType === LSUOp.cboInval ||
      d_dec.info.fuOpType === LSUOp.cboClean ||
      d_dec.info.fuOpType === LSUOp.cboFlush
  )
  val d_rs1Rf = Mux(d_rs1 === 0.U, 0.U, rf(d_rs1))
  val d_rs2Rf = Mux(d_rs2 === 0.U, 0.U, rf(d_rs2))
  val d_rs1EForward = e_forwardValid && d_dec.info.rs1en && d_rs1 === e_uop.rd && nonZeroReg(d_rs1)
  val d_rs2EForward = e_forwardValid && d_dec.info.rs2en && d_rs2 === e_uop.rd && nonZeroReg(d_rs2)
  val d_rs1WForward = w_rfen && d_rs1 === w_uop.rd && nonZeroReg(d_rs1)
  val d_rs2WForward = w_rfen && d_rs2 === w_uop.rd && nonZeroReg(d_rs2)
  val d_rs1Data = Mux(d_rs1EForward, e_forwardData, Mux(d_rs1WForward, w_rfdata, d_rs1Rf))
  val d_rs2Data = Mux(d_rs2EForward, e_forwardData, Mux(d_rs2WForward, w_rfdata, d_rs2Rf))
  val d_isLoad = d_dec.info.isLsu && !d_dec.info.isStore && !d_isCmo
  val d_lsuAddr = (d_rs1Data + d_dec.imm)(p.addrBits - 1, 0)
  val d_isUncached = d_dec.info.isLsu && inRegions(d_lsuAddr, p.uncachedRegions)

  val d_src1 = WireDefault(0.U(p.xlen.W))
  val d_src2 = WireDefault(0.U(p.xlen.W))
  switch(d_dec.info.src1Type) {
    is(SrcType.reg) { d_src1 := d_rs1Data }
    is(SrcType.pc)  { d_src1 := d_pc }
  }
  switch(d_dec.info.src2Type) {
    is(SrcType.reg) { d_src2 := d_rs2Data }
    is(SrcType.imm) { d_src2 := d_dec.imm }
    is(SrcType.pc)  { d_src2 := d_pc }
  }

  val e_fixedValid = e_valid && !e_uop.flushed && e_uop.info.rwen && nonZeroReg(e_uop.rd)
  val w_fixedValid = w_valid && !w_uop.flushed && w_uop.info.rwen && nonZeroReg(w_uop.rd)

  val d_rs1EHit = e_fixedValid && d_dec.info.rs1en && d_rs1 === e_uop.rd
  val d_rs1WHit = w_fixedValid && d_dec.info.rs1en && d_rs1 === w_uop.rd

  def vHaz(reg: UInt, en: Bool, allowERelease: Bool): Bool = {
    VecInit((0 until p.longLatencySlots).map { i =>
      val releasedByE = allowERelease && e_release0 && e_release0Slot === i.U
      v_valid(i) && !releasedByE && en && nonZeroReg(reg) && v_gpr(i) === reg
    }).asUInt.orR
  }

  val d_rs1VHaz = vHaz(d_rs1, d_dec.info.rs1en, allowERelease = true.B)
  val d_rs2VHaz = vHaz(d_rs2, d_dec.info.rs2en, allowERelease = true.B)
  val d_rdVHaz = vHaz(d_rd, d_dec.info.rwen, allowERelease = true.B)

  val d_isJal = d_dec.info.fuType === FuType.jmp && d_dec.info.fuOpType === BruOp.jal
  val d_isJalr = d_dec.info.fuType === FuType.jmp && d_dec.info.fuOpType === BruOp.jalr
  val d_jrBlocked = d_isJalr && (
    d_rs1EHit || d_rs1WHit || vHaz(d_rs1, d_dec.info.rs1en, allowERelease = false.B)
  )

  val d_needVSlot = d_dec.info.isLsu
  val d_noVSlot = d_needVSlot && !vAllocator.io.allocate.valid
  val d_wawHaz = d_dec.info.rwen && d_rdVHaz
  val d_rawHaz = d_rs1VHaz || d_rs2VHaz
  val d_storeQueueFull = sq(0).valid && sq(1).valid
  val d_sqStall = d_dec.info.isStore && d_storeQueueFull
  val d_refillStall = r_state =/= rIdle
  val d_hazardStall = d_rawHaz || d_wawHaz || d_jrBlocked || d_noVSlot || d_sqStall || d_refillStall

  io.inst.ready := !d_hazardStall
  val d_fire = io.inst.valid && io.inst.ready
  val d_flushed = d_fire && (e_redirect.valid || w_redirect.valid)
  val d_vslot = vAllocator.io.allocate.bits
  vAllocator.io.allocate.ready := d_fire && d_needVSlot

  val d_uop = Wire(new BackendUop)
  d_uop.pc := d_pc
  d_uop.inst := d_inst
  d_uop.info := d_dec.info
  d_uop.rs1 := d_rs1
  d_uop.rs2 := d_rs2
  d_uop.rd := d_rd
  d_uop.imm := d_dec.imm
  d_uop.src1 := d_src1
  d_uop.src2 := d_src2
  d_uop.storeData := d_rs2Data
  d_uop.lsuAddr := d_lsuAddr
  d_uop.csrAddr := d_dec.csrAddr
  d_uop.exceptionCode := d_dec.exceptionCode
  d_uop.flushed := d_flushed
  d_uop.isUncached := d_isUncached
  d_uop.vslotValid := d_needVSlot
  d_uop.vslot := d_vslot

  d_redirect.valid := d_fire && !d_flushed && (d_isJal || d_isJalr)
  d_redirect.bits := Mux(
    d_isJal,
    (d_pc + d_dec.imm)(p.addrBits - 1, 0),
    ((d_rs1Rf + d_dec.imm) & ~1.U(p.addrBits.W))(p.addrBits - 1, 0)
  )

  val d_tagSet = dcacheGetSet(d_lsuAddr)
  val d_dataIdx = dcacheDataIdx(d_lsuAddr)
  val d_readTag = d_fire && d_dec.info.isLsu && !d_isUncached
  val d_readData = d_fire && d_isLoad && !d_isUncached
  for (i <- 0 until p.dcacheWays) {
    dcTags(i).io.r.req.valid := d_readTag
    dcTags(i).io.r.req.bits.apply(d_tagSet)
    dcDatas(i).io.r.req.valid := d_readData
    dcDatas(i).io.r.req.bits.apply(d_dataIdx)
  }

  //==========================================================================
  // E 级：执行、分支判断、数据缓存命中判断和存储队列前递
  //==========================================================================

  val e_killedByW = e_valid && w_redirect.valid
  val e_canCommit = e_valid && !e_uop.flushed && !e_killedByW

  val e_src1 = Mux(w_rfen && e_uop.info.rs1en && e_uop.rs1 === w_uop.rd && nonZeroReg(e_uop.rs1), w_rfdata, e_uop.src1)
  val e_src2 = Mux(w_rfen && e_uop.info.rs2en && e_uop.rs2 === w_uop.rd && nonZeroReg(e_uop.rs2), w_rfdata, e_uop.src2)
  val e_storeData = Mux(w_rfen && e_uop.info.rs2en && e_uop.rs2 === w_uop.rd && nonZeroReg(e_uop.rs2), w_rfdata, e_uop.storeData)

  alu.io.src1 := e_src1
  alu.io.src2 := e_src2
  alu.io.op := e_uop.info.fuOpType(AluOp.width - 1, 0)

  val e_bruOp = e_uop.info.fuOpType
  val e_branchTaken = WireDefault(false.B)
  switch(e_bruOp) {
    is(BruOp.beq)  { e_branchTaken := e_src1 === e_src2 }
    is(BruOp.bne)  { e_branchTaken := e_src1 =/= e_src2 }
    is(BruOp.blt)  { e_branchTaken := e_src1.asSInt < e_src2.asSInt }
    is(BruOp.bge)  { e_branchTaken := e_src1.asSInt >= e_src2.asSInt }
    is(BruOp.bltu) { e_branchTaken := e_src1 < e_src2 }
    is(BruOp.bgeu) { e_branchTaken := e_src1 >= e_src2 }
  }
  e_redirect.valid := e_canCommit && e_uop.info.fuType === FuType.bru && e_branchTaken
  e_redirect.bits := (e_uop.pc + e_uop.imm)(p.addrBits - 1, 0)

  val e_cacheTag = dcacheGetTag(e_uop.lsuAddr)
  val e_cacheSet = dcacheGetSet(e_uop.lsuAddr)
  val e_cacheWord = dcacheGetWord(e_uop.lsuAddr)
  val e_isCmo = e_uop.info.isLsu && (
    e_uop.info.fuOpType === LSUOp.cboInval ||
      e_uop.info.fuOpType === LSUOp.cboClean ||
      e_uop.info.fuOpType === LSUOp.cboFlush
  )
  val e_hits = VecInit((0 until p.dcacheWays).map { i =>
    dcValid(i)(e_cacheSet) && dcTags(i).io.r.resp.data(0).tag === e_cacheTag
  })
  val e_hit = e_canCommit && e_uop.info.isLsu && !e_uop.isUncached && e_hits.asUInt.orR
  val e_hitWay = Mux(e_hits(0), 0.U, 1.U)
  val e_cacheData = Mux(e_hits(0), dcDatas(0).io.r.resp.data(0), dcDatas(1).io.r.resp.data(0))

  val e_storeMask = storeMask(e_uop.info.fuOpType, e_uop.lsuAddr)
  val e_storeShifted = shiftStoreData(e_uop.info.fuOpType, e_uop.lsuAddr, e_storeData)
  val e_storeCommitNow = w_valid && !w_uop.flushed && w_uop.info.isStore && w_cacheHit

  val e_forwardFromW = e_storeCommitNow && sameWordAddr(w_storeEntry.addr, e_uop.lsuAddr)
  val e_forwardFromSq0 = sq(0).valid && sameWordAddr(sq(0).addr, e_uop.lsuAddr)
  val e_forwardFromSq1 = sq(1).valid && sameWordAddr(sq(1).addr, e_uop.lsuAddr)
  val e_loadForward0 = Mux(e_forwardFromW, mergeByMask(e_cacheData, w_storeEntry.data, w_storeEntry.mask), e_cacheData)
  val e_loadForward1 = Mux(e_forwardFromSq0, mergeByMask(e_loadForward0, sq(0).data, sq(0).mask), e_loadForward0)
  val e_loadForward2 = Mux(e_forwardFromSq1, mergeByMask(e_loadForward1, sq(1).data, sq(1).mask), e_loadForward1)
  val e_loadData = loadExtend(e_uop.info.fuOpType, e_uop.lsuAddr, e_loadForward2)

  val e_storeEntry = Wire(new StoreQueueEntry)
  e_storeEntry.valid := e_canCommit && e_uop.info.isStore && e_hit
  e_storeEntry.way := e_hitWay
  e_storeEntry.set := e_cacheSet
  e_storeEntry.word := e_cacheWord
  e_storeEntry.addr := e_uop.lsuAddr
  e_storeEntry.mask := e_storeMask
  e_storeEntry.data := e_storeShifted

  val e_cacheMiss = e_canCommit && e_uop.info.isLsu && !e_uop.isUncached && !e_hit && !e_isCmo
  val e_needsLongWriteback = e_canCommit && e_uop.info.isLsu && e_uop.isUncached
  e_forwardValid := e_canCommit && e_uop.info.rwen && nonZeroReg(e_uop.rd) && !e_cacheMiss && !e_needsLongWriteback
  e_forwardData := Mux(e_uop.info.isLsu, e_loadData, Mux(e_uop.info.fuType === FuType.jmp, e_uop.pc + 4.U, alu.io.out))
  e_release0 := e_uop.vslotValid && e_valid && (e_uop.flushed || e_killedByW || (e_uop.info.isLsu && !e_needsLongWriteback))
  e_release0Slot := e_uop.vslot

  //==========================================================================
  // W 级：提交、写回、重定向和长流水控制
  //==========================================================================

  val w_canCommit = w_valid && !w_uop.flushed
  val w_isLsu = w_uop.info.isLsu
  val w_isLoad = w_isLsu && !w_uop.info.isStore && !w_isCmo
  val w_writesGpr = w_canCommit && w_uop.info.rwen && nonZeroReg(w_uop.rd) && !w_cacheMiss
  val w_commitValid = w_canCommit && !w_cacheMiss
  w_rfdata := Mux(w_isLoad, w_loadData, Mux(w_uop.info.fuType === FuType.jmp, w_uop.pc + 4.U, w_result))
  w_rfen := w_writesGpr

  w_redirect.valid := w_canCommit && (w_cacheMiss || w_isCmo || w_uop.info.flushPipe)
  w_redirect.bits := Mux(w_cacheMiss, w_uop.pc, (w_uop.pc + 4.U)(p.addrBits - 1, 0))

  io.ifencei := w_canCommit && w_uop.info.flushPipe && !w_cacheMiss

  io.commit.valid := w_commitValid
  io.commit.bits.pc := w_uop.pc
  io.commit.bits.inst := w_uop.inst
  io.commit.bits.jump := w_redirect.valid
  io.commit.bits.target := w_redirect.bits

  io.diffCommitValid := w_commitValid
  io.diffCommitPc := w_uop.pc
  io.diffCommitInst := w_uop.inst
  io.diffCommitRfWen := w_rfen
  io.diffCommitRd := w_uop.rd
  io.diffCommitIsLoad := w_isLoad
  io.diffCommitIsStore := w_uop.info.isStore

  val w_storeCommit = w_canCommit && w_uop.info.isStore && w_cacheHit && !w_cacheMiss

  val diffRf = Wire(Vec(32, UInt(p.xlen.W)))
  for (i <- 0 until 32) {
    val wHit = w_rfen && w_uop.rd === i.U
    val vHit = v_rfen && v_rf === i.U
    diffRf(i) := Mux(i.U === 0.U, 0.U, Mux(wHit, w_rfdata, Mux(vHit, v_rfdata, rf(i))))
  }
  io.diffRf := diffRf

  when(w_rfen) {
    rf(w_uop.rd) := w_rfdata
  }
  when(v_rfen && nonZeroReg(v_rf)) {
    rf(v_rf) := v_rfdata
  }
  rf(0) := 0.U

  //==========================================================================
  // 存储队列：提交入队、同地址掩码合并和空闲 SRAM 写入
  //==========================================================================

  val sqHasEntry = sq(0).valid || sq(1).valid
  val sqDrainSel = Mux(sq(0).valid, 0.U(1.W), 1.U(1.W))
  val sqDrainEntry = Mux(sqDrainSel === 0.U, sq(0), sq(1))
  val sqDrain = sqHasEntry && r_state === rIdle && !d_readData

  when(sqDrain) {
    for (i <- 0 until p.dcacheWays) {
      when(sqDrainEntry.way === i.U) {
        dc_data_w_valid(i) := true.B
        dc_data_w_idx(i) := sqDrainEntry.set ## sqDrainEntry.word
        dc_data_w_data(i) := sqDrainEntry.data
        dc_data_w_mask(i) := byteMask(sqDrainEntry.mask)
      }
    }
  }

  val sqNextBase = WireDefault(sq)
  when(sqDrain) {
    sqNextBase(sqDrainSel).valid := false.B
  }

  val sqAfterClear = Wire(Vec(2, new StoreQueueEntry))
  for (i <- 0 until 2) {
    val nextMask = sqNextBase(i).mask & ~Mux(w_storeCommit && sameWordAddr(sqNextBase(i).addr, w_storeEntry.addr), w_storeEntry.mask, 0.U(bytesPerWord.W))
    sqAfterClear(i) := sqNextBase(i)
    sqAfterClear(i).mask := nextMask
    when(sqNextBase(i).valid && nextMask === 0.U) {
      sqAfterClear(i).valid := false.B
    }
  }

  val sqFinal = WireDefault(sqAfterClear)
  when(w_storeCommit) {
    when(!sqAfterClear(0).valid) {
      sqFinal(0) := w_storeEntry
      sqFinal(0).valid := true.B
    }.elsewhen(!sqAfterClear(1).valid) {
      sqFinal(1) := w_storeEntry
      sqFinal(1).valid := true.B
    }
  }
  sq := sqFinal

  //==========================================================================
  // 重填和 CMO：阻塞后端并接管数据缓存
  //==========================================================================

  val w_startRefill = w_canCommit && w_cacheMiss && r_state === rIdle
  val w_startCmo = w_canCommit && w_isCmo && r_state === rIdle

  io.bus.a.valid := r_state === rRefill && r_aValid
  io.bus.a.bits.op := ReadOnly
  io.bus.a.bits.id := 1.U
  io.bus.a.bits.size := log2Ceil(p.dcacheLineBytes).U
  io.bus.a.bits.address := r_addr
  io.bus.a.bits.mask := Fill(busBytes, true.B)
  io.bus.a.bits.data := 0.U
  io.bus.d.ready := r_state === rRefill

  when(w_startRefill) {
    r_state := rRefill
    r_aValid := true.B
    r_way := nextReplaceWay
    r_set := dcacheGetSet(w_uop.lsuAddr)
    r_tag := dcacheGetTag(w_uop.lsuAddr)
    r_addr := lineBase(w_uop.lsuAddr)
    r_cnt := 0.U
    nextReplaceWay := nextReplaceWay + 1.U
  }.elsewhen(w_startCmo) {
    r_state := rCmo
    r_way := w_hitWay
    r_set := dcacheGetSet(w_uop.lsuAddr)
    r_tag := dcacheGetTag(w_uop.lsuAddr)
    r_addr := lineBase(w_uop.lsuAddr)
    r_cmoHit := w_cacheHit
    r_cmoOp := w_uop.info.fuOpType
  }

  when(io.bus.a.fire) {
    r_aValid := false.B
  }

  when(r_state === rRefill && io.bus.d.fire) {
    val refillWord = r_cnt(beatCntBits - 1, 1)
    val refillIdx = r_set ## refillWord
    when(r_cnt(0) === 0.U) {
      r_half := io.bus.d.bits.data
    }.otherwise {
      for (i <- 0 until p.dcacheWays) {
        when(r_way === i.U) {
          dc_data_w_valid(i) := true.B
          dc_data_w_idx(i) := refillIdx
          dc_data_w_data(i) := Cat(io.bus.d.bits.data, r_half)
          dc_data_w_mask(i) := Fill(p.xlen, true.B)
        }
      }
    }

    r_cnt := r_cnt + 1.U
    when(r_cnt === (beatsPerLine - 1).U) {
      for (i <- 0 until p.dcacheWays) {
        when(r_way === i.U) {
          val tagData = Wire(new DCacheTag)
          tagData.tag := r_tag
          tagData.dirty := false.B
          dc_tag_w_valid(i) := true.B
          dc_tag_w_set(i) := r_set
          dc_tag_w_data(i) := tagData
          dcValid(i)(r_set) := true.B
        }
      }
      r_state := rIdle
    }
  }

  when(r_state === rCmo) {
    when(r_cmoHit) {
      when(r_cmoOp === LSUOp.cboInval || r_cmoOp === LSUOp.cboFlush) {
        dcValid(r_way)(r_set) := false.B
      }.otherwise {
        val tagData = Wire(new DCacheTag)
        tagData.tag := r_tag
        tagData.dirty := false.B
        for (i <- 0 until p.dcacheWays) {
          when(r_way === i.U) {
            dc_tag_w_valid(i) := true.B
            dc_tag_w_set(i) := r_set
            dc_tag_w_data(i) := tagData
          }
        }
      }
    }
    r_state := rIdle
  }

  //==========================================================================
  // 流水寄存器和记分牌状态更新
  //==========================================================================

  when(e_release0 && vAllocator.io.release(0).ready) {
    v_valid(e_release0Slot) := false.B
  }
  when(v_release1 && vAllocator.io.release(1).ready) {
    v_valid(v_release1Slot) := false.B
  }
  when(d_fire && d_needVSlot) {
    v_valid(d_vslot) := true.B
    v_gpr(d_vslot) := d_rd
  }

  w_valid := e_valid && !w_redirect.valid
  w_uop := e_uop
  w_result := alu.io.out
  w_loadData := e_loadData
  w_storeEntry := e_storeEntry
  w_cacheHit := e_hit
  w_hitWay := e_hitWay
  w_cacheMiss := e_cacheMiss
  w_isCmo := e_isCmo

  when(d_fire) {
    e_valid := true.B
    e_uop := d_uop
  }.otherwise {
    e_valid := false.B
    e_uop := 0.U.asTypeOf(new BackendUop)
  }
}
