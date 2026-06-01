package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.CoreParams
import wiredrv.bus._
import wiredrv.bus.BusOp.{ReadOnly, StoreOnly}
import wiredrv.frontend.FetchPacket
import xs.utils.mbist.MbistPipeline
import xs.utils.sram.SRAMTemplate

class BackendLsuDRequest(implicit p: CoreParams) extends Bundle {
  val addr = UInt(p.addrBits.W)
  val readTag = Bool()
  val readData = Bool()
}

class BackendLsuERequest(implicit p: CoreParams) extends Bundle {
  val canSideEffect = Bool()
  val uop = new BackendUop
  val addr = UInt(p.addrBits.W)
  val storeData = UInt(p.xlen.W)
  val wStoreForwardValid = Bool()
  val wStoreForwardEntry = new StoreQueueEntry
}

class BackendLsuEResponse(implicit p: CoreParams) extends Bundle {
  val hit = Bool()
  val lookupHit = Bool()
  val lookupWay = UInt(log2Ceil(p.dcacheWays).W)
  val hitWay = UInt(log2Ceil(p.dcacheWays).W)
  val replaceWay = UInt(log2Ceil(p.dcacheWays).W)
  val cacheWord = UInt(p.xlen.W)
  val loadData = UInt(p.xlen.W)
  val storeEntry = new StoreQueueEntry
  val miss = Bool()
  val isCmo = Bool()
  val victimTag = new DCTag
  val victimValid = Bool()
  val releaseVslot = Bool()
  val releaseSlot = UInt(p.longLatencySlotsBits.W)
  val dcacheLoadHit = Bool()
  val dcacheStoreHit = Bool()
}

class BackendLsuWRequest(implicit p: CoreParams) extends Bundle {
  val canCommit = Bool()
  val trap = Bool()
  val uop = new BackendUop
  val lsuAddr = UInt(p.addrBits.W)
  val isAmo = Bool()
  val isLoad = Bool()
  val isStore = Bool()
  val isUncached = Bool()
  val lsuMisaligned = Bool()
  val cachedMisaligned = Bool()
  val lsuBypass = Bool()
  val hit = Bool()
  val lookupHit = Bool()
  val lookupWay = UInt(log2Ceil(p.dcacheWays).W)
  val cacheWord = UInt(p.xlen.W)
  val hitWay = UInt(log2Ceil(p.dcacheWays).W)
  val replaceWay = UInt(log2Ceil(p.dcacheWays).W)
  val miss = Bool()
  val isCmo = Bool()
  val victimTag = new DCTag
  val victimValid = Bool()
  val storeEntry = new StoreQueueEntry
  val longWbValid = Bool()
}

class BackendLsuSlowCommit(implicit p: CoreParams) extends Bundle {
  val valid = Bool()
  val packet = new FetchPacket
  val rfWen = Bool()
  val rd = UInt(5.W)
  val rfData = UInt(p.xlen.W)
  val isLoad = Bool()
  val isStore = Bool()
  val releaseVslot = Bool()
  val releaseSlot = UInt(p.longLatencySlotsBits.W)
  val exit = Bool()
}

class BackendLsuPerfEvents extends Bundle {
  val dcacheMiss = Bool()
  val dcacheMissLoad = Bool()
  val dcacheMissStore = Bool()
  val dcacheVictimWriteback = Bool()
  val dcacheRefillCycle = Bool()
  val dcacheRefillRespWait = Bool()
  val dcacheVictimWritebackCycle = Bool()
  val cmoStart = Bool()
  val cmoCycle = Bool()
  val storeQueueEnq = UInt(2.W)
  val storeQueueDrain = Bool()
  val storeQueueFullCycle = Bool()
  val storeQueueOneEntryCycle = Bool()
  val uncachedAccess = Bool()
  val uncachedCycle = Bool()
  val dBusAWait = Bool()
  val dBusDWait = Bool()
  val dBusDWaitRefill = Bool()
  val dBusDWaitUncached = Bool()
}

class BackendLsu(implicit p: CoreParams) extends Module {
  require(p.xlen == 64, "BackendLsu currently implements RV64 data path")
  require(p.innerBusWidth == 32, "BackendLsu refill path expects the 32-bit inner bus")
  require(p.outerBusWidth == 64, "BackendLsu atomic path expects the 64-bit outer bus")
  require(p.dcacheWays == 2, "BackendLsu currently assumes a 2-way data cache")

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

  val io = IO(new Bundle {
    val d = Input(new BackendLsuDRequest)
    val e = Input(new BackendLsuERequest)
    val eResp = Output(new BackendLsuEResponse)
    val w = Input(new BackendLsuWRequest)

    val bus = new BusBundle(p.innerBusWidth)
    val atomicBus = new BusBundle(p.outerBusWidth)

    val sqHasEntry = Output(Bool())
    val cacheBusy = Output(Bool())
    val uncachedBusy = Output(Bool())
    val misalignedBusy = Output(Bool())
    val busAvailable = Output(Bool())
    val startRefill = Output(Bool())
    val startCmo = Output(Bool())
    val startCachedMisaligned = Output(Bool())
    val startUncached = Output(Bool())
    val storeCommit = Output(Bool())

    val slow = Output(new BackendLsuSlowCommit)
    val simUart = Output(Valid(UInt(8.W)))
    val perf = Output(new BackendLsuPerfEvents)
  })

  val dc_valid = RegInit(0.U.asTypeOf(Vec(p.dcacheWays, Vec(p.dcacheSets, Bool()))))
  val dc_tags = Seq.fill(p.dcacheWays)(
    Module(new SRAMTemplate(new DCTag, p.dcacheSets, way = 1, singlePort = true, hasMbist = true))
  )
  val dc_datas = Seq.fill(p.dcacheWays)(
    Module(new SRAMTemplate(UInt(p.xlen.W), dataSetCount, way = 1, singlePort = true, useBitmask = true, hasMbist = true))
  )
  private val dcMbistPipeline = MbistPipeline.PlaceMbistPipeline(1, "MbistPipelineWiredRVBackendLsu", place = true)
  val store_queue = RegInit(VecInit(Seq.fill(2)(0.U.asTypeOf(new StoreQueueEntry))))
  val sq_has_entry = store_queue(0).valid || store_queue(1).valid
  val next_replace_way = RegInit(0.U(log2Ceil(p.dcacheWays).W))

  val r_state = RegInit(DCacheRefillState.idle)
  val r_a_valid = RegInit(false.B)
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
  val u_is_amo = RegInit(false.B)
  val u_size = RegInit(0.U(4.W))
  val u_beats = RegInit(1.U(2.W))
  val u_beat = RegInit(0.U(2.W))
  val u_mask = RegInit(0.U(bytesPerWord.W))
  val u_data = RegInit(0.U(p.xlen.W))
  val u_compare = RegInit(0.U(p.xlen.W))
  val u_load_word = RegInit(0.U(p.xlen.W))
  val u_byte_mode = RegInit(false.B)
  val u_byte_idx = RegInit(0.U(byteIdxBits.W))
  val u_byte_count = RegInit(1.U(byteCountBits.W))

  val m_state = RegInit(MisalignedState.idle)
  val m_pc = RegInit(0.U(p.addrBits.W))
  val m_inst = RegInit(0.U(32.W))
  val m_addr = RegInit(0.U(p.addrBits.W))
  val m_op = RegInit(LSUOp.none)
  val m_rd = RegInit(0.U(5.W))
  val m_vslot = RegInit(0.U(p.longLatencySlotsBits.W))
  val m_vslot_valid = RegInit(false.B)
  val m_is_load = RegInit(false.B)
  val m_is_store = RegInit(false.B)
  val m_byte_count = RegInit(1.U(byteCountBits.W))
  val m_byte_idx = RegInit(0.U(byteCountBits.W))
  val m_store_data = RegInit(0.U(p.xlen.W))
  val m_load_word = RegInit(0.U(p.xlen.W))
  val m_word0_addr = RegInit(0.U(p.addrBits.W))
  val m_word1_addr = RegInit(0.U(p.addrBits.W))
  val m_word0_hit = RegInit(false.B)
  val m_word1_hit = RegInit(false.B)
  val m_word0_way = RegInit(0.U(log2Ceil(p.dcacheWays).W))
  val m_word1_way = RegInit(0.U(log2Ceil(p.dcacheWays).W))
  val m_word0_data = RegInit(0.U(p.xlen.W))
  val m_word1_data = RegInit(0.U(p.xlen.W))
  val m_need_second = RegInit(false.B)
  val m_bus_a_valid = RegInit(false.B)

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

  io.bus.a.valid := false.B
  io.bus.a.bits := 0.U.asTypeOf(io.bus.a.bits)
  io.bus.d.ready := false.B
  io.atomicBus.a.valid := false.B
  io.atomicBus.a.bits := 0.U.asTypeOf(io.atomicBus.a.bits)
  io.atomicBus.d.ready := false.B
  io.slow := 0.U.asTypeOf(new BackendLsuSlowCommit)
  io.simUart.valid := false.B
  io.simUart.bits := 0.U
  io.perf := 0.U.asTypeOf(new BackendLsuPerfEvents)

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

  val d_dcache_set = p.dcacheGetSet(io.d.addr)
  val d_dcache_idx = dcacheDataIdx(io.d.addr)
  for (i <- 0 until p.dcacheWays) {
    dc_tags(i).io.r.req.valid := io.d.readTag
    dc_tags(i).io.r.req.bits.apply(d_dcache_set)
    dc_datas(i).io.r.req.valid := io.d.readData
    dc_datas(i).io.r.req.bits.apply(d_dcache_idx)
  }

  val e_uop = io.e.uop
  val e_addr = io.e.addr
  val e_set = p.dcacheGetSet(e_addr)
  val e_tag = p.dcacheGetTag(e_addr)
  val e_word = p.dcacheGetWord(e_addr)
  val e_idx = dcacheDataIdx(e_set, e_word)
  val e_is_cmo = e_uop.info.isLsu && isCmoOp(e_uop.info.fuOpType)
  val e_is_amo = e_uop.info.isLsu && LSUOp.isAmo(e_uop.info.fuOpType)
  val e_is_uncached = e_uop.info.isLsu && !e_is_cmo && (e_is_amo || inRegions(e_addr, p.uncachedRegions))
  val e_is_load = e_uop.info.isLsu && (!e_uop.info.isStore || e_is_amo) && !e_is_cmo
  val e_is_store = e_uop.info.isStore
  val e_load_misaligned = e_is_load && loadNeedsAlign(e_uop.info.fuOpType, e_addr)
  val e_store_misaligned = e_is_store && storeNeedsAlign(e_uop.info.fuOpType, e_addr)
  val e_lsu_misaligned = e_load_misaligned || e_store_misaligned
  val e_lsu_bypass = e_is_uncached || e_lsu_misaligned
  val e_exception = e_uop.exceptionValid || e_uop.info.illegal
  val e_way_hits = VecInit((0 until p.dcacheWays).map { i =>
    dc_valid(i)(e_set) && dc_tags(i).io.r.resp.data(0).tag === e_tag
  })
  val e_raw_hit = e_way_hits.asUInt.orR
  val e_cache_lookup = io.e.canSideEffect && e_uop.info.isLsu && !e_is_uncached && !e_is_cmo && !e_exception
  val e_lookup_hit = e_cache_lookup && e_raw_hit
  val e_hit = e_cache_lookup && !e_lsu_misaligned && e_raw_hit
  val e_hit_way = PriorityEncoder(e_way_hits)
  val e_replace_way = next_replace_way
  val e_hit_tag = Mux(e_hit_way === 0.U, dc_tags(0).io.r.resp.data(0), dc_tags(1).io.r.resp.data(0))
  val e_victim_tag = Mux(e_replace_way === 0.U, dc_tags(0).io.r.resp.data(0), dc_tags(1).io.r.resp.data(0))
  val e_victim_valid = dc_valid(e_replace_way)(e_set)
  val e_cache_word = Mux(e_way_hits(0), dc_datas(0).io.r.resp.data(0), dc_datas(1).io.r.resp.data(0))

  def storeHitsLoad(s: StoreQueueEntry, loadAddr: UInt): Bool =
    s.valid && sameWordAddr(s.addr, loadAddr)

  val e_store_mask = storeMask(e_uop.info.fuOpType, e_addr)
  val e_store_shifted = storeData(e_uop.info.fuOpType, io.e.storeData, e_addr)
  val after_sq0 = Mux(storeHitsLoad(store_queue(0), e_addr), mergeByMask(e_cache_word, store_queue(0).data, store_queue(0).mask), e_cache_word)
  val after_sq1 = Mux(storeHitsLoad(store_queue(1), e_addr), mergeByMask(after_sq0, store_queue(1).data, store_queue(1).mask), after_sq0)
  val after_w_store = Mux(io.e.wStoreForwardValid, mergeByMask(after_sq1, io.e.wStoreForwardEntry.data, io.e.wStoreForwardEntry.mask), after_sq1)
  val e_load_data = loadExtend(after_w_store, e_addr, e_uop.info.fuOpType)

  val e_store_entry = Wire(new StoreQueueEntry)
  e_store_entry.valid := io.e.canSideEffect && e_is_store && e_hit
  e_store_entry.way := e_hit_way
  e_store_entry.idx := e_idx
  e_store_entry.addr := e_addr
  e_store_entry.mask := e_store_mask
  e_store_entry.data := e_store_shifted
  e_store_entry.set := e_set

  val e_miss = io.e.canSideEffect && e_uop.info.isLsu && !e_lsu_bypass && !e_exception && !e_is_cmo && !e_hit
  val e_cachable_hit_no_long_latency = e_uop.info.isLsu && (e_exception || (!e_lsu_bypass && (e_is_cmo || e_hit)))

  io.eResp.hit := e_hit
  io.eResp.lookupHit := e_lookup_hit
  io.eResp.lookupWay := e_hit_way
  io.eResp.hitWay := e_hit_way
  io.eResp.replaceWay := e_replace_way
  io.eResp.cacheWord := after_w_store
  io.eResp.loadData := e_load_data
  io.eResp.storeEntry := e_store_entry
  io.eResp.miss := e_miss
  io.eResp.isCmo := e_is_cmo
  io.eResp.victimTag := Mux(e_is_cmo, e_hit_tag, e_victim_tag)
  io.eResp.victimValid := Mux(e_is_cmo, e_hit, e_victim_valid)
  io.eResp.releaseVslot := io.e.canSideEffect && e_uop.vslot_valid && e_cachable_hit_no_long_latency
  io.eResp.releaseSlot := e_uop.vslot
  io.eResp.dcacheLoadHit := io.e.canSideEffect && e_is_load && e_hit
  io.eResp.dcacheStoreHit := io.e.canSideEffect && e_is_store && e_hit

  val w_bus_available =
    r_state === DCacheRefillState.idle && u_state === UncachedState.idle && m_state === MisalignedState.idle
  val w_store_commit = io.w.canCommit && !io.w.lsuBypass && io.w.isStore && io.w.hit && !io.w.miss && !io.w.trap
  val w_start_refill = io.w.canCommit && !io.w.trap && !io.w.lsuBypass && io.w.miss && !sq_has_entry &&
    w_bus_available
  val w_start_cmo = io.w.canCommit && !io.w.trap && !io.w.lsuBypass && io.w.isCmo && !sq_has_entry &&
    w_bus_available
  val w_start_cached_misaligned = io.w.canCommit && io.w.cachedMisaligned && !io.w.trap && w_bus_available
  val w_start_uncached = io.w.canCommit && io.w.isUncached && !io.w.trap && w_bus_available

  io.sqHasEntry := sq_has_entry
  io.cacheBusy := (r_state =/= DCacheRefillState.idle) || (m_state =/= MisalignedState.idle)
  io.uncachedBusy := u_state =/= UncachedState.idle
  io.misalignedBusy := m_state =/= MisalignedState.idle
  io.busAvailable := w_bus_available
  io.startRefill := w_start_refill
  io.startCmo := w_start_cmo
  io.startCachedMisaligned := w_start_cached_misaligned
  io.startUncached := w_start_uncached
  io.storeCommit := w_store_commit

  io.perf.dcacheMiss := w_start_refill
  io.perf.dcacheMissLoad := w_start_refill && io.w.isLoad
  io.perf.dcacheMissStore := w_start_refill && io.w.isStore
  io.perf.dcacheVictimWriteback := w_start_refill && io.w.victimValid && io.w.victimTag.dirty
  io.perf.dcacheRefillCycle := r_state =/= DCacheRefillState.idle
  io.perf.dcacheRefillRespWait := r_state === DCacheRefillState.recvD && !io.bus.d.fire
  io.perf.dcacheVictimWritebackCycle := (r_state === DCacheRefillState.cmoRead) || (r_state === DCacheRefillState.cmoWriteback)
  io.perf.cmoStart := w_start_cmo
  io.perf.cmoCycle := r_is_cmo && r_state =/= DCacheRefillState.idle
  io.perf.uncachedAccess := w_start_uncached
  io.perf.uncachedCycle := u_state =/= UncachedState.idle

  val sq_drain_sel = Mux(store_queue(0).valid, 0.U(1.W), 1.U(1.W))
  val sq_drain_entry = Mux(sq_drain_sel === 0.U, store_queue(0), store_queue(1))
  val sq_pipeline_lsu_busy = (io.e.canSideEffect && io.e.uop.info.isLsu) || (io.w.canCommit && io.w.uop.info.isLsu)
  val sq_can_drain =
    r_state === DCacheRefillState.idle && u_state === UncachedState.idle && m_state === MisalignedState.idle &&
      !sq_pipeline_lsu_busy && !io.d.readTag && !io.d.readData &&
      !w_start_refill && !w_start_cmo && !w_start_cached_misaligned && !w_start_uncached
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
      w_store_commit && sq_after_drain(i).valid && sameWordAddr(sq_after_drain(i).addr, io.w.storeEntry.addr),
      io.w.storeEntry.mask,
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
      sq_final(0) := io.w.storeEntry
      sq_final(0).valid := true.B
    }.elsewhen(!sq_after_clear(1).valid) {
      sq_final(1) := io.w.storeEntry
      sq_final(1).valid := true.B
    }.otherwise {
      assert(false.B, "Store Queue overflow")
    }
  }

  val m_store_commit = WireDefault(false.B)
  val m_store_entry0_valid = WireDefault(false.B)
  val m_store_entry1_valid = WireDefault(false.B)
  val m_store_entry0 = WireDefault(0.U.asTypeOf(new StoreQueueEntry))
  val m_store_entry1 = WireDefault(0.U.asTypeOf(new StoreQueueEntry))

  val sq_with_misaligned = WireDefault(sq_final)
  when(m_store_commit) {
    assert(!sq_final(0).valid && !sq_final(1).valid,
      "Misaligned cached store expected an empty Store Queue")
    when(m_store_entry0_valid) {
      sq_with_misaligned(0) := m_store_entry0
      sq_with_misaligned(0).valid := true.B
    }
    when(m_store_entry1_valid) {
      when(m_store_entry0_valid) {
        sq_with_misaligned(1) := m_store_entry1
        sq_with_misaligned(1).valid := true.B
      }.otherwise {
        sq_with_misaligned(0) := m_store_entry1
        sq_with_misaligned(0).valid := true.B
      }
    }
  }
  store_queue := sq_with_misaligned

  io.perf.storeQueueEnq := Mux(w_store_commit, 1.U, 0.U) +
    Mux(m_store_commit, PopCount(Seq(m_store_entry0_valid, m_store_entry1_valid)), 0.U)
  io.perf.storeQueueDrain := sq_drain
  io.perf.storeQueueFullCycle := store_queue(0).valid && store_queue(1).valid
  io.perf.storeQueueOneEntryCycle := (store_queue(0).valid || store_queue(1).valid) && !(store_queue(0).valid && store_queue(1).valid)

  val r_send_write = r_state === DCacheRefillState.cmoWriteback && r_wb_word_valid
  val r_send_read = r_state === DCacheRefillState.sendA
  val r_write_beat_data = Mux(r_wb_beat === 0.U, r_wb_word(31, 0), r_wb_word(63, 32))
  val r_write_beat_addr = r_wb_addr + (r_cnt << wordOffsetBits) + (r_wb_beat << busOffsetBits)
  val u_send = u_state === UncachedState.sendA
  val u_recv = u_state === UncachedState.recvD
  val u_normal_bus_addr = Cat(u_addr(p.addrBits - 1, busOffsetBits), 0.U(busOffsetBits.W)) + (u_beat << busOffsetBits)
  val u_normal_bus_half = u_normal_bus_addr(wordOffsetBits - 1)
  val u_normal_bus_data = Mux(u_normal_bus_half, u_data(63, 32), u_data(31, 0))
  val u_normal_bus_mask = Mux(u_normal_bus_half, u_mask(7, 4), u_mask(3, 0))
  val u_normal_bus_size = Mux(u_size <= busOffsetBits.U, u_size, busOffsetBits.U)
  val u_resp_data = Mux(u_is_amo, io.atomicBus.d.bits.data, Cat(0.U((p.xlen - p.innerBusWidth).W), io.bus.d.bits.data))
  val u_normal_resp_word = Mux(
    u_is_amo,
    u_resp_data,
    Mux(
      u_normal_bus_half,
      Cat(io.bus.d.bits.data, u_load_word(31, 0)),
      Cat(u_load_word(63, 32), io.bus.d.bits.data)
    )
  )
  val u_byte_addr = u_addr + u_byte_idx
  val u_byte_bus_addr = Cat(u_byte_addr(p.addrBits - 1, busOffsetBits), 0.U(busOffsetBits.W))
  val u_byte_lane = u_byte_addr(busOffsetBits - 1, 0)
  val u_store_byte = (u_data >> (u_byte_idx << 3))(7, 0)
  val u_byte_bus_data = (Cat(0.U((p.innerBusWidth - 8).W), u_store_byte) << (u_byte_lane << 3))(p.innerBusWidth - 1, 0)
  val u_byte_bus_mask = (1.U(busBytes.W) << u_byte_lane)(busBytes - 1, 0)
  val u_bus_addr = Mux(u_byte_mode, u_byte_bus_addr, u_normal_bus_addr)
  val u_bus_data = Mux(u_byte_mode, u_byte_bus_data, u_normal_bus_data)
  val u_bus_mask = Mux(u_byte_mode, u_byte_bus_mask, u_normal_bus_mask)
  val u_bus_size = Mux(u_byte_mode, log2Ceil(busBytes).U, u_normal_bus_size)
  val u_last_beat = Mux(u_byte_mode, Cat(0.U(1.W), u_byte_idx) === u_byte_count - 1.U, u_beat === (u_beats - 1.U))
  val u_complete_can_write = !io.w.longWbValid
  val m_send = m_state === MisalignedState.busSend
  val m_recv = m_state === MisalignedState.busRecv
  val m_byte_addr = (m_addr + m_byte_idx)(p.addrBits - 1, 0)
  val m_byte_lane = m_byte_addr(busOffsetBits - 1, 0)
  val m_byte_uses_word1 = m_need_second && sameWordAddr(m_byte_addr, m_word1_addr)
  val m_byte_cache_hit = Mux(m_byte_uses_word1, m_word1_hit, m_word0_hit)
  val m_byte_needs_bus = m_byte_idx < m_byte_count && !m_byte_cache_hit
  val m_bus_addr = Cat(m_byte_addr(p.addrBits - 1, busOffsetBits), 0.U(busOffsetBits.W))
  val m_store_byte = (m_store_data >> (m_byte_idx << 3))(7, 0)
  val m_bus_data = (Cat(0.U((p.innerBusWidth - 8).W), m_store_byte) << (m_byte_lane << 3))(p.innerBusWidth - 1, 0)
  val m_bus_mask = (1.U(busBytes.W) << m_byte_lane)(busBytes - 1, 0)

  val r_uses_bus = r_state =/= DCacheRefillState.idle
  val m_uses_bus = m_send || m_recv
  io.bus.a.valid := Mux(
    r_uses_bus,
    (r_send_read || r_send_write) && r_a_valid,
    Mux(m_uses_bus, m_send && m_bus_a_valid, u_send && !u_is_amo && u_a_valid)
  )
  io.bus.a.bits.op := Mux(
    r_uses_bus,
    Mux(r_send_write, StoreOnly, ReadOnly),
    Mux(m_uses_bus, Mux(m_is_store, StoreOnly, ReadOnly), Mux(u_is_store, StoreOnly, ReadOnly))
  )
  io.bus.a.bits.id := 1.U
  io.bus.a.bits.size := Mux(
    r_uses_bus,
    Mux(r_send_write, log2Ceil(busBytes).U, log2Ceil(p.dcacheLineBytes).U),
    Mux(m_uses_bus, log2Ceil(busBytes).U, u_bus_size)
  )
  io.bus.a.bits.address := Mux(
    r_uses_bus,
    Mux(r_send_write, r_write_beat_addr, r_line_addr),
    Mux(m_uses_bus, m_bus_addr, u_bus_addr)
  )
  io.bus.a.bits.mask := Mux(r_uses_bus, Fill(busBytes, 1.U), Mux(m_uses_bus, m_bus_mask, u_bus_mask))
  io.bus.a.bits.data := Mux(r_uses_bus, r_write_beat_data, Mux(m_uses_bus, m_bus_data, u_bus_data))
  io.bus.a.bits.compare := 0.U
  io.bus.d.ready := Mux(
    r_uses_bus,
    r_state === DCacheRefillState.recvD || r_send_write,
    Mux(m_uses_bus, m_recv, u_recv && !u_is_amo && (!u_last_beat || u_complete_can_write))
  )
  io.atomicBus.a.valid := u_send && u_is_amo && u_a_valid
  io.atomicBus.a.bits.op := LSUOp.amoBusOp(u_op)
  io.atomicBus.a.bits.id := 1.U
  io.atomicBus.a.bits.size := u_size
  io.atomicBus.a.bits.address := u_addr
  io.atomicBus.a.bits.mask := u_mask
  io.atomicBus.a.bits.data := u_data
  io.atomicBus.a.bits.compare := u_compare
  io.atomicBus.d.ready := u_recv && u_is_amo && (!u_last_beat || u_complete_can_write)

  val d_bus_d_expected = Mux(r_uses_bus, r_state === DCacheRefillState.recvD || r_send_write, Mux(m_uses_bus, m_recv, u_recv))
  val d_bus_d_fire = Mux(!r_uses_bus && !m_uses_bus && u_is_amo, io.atomicBus.d.fire, io.bus.d.fire)
  io.perf.dBusAWait := (io.bus.a.valid && !io.bus.a.ready) || (io.atomicBus.a.valid && !io.atomicBus.a.ready)
  io.perf.dBusDWait := d_bus_d_expected && !d_bus_d_fire
  io.perf.dBusDWaitRefill := d_bus_d_expected && !d_bus_d_fire && r_uses_bus
  io.perf.dBusDWaitUncached := d_bus_d_expected && !d_bus_d_fire && !r_uses_bus

  when(w_start_refill) {
    r_line_addr := lineBase(io.w.lsuAddr)
    r_wb_addr := lineBaseFromTag(io.w.victimTag.tag, p.dcacheGetSet(io.w.lsuAddr))
    r_set := p.dcacheGetSet(io.w.lsuAddr)
    r_tag := p.dcacheGetTag(io.w.lsuAddr)
    r_way := io.w.replaceWay
    r_is_cmo := false.B
    r_cmo_hit := false.B
    r_cmo_op := LSUOp.none
    r_cnt := 0.U
    r_wb_beat := 0.U
    r_wb_word_valid := false.B
    r_after_wb_refill := true.B
    next_replace_way := io.w.replaceWay + 1.U
    when(io.w.victimValid && io.w.victimTag.dirty) {
      r_state := DCacheRefillState.cmoRead
      r_a_valid := false.B
    }.otherwise {
      r_state := DCacheRefillState.sendA
      r_a_valid := true.B
    }
  }.elsewhen(w_start_cmo) {
    r_line_addr := lineBase(io.w.lsuAddr)
    r_wb_addr := lineBase(io.w.lsuAddr)
    r_set := p.dcacheGetSet(io.w.lsuAddr)
    r_tag := p.dcacheGetTag(io.w.lsuAddr)
    r_way := io.w.hitWay
    r_is_cmo := true.B
    r_cmo_hit := io.w.hit
    r_cmo_op := io.w.uop.info.fuOpType
    r_cnt := 0.U
    r_wb_beat := 0.U
    r_wb_word_valid := false.B
    r_after_wb_refill := false.B
    when(io.w.hit && io.w.victimTag.dirty && (io.w.uop.info.fuOpType === LSUOp.cboClean || io.w.uop.info.fuOpType === LSUOp.cboFlush)) {
      r_state := DCacheRefillState.cmoRead
      r_a_valid := false.B
    }.otherwise {
      r_state := DCacheRefillState.cmoUpdate
      r_a_valid := false.B
    }
  }

  when(r_uses_bus && io.bus.a.fire) {
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

  def mAccessByteAddr(i: Int): UInt =
    (m_addr + i.U)(p.addrBits - 1, 0)

  def mLoadHitPart(i: Int): UInt = {
    val addr = mAccessByteAddr(i)
    val useWord1 = m_need_second && sameWordAddr(addr, m_word1_addr)
    val hit = Mux(useWord1, m_word1_hit, m_word0_hit)
    val word = Mux(useWord1, m_word1_data, m_word0_data)
    val byte = (word >> (addr(wordOffsetBits - 1, 0) << 3))(7, 0)
    Mux(i.U < m_byte_count && hit,
      (Cat(0.U((p.xlen - 8).W), byte) << (i.U << 3))(p.xlen - 1, 0),
      0.U(p.xlen.W)
    )
  }

  def mStoreMaskFor(wordAddr: UInt, hit: Bool): UInt =
    (0 until bytesPerWord).map { i =>
      val addr = mAccessByteAddr(i)
      Mux(i.U < m_byte_count && hit && sameWordAddr(addr, wordAddr),
        (1.U(bytesPerWord.W) << addr(wordOffsetBits - 1, 0))(bytesPerWord - 1, 0),
        0.U(bytesPerWord.W)
      )
    }.reduce(_ | _)

  def mStoreDataFor(wordAddr: UInt, hit: Bool): UInt =
    (0 until bytesPerWord).map { i =>
      val addr = mAccessByteAddr(i)
      val byte = (m_store_data >> (i.U << 3))(7, 0)
      Mux(i.U < m_byte_count && hit && sameWordAddr(addr, wordAddr),
        (Cat(0.U((p.xlen - 8).W), byte) << (addr(wordOffsetBits - 1, 0) << 3))(p.xlen - 1, 0),
        0.U(p.xlen.W)
      )
    }.reduce(_ | _)

  val m_hit_load_word = (0 until bytesPerWord).map(mLoadHitPart).reduce(_ | _)
  val m_word0_store_mask = mStoreMaskFor(m_word0_addr, m_word0_hit)
  val m_word1_store_mask = mStoreMaskFor(m_word1_addr, m_word1_hit)
  val m_word0_store_data = mStoreDataFor(m_word0_addr, m_word0_hit)
  val m_word1_store_data = mStoreDataFor(m_word1_addr, m_word1_hit)
  val m_finish = m_state === MisalignedState.busCheck && m_byte_idx >= m_byte_count
  val m_load_result = loadExtend(m_load_word, 0.U(p.addrBits.W), m_op)

  when(w_start_cached_misaligned) {
    val byteCount = lsuByteCount(io.w.uop.info.fuOpType)
    val word0Addr = Cat(io.w.lsuAddr(p.addrBits - 1, wordOffsetBits), 0.U(wordOffsetBits.W))
    val word1Addr = (word0Addr + bytesPerWord.U)(p.addrBits - 1, 0)
    val endOffset = io.w.lsuAddr(wordOffsetBits - 1, 0) +& byteCount
    val needSecond = endOffset > bytesPerWord.U

    m_pc := io.w.uop.pc
    m_inst := io.w.uop.inst
    m_addr := io.w.lsuAddr
    m_op := io.w.uop.info.fuOpType
    m_rd := io.w.uop.rd
    m_vslot := io.w.uop.vslot
    m_vslot_valid := io.w.uop.vslot_valid
    m_is_load := io.w.isLoad
    m_is_store := io.w.isStore
    m_byte_count := byteCount
    m_byte_idx := 0.U
    m_store_data := io.w.uop.store_data
    m_load_word := 0.U
    m_word0_addr := word0Addr
    m_word1_addr := word1Addr
    m_word0_hit := io.w.lookupHit
    m_word1_hit := false.B
    m_word0_way := io.w.lookupWay
    m_word1_way := 0.U
    m_word0_data := io.w.cacheWord
    m_word1_data := 0.U
    m_need_second := needSecond
    m_bus_a_valid := false.B
    m_state := Mux(needSecond, MisalignedState.readSecondReq, MisalignedState.busCheck)
  }

  when(m_state === MisalignedState.readSecondReq) {
    val m_word1_set = p.dcacheGetSet(m_word1_addr)
    val m_word1_idx = dcacheDataIdx(m_word1_addr)
    for (i <- 0 until p.dcacheWays) {
      dc_tags(i).io.r.req.valid := true.B
      dc_tags(i).io.r.req.bits.apply(m_word1_set)
      dc_datas(i).io.r.req.valid := m_is_load
      dc_datas(i).io.r.req.bits.apply(m_word1_idx)
    }
    m_state := MisalignedState.readSecondResp
  }

  when(m_state === MisalignedState.readSecondResp) {
    val m_word1_set = p.dcacheGetSet(m_word1_addr)
    val m_word1_tag = p.dcacheGetTag(m_word1_addr)
    val m_word1_hits = VecInit((0 until p.dcacheWays).map { i =>
      dc_valid(i)(m_word1_set) && dc_tags(i).io.r.resp.data(0).tag === m_word1_tag
    })
    val m_word1_raw_hit = m_word1_hits.asUInt.orR
    val m_word1_hit_way = PriorityEncoder(m_word1_hits)
    m_word1_hit := m_word1_raw_hit
    m_word1_way := m_word1_hit_way
    m_word1_data := Mux(m_word1_hit_way === 0.U, dc_datas(0).io.r.resp.data(0), dc_datas(1).io.r.resp.data(0))
    m_state := MisalignedState.busCheck
  }

  when(m_state === MisalignedState.busCheck) {
    when(m_byte_idx === 0.U && m_is_load) {
      m_load_word := m_hit_load_word
    }

    when(m_finish) {
      m_state := MisalignedState.idle
      m_vslot_valid := false.B

      io.slow.valid := true.B
      io.slow.packet.pc := m_pc
      io.slow.packet.inst := m_inst
      io.slow.packet.instWord := Cat(0.U(32.W), m_inst)
      io.slow.packet.jump := true.B
      io.slow.packet.target := (m_pc + 4.U)(p.addrBits - 1, 0)
      io.slow.rfWen := m_is_load && nonZeroReg(m_rd)
      io.slow.rd := m_rd
      io.slow.rfData := m_load_result
      io.slow.isLoad := m_is_load
      io.slow.isStore := m_is_store
      io.slow.releaseVslot := m_vslot_valid
      io.slow.releaseSlot := m_vslot
      io.slow.exit := m_inst === RV64IInstructions.NEMU_TRAP.value.U

      m_store_commit := m_is_store
      m_store_entry0_valid := m_is_store && m_word0_store_mask =/= 0.U
      m_store_entry1_valid := m_is_store && m_word1_store_mask =/= 0.U
      m_store_entry0.valid := true.B
      m_store_entry0.way := m_word0_way
      m_store_entry0.idx := dcacheDataIdx(m_word0_addr)
      m_store_entry0.addr := m_word0_addr
      m_store_entry0.mask := m_word0_store_mask
      m_store_entry0.data := m_word0_store_data
      m_store_entry0.set := p.dcacheGetSet(m_word0_addr)
      m_store_entry1.valid := true.B
      m_store_entry1.way := m_word1_way
      m_store_entry1.idx := dcacheDataIdx(m_word1_addr)
      m_store_entry1.addr := m_word1_addr
      m_store_entry1.mask := m_word1_store_mask
      m_store_entry1.data := m_word1_store_data
      m_store_entry1.set := p.dcacheGetSet(m_word1_addr)
    }.elsewhen(m_byte_needs_bus) {
      m_state := MisalignedState.busSend
      m_bus_a_valid := true.B
    }.otherwise {
      m_byte_idx := m_byte_idx + 1.U
    }
  }

  when(m_send && io.bus.a.fire) {
    m_bus_a_valid := false.B
    m_state := MisalignedState.busRecv
  }

  when(m_recv && io.bus.d.fire) {
    when(m_is_load) {
      val m_load_byte = (io.bus.d.bits.data >> (m_byte_lane << 3))(7, 0)
      val m_load_byte_shifted = (Cat(0.U((p.xlen - 8).W), m_load_byte) << (m_byte_idx << 3))(p.xlen - 1, 0)
      val m_load_byte_mask = (Fill(8, 1.U(1.W)).asUInt << (m_byte_idx << 3))(p.xlen - 1, 0)
      m_load_word := (m_load_word & ~m_load_byte_mask) | m_load_byte_shifted
    }
    m_byte_idx := m_byte_idx + 1.U
    m_state := MisalignedState.busCheck
  }

  when(w_start_uncached) {
    val uncached_size = lsuSize(io.w.uop.info.fuOpType)
    val byte_mode = io.w.lsuMisaligned
    val simUartAddr = "h80001ff0".U(p.addrBits.W)
    val simUartStoreData = storeData(io.w.uop.info.fuOpType, io.w.uop.store_data, io.w.lsuAddr)

    when(io.w.isStore && io.w.lsuAddr === simUartAddr) {
      io.simUart.valid := true.B
      io.simUart.bits := simUartStoreData(7, 0)
    }

    u_pc := io.w.uop.pc
    u_inst := io.w.uop.inst
    u_addr := io.w.lsuAddr
    u_op := io.w.uop.info.fuOpType
    u_rd := io.w.uop.rd
    u_vslot := io.w.uop.vslot
    u_is_load := io.w.isLoad
    u_is_store := io.w.isStore
    u_is_amo := io.w.isAmo
    u_size := uncached_size
    u_beats := Mux(io.w.isAmo, 1.U, Mux(uncached_size > busOffsetBits.U, refillBeatsPerWord.U(2.W), 1.U(2.W)))
    u_beat := 0.U
    u_byte_mode := byte_mode
    u_byte_idx := 0.U
    u_byte_count := lsuByteCount(io.w.uop.info.fuOpType)
    u_mask := Mux(io.w.isStore || io.w.isAmo, storeMask(io.w.uop.info.fuOpType, io.w.lsuAddr), 0.U)
    u_data := Mux(
      io.w.isAmo,
      amoBusData(io.w.uop.info.fuOpType, io.w.uop.store_data, io.w.lsuAddr),
      Mux(io.w.isStore, Mux(byte_mode, io.w.uop.store_data, storeData(io.w.uop.info.fuOpType, io.w.uop.store_data, io.w.lsuAddr)), 0.U)
    )
    u_compare := Mux(io.w.isAmo, amoCompareData(io.w.uop.info.fuOpType, io.w.uop.rdData, io.w.lsuAddr), 0.U)
    u_load_word := 0.U
    u_state := UncachedState.sendA
    u_a_valid := true.B
  }

  val u_bus_a_fire = Mux(u_is_amo, io.atomicBus.a.fire, io.bus.a.fire)
  val u_bus_d_fire = Mux(u_is_amo, io.atomicBus.d.fire, io.bus.d.fire)

  when(u_send && u_bus_a_fire) {
    u_a_valid := false.B
    u_state := UncachedState.recvD
  }

  when(u_recv && u_bus_d_fire) {
    val u_load_byte = (u_resp_data >> (u_byte_lane << 3))(7, 0)
    val u_load_byte_shifted = (Cat(0.U((p.xlen - 8).W), u_load_byte) << (u_byte_idx << 3))(p.xlen - 1, 0)
    val u_load_byte_mask = (Fill(8, 1.U(1.W)).asUInt << (u_byte_idx << 3))(p.xlen - 1, 0)
    val u_load_word_next = Mux(
      u_byte_mode && u_is_load,
      (u_load_word & ~u_load_byte_mask) | u_load_byte_shifted,
      u_normal_resp_word
    )

    when(u_is_load) {
      u_load_word := u_load_word_next
    }

    when(u_last_beat) {
      u_state := UncachedState.idle
      u_a_valid := false.B
      u_byte_mode := false.B
      u_is_amo := false.B

      val u_resp_op = Mux(LSUOp.isAmoW(u_op), LSUOp.lw, Mux(LSUOp.isAmoD(u_op), LSUOp.ld, u_op))
      val u_writes_rd = u_is_load && nonZeroReg(u_rd)
      val u_rfdata = loadExtend(u_load_word_next, Mux(u_byte_mode, 0.U(p.addrBits.W), u_addr), u_resp_op)

      when(u_byte_mode && u_is_store) {
        dc_valid := 0.U.asTypeOf(dc_valid)
      }

      io.slow.valid := true.B
      io.slow.packet.pc := u_pc
      io.slow.packet.inst := u_inst
      io.slow.packet.instWord := Cat(0.U(32.W), u_inst)
      io.slow.packet.jump := true.B
      io.slow.packet.target := (u_pc + 4.U)(p.addrBits - 1, 0)
      io.slow.rfWen := u_writes_rd
      io.slow.rd := u_rd
      io.slow.rfData := u_rfdata
      io.slow.isLoad := u_is_load
      io.slow.isStore := u_is_store || u_is_amo
      io.slow.releaseVslot := true.B
      io.slow.releaseSlot := u_vslot
      io.slow.exit := u_inst === RV64IInstructions.NEMU_TRAP.value.U
    }.otherwise {
      when(u_byte_mode) {
        u_byte_idx := u_byte_idx + 1.U
      }.otherwise {
        u_beat := u_beat + 1.U
      }
      u_state := UncachedState.sendA
      u_a_valid := true.B
    }
  }
}
