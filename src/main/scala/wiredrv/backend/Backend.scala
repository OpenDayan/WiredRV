package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.CoreParams
import wiredrv.bus._
import wiredrv.bus.BusOp.{ReadOnly, StoreOnly}
import wiredrv.frontend.{BranchUpdate, FetchPacket}
import xs.utils.mbist.MbistPipeline
import xs.utils.sram.SRAMTemplate

class CoreCsrState(implicit p: CoreParams) extends Bundle {
  val privilegeMode = UInt(p.xlen.W)
  val mstatus = UInt(p.xlen.W)
  val sstatus = UInt(p.xlen.W)
  val mepc = UInt(p.xlen.W)
  val sepc = UInt(p.xlen.W)
  val mtval = UInt(p.xlen.W)
  val stval = UInt(p.xlen.W)
  val mtvec = UInt(p.xlen.W)
  val stvec = UInt(p.xlen.W)
  val mcause = UInt(p.xlen.W)
  val scause = UInt(p.xlen.W)
  val satp = UInt(p.xlen.W)
  val mip = UInt(p.xlen.W)
  val mie = UInt(p.xlen.W)
  val mscratch = UInt(p.xlen.W)
  val sscratch = UInt(p.xlen.W)
  val mideleg = UInt(p.xlen.W)
  val medeleg = UInt(p.xlen.W)
}

class BackendUop(implicit p: CoreParams) extends Bundle {
  val pc = UInt(p.addrBits.W)
  val inst = UInt(32.W)
  val instWord = UInt(64.W)
  val predictedJump = Bool()
  val predictedTarget = UInt(p.addrBits.W)
  val info = new DecodeInfo

  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rd = UInt(5.W)
  val imm = UInt(p.xlen.W)
  val csrAddr = UInt(12.W)
  val exceptionValid = Bool()
  val exceptionCode = UInt(16.W)
  val isInterrupt = Bool()

  val src1 = UInt(p.xlen.W)
  val src2 = UInt(p.xlen.W)
  val store_data = UInt(p.xlen.W)
  val rdData = UInt(p.xlen.W)
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

class BackendPerfCounters extends Bundle {
  val cycles = UInt(64.W)
  val commits = UInt(64.W)
  val dStall = UInt(64.W)
  val dRs1VarHaz = UInt(64.W)
  val dRs2VarHaz = UInt(64.W)
  val dRdVarHaz = UInt(64.W)
  val dERawHaz = UInt(64.W)
  val dWRawHaz = UInt(64.W)
  val dLsuBaseNotReady = UInt(64.W)
  val dJrNotReady = UInt(64.W)
  val dNoVslot = UInt(64.W)
  val dDivResourceBusy = UInt(64.W)
  val dCacheBusy = UInt(64.W)
  val dUncachedBusy = UInt(64.W)
  val dStoreQueueWait = UInt(64.W)
  val dInflightStoreWait = UInt(64.W)
  val dFenceWait = UInt(64.W)
  val dMisalignedWait = UInt(64.W)
  val dNemuTrapWait = UInt(64.W)
  val loadCommit = UInt(64.W)
  val storeCommit = UInt(64.W)
  val jumpCommit = UInt(64.W)
  val branchCommit = UInt(64.W)
  val branchRedirect = UInt(64.W)
  val dcacheLoadHit = UInt(64.W)
  val dcacheStoreHit = UInt(64.W)
  val dcacheMiss = UInt(64.W)
  val dcacheMissLoad = UInt(64.W)
  val dcacheMissStore = UInt(64.W)
  val dcacheRefillCycles = UInt(64.W)
  val dcacheRefillRespWait = UInt(64.W)
  val dcacheVictimWriteback = UInt(64.W)
  val dcacheVictimWritebackCycles = UInt(64.W)
  val cmoStart = UInt(64.W)
  val cmoCycles = UInt(64.W)
  val storeQueueEnq = UInt(64.W)
  val storeQueueDrain = UInt(64.W)
  val storeQueueFullCycles = UInt(64.W)
  val storeQueueOneEntryCycles = UInt(64.W)
  val uncachedAccess = UInt(64.W)
  val uncachedCycles = UInt(64.W)
  val dBusAWait = UInt(64.W)
  val dBusDWait = UInt(64.W)
  val dBusDWaitRefill = UInt(64.W)
  val dBusDWaitUncached = UInt(64.W)
  val mulStart = UInt(64.W)
  val divStart = UInt(64.W)
  val divBusyCycles = UInt(64.W)
}

object DCacheRefillState {
  val idle :: sendA :: recvD :: writeMeta :: cmoRead :: cmoWriteback :: cmoUpdate :: Nil = Enum(7)
}

object UncachedState {
  val idle :: sendA :: recvD :: Nil = Enum(3)
}

object MisalignedState {
  val idle :: readSecondReq :: readSecondResp :: busCheck :: busSend :: busRecv :: Nil = Enum(6)
}

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
  private val misaValue = ((BigInt(2) << (p.xlen - 2)) | (BigInt(1) << 8) | (BigInt(1) << 12)).U(p.xlen.W)
  private val mstatusMppMask = (BigInt(3) << 11).U(p.xlen.W)
  private val mstatusMppM = (BigInt(3) << 11).U(p.xlen.W)

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

  private def amoNeedsAlign(op: UInt, addr: UInt): Bool =
    (LSUOp.isAmoW(op) && addr(1, 0).orR) || (LSUOp.isAmoD(op) && addr(2, 0).orR)

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
      LSUOp.amoaddW -> 2.U,
      LSUOp.amoswapW -> 2.U,
      LSUOp.amoxorW -> 2.U,
      LSUOp.amoorW -> 2.U,
      LSUOp.amoandW -> 2.U,
      LSUOp.amominW -> 2.U,
      LSUOp.amomaxW -> 2.U,
      LSUOp.amominuW -> 2.U,
      LSUOp.amomaxuW -> 2.U,
      LSUOp.amocasW -> 2.U,
      LSUOp.ld -> 3.U,
      LSUOp.sd -> 3.U,
      LSUOp.amoaddD -> 3.U,
      LSUOp.amoswapD -> 3.U,
      LSUOp.amoxorD -> 3.U,
      LSUOp.amoorD -> 3.U,
      LSUOp.amoandD -> 3.U,
      LSUOp.amominD -> 3.U,
      LSUOp.amomaxD -> 3.U,
      LSUOp.amominuD -> 3.U,
      LSUOp.amomaxuD -> 3.U,
      LSUOp.amocasD -> 3.U
    ))

  private def lsuByteCount(op: UInt): UInt =
    (1.U(byteCountBits.W) << lsuSize(op))(byteCountBits - 1, 0)

  private def storeMask(op: UInt, addr: UInt): UInt = {
    val off = addr(wordOffsetBits - 1, 0)
    val rawMask = MuxLookup(op, 0.U(bytesPerWord.W))(Seq(
      LSUOp.sb -> (1.U(bytesPerWord.W) << off),
      LSUOp.sh -> (3.U(bytesPerWord.W) << off),
      LSUOp.sw -> ("hf".U(bytesPerWord.W) << off),
      LSUOp.sd -> "hff".U(bytesPerWord.W),
      LSUOp.amoaddW -> ("hf".U(bytesPerWord.W) << off),
      LSUOp.amoswapW -> ("hf".U(bytesPerWord.W) << off),
      LSUOp.amoxorW -> ("hf".U(bytesPerWord.W) << off),
      LSUOp.amoorW -> ("hf".U(bytesPerWord.W) << off),
      LSUOp.amoandW -> ("hf".U(bytesPerWord.W) << off),
      LSUOp.amominW -> ("hf".U(bytesPerWord.W) << off),
      LSUOp.amomaxW -> ("hf".U(bytesPerWord.W) << off),
      LSUOp.amominuW -> ("hf".U(bytesPerWord.W) << off),
      LSUOp.amomaxuW -> ("hf".U(bytesPerWord.W) << off),
      LSUOp.amocasW -> ("hf".U(bytesPerWord.W) << off),
      LSUOp.amoaddD -> "hff".U(bytesPerWord.W),
      LSUOp.amoswapD -> "hff".U(bytesPerWord.W),
      LSUOp.amoxorD -> "hff".U(bytesPerWord.W),
      LSUOp.amoorD -> "hff".U(bytesPerWord.W),
      LSUOp.amoandD -> "hff".U(bytesPerWord.W),
      LSUOp.amominD -> "hff".U(bytesPerWord.W),
      LSUOp.amomaxD -> "hff".U(bytesPerWord.W),
      LSUOp.amominuD -> "hff".U(bytesPerWord.W),
      LSUOp.amomaxuD -> "hff".U(bytesPerWord.W),
      LSUOp.amocasD -> "hff".U(bytesPerWord.W)
    ))
    rawMask(bytesPerWord - 1, 0)
  }

  private def storeData(op: UInt, data: UInt, addr: UInt): UInt = {
    val storeBytes = MuxLookup(op, data)(Seq(
      LSUOp.sb -> Cat(0.U((p.xlen - 8).W), data(7, 0)),
      LSUOp.sh -> Cat(0.U((p.xlen - 16).W), data(15, 0)),
      LSUOp.sw -> Cat(0.U((p.xlen - 32).W), data(31, 0)),
      LSUOp.sd -> data
    ))
    storeBytes << (addr(wordOffsetBits - 1, 0) << 3)
  }

  private def amoOperand(op: UInt, data: UInt): UInt = {
    val wordData = Cat(0.U((p.xlen - 32).W), data(31, 0))
    val sizedData = Mux(LSUOp.isAmoW(op), wordData, data)
    Mux(op === LSUOp.amoandW, Cat(0.U((p.xlen - 32).W), ~data(31, 0)),
      Mux(op === LSUOp.amoandD, ~data, sizedData))
  }

  private def amoBusData(op: UInt, data: UInt, addr: UInt): UInt =
    (amoOperand(op, data) << (addr(wordOffsetBits - 1, 0) << 3))(p.xlen - 1, 0)

  private def amoCompareData(op: UInt, data: UInt, addr: UInt): UInt = {
    val wordData = Cat(0.U((p.xlen - 32).W), data(31, 0))
    val sizedData = Mux(LSUOp.isAmoW(op), wordData, data)
    (sizedData << (addr(wordOffsetBits - 1, 0) << 3))(p.xlen - 1, 0)
  }

  private def csrRead(addr: UInt, cycle: UInt): UInt =
    MuxLookup(addr, 0.U(p.xlen.W))(Seq(
      "h300".U(12.W) -> mstatus,
      "h304".U(12.W) -> mie,
      "h305".U(12.W) -> mtvec,
      "h301".U(12.W) -> misaValue,
      "h340".U(12.W) -> mscratch,
      "h341".U(12.W) -> mepc,
      "h342".U(12.W) -> mcause,
      "h343".U(12.W) -> mtval,
      "h344".U(12.W) -> mipRead,
      "h3a0".U(12.W) -> pmpcfg0,
      "h3b0".U(12.W) -> pmpaddr0,
      "hc01".U(12.W) -> csrTime,
      "hc81".U(12.W) -> Cat(0.U(32.W), csrTime(63, 32)),
      "hb00".U(12.W) -> cycle, // mcycle
      "hb80".U(12.W) -> Cat(0.U(32.W), cycle(63, 32)), // mcycleh, useful for RV32-style code
      "hf11".U(12.W) -> 0.U,
      "hf12".U(12.W) -> 0.U,
      "hf13".U(12.W) -> 0.U,
      "hf14".U(12.W) -> Cat(0.U((p.xlen - p.maxHartIdBits).W), io.hartId)
    ))

  private def csrWriteData(op: UInt, old: UInt, src: UInt): UInt =
    MuxLookup(op, old)(Seq(
      CsrOp.csrrw -> src,
      CsrOp.csrrs -> (old | src),
      CsrOp.csrrc -> (old & ~src)
    ))

  private def legalizeMstatus(data: UInt): UInt =
    data

  private def legalizeCsrWrite(addr: UInt, data: UInt): UInt =
    Mux(addr === "h300".U, legalizeMstatus(data), data)

  private def csrModeledWritable(addr: UInt): Bool =
    addr === "h300".U || addr === "h304".U || addr === "h305".U ||
      addr === "h340".U || addr === "h341".U || addr === "h342".U ||
      addr === "h343".U || addr === "h344".U || addr === "h3a0".U ||
      addr === "h3b0".U

  private def csrWrites(op: UInt, src: UInt): Bool =
    op === CsrOp.csrrw || ((op === CsrOp.csrrs || op === CsrOp.csrrc) && src =/= 0.U)

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
  val cycleCnt = RegInit(0.U(p.xlen.W))
  val perf = RegInit(0.U.asTypeOf(new BackendPerfCounters))
  val mstatus = RegInit(mstatusMppM)
  val mtvec = RegInit(0.U(p.xlen.W))
  val mepc = RegInit(0.U(p.xlen.W))
  val mcause = RegInit(0.U(p.xlen.W))
  val mtval = RegInit(0.U(p.xlen.W))
  val mie = RegInit(0.U(p.xlen.W))
  val mip = RegInit(0.U(p.xlen.W))
  val mscratch = RegInit(0.U(p.xlen.W))
  val pmpcfg0 = RegInit(0.U(p.xlen.W))
  val pmpaddr0 = RegInit(0.U(p.xlen.W))
  val privilegeMode = RegInit(3.U(2.W))
  private val mstatusMieBit = 3
  private val mstatusMpieBit = 7
  private val mstatusMieMask = (BigInt(1) << mstatusMieBit).U(p.xlen.W)
  private val mstatusMpieMask = (BigInt(1) << mstatusMpieBit).U(p.xlen.W)
  private val msipMask = (BigInt(1) << 3).U(p.xlen.W)
  private val mtipMask = (BigInt(1) << 7).U(p.xlen.W)
  private val meipMask = (BigInt(1) << 11).U(p.xlen.W)
  val linePendingMask =
    Mux(io.msip, msipMask, 0.U(p.xlen.W)) |
    Mux(io.mtip, mtipMask, 0.U(p.xlen.W)) |
    Mux(io.meip, meipMask, 0.U(p.xlen.W))
  val mipRead = mip | linePendingMask
  val enabledInterrupts = mie & mipRead
  val csrTime = Mux(io.time.valid, io.time.bits, cycleCnt)
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

  val v_gpr = RegInit(VecInit(Seq.fill(p.longLatencySlots)(0.U(5.W))))
  val div_wb_pending_valid = RegInit(false.B)
  val div_wb_pending = RegInit(0.U.asTypeOf(new MduWriteback))
  val tensorBusy = RegInit(false.B)
  val tensorRd = RegInit(0.U(5.W))
  val tensorHasVslot = RegInit(false.B)
  val tensorVslot = RegInit(0.U(p.longLatencySlotsBits.W))

  val vAllocator = Module(new SBAllocator)
  val alu = Module(new ALU)
  val mulPipe = Module(new MulPipeline)
  val divUnit = Module(new DivUnit)
  val decoder = Module(new Decode)
  val tensorIssueQueue = Module(new Queue(new TensorInst, entries = 1))

  val dc_valid = RegInit(0.U.asTypeOf(Vec(p.dcacheWays, Vec(p.dcacheSets, Bool()))))
  val dc_tags = Seq.fill(p.dcacheWays)(
    Module(new SRAMTemplate(new DCTag, p.dcacheSets, way = 1, singlePort = true, hasMbist = true))
  )
  val dc_datas = Seq.fill(p.dcacheWays)(
    Module(new SRAMTemplate(UInt(p.xlen.W), dataSetCount, way = 1, singlePort = true, useBitmask = true, hasMbist = true))
  )
  private val dcMbistPipeline = MbistPipeline.PlaceMbistPipeline(1, "MbistPipelineWiredRVBackend", place = true)
  val store_queue = RegInit(VecInit(Seq.fill(2)(0.U.asTypeOf(new StoreQueueEntry))))
  val sq_has_entry = store_queue(0).valid || store_queue(1).valid
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
  val simUartValid = RegInit(false.B)
  val simUartBits = Reg(UInt(8.W))

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
  val u_release_vslot = WireDefault(false.B)
  val u_release_slot = WireDefault(0.U(p.longLatencySlotsBits.W))
  val m_release_vslot = WireDefault(false.B)
  val m_release_slot = WireDefault(0.U(p.longLatencySlotsBits.W))
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
  val m_store_commit = WireDefault(false.B)
  val m_store_entry0_valid = WireDefault(false.B)
  val m_store_entry1_valid = WireDefault(false.B)
  val m_store_entry0 = WireDefault(0.U.asTypeOf(new StoreQueueEntry))
  val m_store_entry1 = WireDefault(0.U.asTypeOf(new StoreQueueEntry))

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
  io.branchUpdate.valid := false.B
  io.branchUpdate.bits := 0.U.asTypeOf(new BranchUpdate)
  io.ifencei := false.B

  io.bus.a.valid := false.B
  io.bus.a.bits := 0.U.asTypeOf(io.bus.a.bits)
  io.bus.d.ready := false.B
  io.atomicBus.a.valid := false.B
  io.atomicBus.a.bits := 0.U.asTypeOf(io.atomicBus.a.bits)
  io.atomicBus.d.ready := false.B
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
  io.diffCsr := 0.U.asTypeOf(new CoreCsrState)
  io.diffCsr.privilegeMode := Cat(0.U((p.xlen - 2).W), privilegeMode)
  io.diffCsr.mstatus := mstatus
  io.diffCsr.mepc := mepc
  io.diffCsr.mtval := mtval
  io.diffCsr.mtvec := mtvec
  io.diffCsr.mcause := mcause
  io.diffCsr.mip := mipRead
  io.diffCsr.mie := mie
  io.diffCsr.mscratch := mscratch
  io.diffTrapValid := false.B
  io.diffTrapIsInterrupt := false.B
  io.diffTrapCode := 0.U
  io.diffTrapPc := 0.U
  io.diffTrapInst := 0.U
  io.diffExit := false.B
  simUartValid := false.B
  io.simUart.valid := simUartValid
  io.simUart.bits := simUartBits

  vAllocator.io.allocate.ready := false.B
  val release0_normal_valid = e_release_vslot || u_release_vslot || m_release_vslot
  val release0_pending_valid = cancel_release_pending_valid
  val release0_cancel_direct = w_cancel_release_vslot && !release0_normal_valid && !release0_pending_valid
  vAllocator.io.release(0).valid := release0_normal_valid || release0_pending_valid || release0_cancel_direct
  vAllocator.io.release(0).bits := Mux(
    u_release_vslot,
    u_release_slot,
    Mux(m_release_vslot, m_release_slot,
      Mux(e_release_vslot, e_release_slot, Mux(release0_pending_valid, cancel_release_pending_slot, w_cancel_release_slot)))
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
  val d_cache_lookup = d_dec.info.isLsu && !d_is_uncached && !d_is_cmo
  val d_cache_access = d_cache_lookup && !d_lsu_misaligned
  val d_is_mext_long_latency = d_dec.info.fuType === FuType.mdu
  val d_is_div = d_is_mext_long_latency && MduCalc.isDivOp(d_dec.info.fuOpType)
  val d_need_vslot_raw = d_is_uncached || d_lsu_misaligned ||
    ((d_is_mext_long_latency || d_is_tensor) && d_writes_rd)
  val e_is_div = e_valid && !e_uop.flushed && e_uop.info.fuType === FuType.mdu && MduCalc.isDivOp(e_uop.info.fuOpType)
  val w_is_div_pipe = w_valid && !w_uop.flushed && w_uop.info.fuType === FuType.mdu && MduCalc.isDivOp(w_uop.info.fuOpType)
  val e_is_tensor_pipe = e_valid && !e_uop.flushed && !e_killed_by_w && e_uop.info.fuType === FuType.tensor
  val w_is_tensor_pipe = w_valid && !w_uop.flushed && w_uop.info.fuType === FuType.tensor
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
  val d_rd_src_var_haz = d_is_amocas && nonZeroReg(d_rd) && hitVar(d_rd) && !longWbHit(d_rd)
  val d_tensor_rd_e_haz = d_is_tensor && nonZeroReg(d_rd) && hitE(d_rd)
  val d_e_raw_haz = d_rs1_e_haz || d_rs2_e_haz || d_rd_src_e_haz || d_tensor_rd_e_haz
  val d_rs1_w_haz = d_uses_rs1 && nonZeroReg(d_rs1) && wGpr === d_rs1
  val d_rs2_w_haz = d_uses_rs2 && nonZeroReg(d_rs2) && wGpr === d_rs2
  val d_w_raw_haz = d_rs1_w_haz || d_rs2_w_haz
  val d_lsu_base_not_ready = d_dec.info.isLsu && d_rs1_e_raw_haz && !d_lsu_base_e_forward
  val d_jr_not_ready = d_is_jalr && d_uses_rs1 && (hitE(d_rs1) || (hitVar(d_rs1) && !longWbHit(d_rs1)))
  val d_no_vslot = d_need_vslot_raw && !vAllocator.io.allocate.valid
  val d_tensor_busy = d_is_tensor && (tensorBusy || e_is_tensor_pipe || w_is_tensor_pipe || !tensorIssueQueue.io.enq.ready)
  val d_div_resource_busy = d_is_div && d_writes_rd &&
    (e_is_div || w_is_div_pipe || divUnit.io.busy || div_wb_pending_valid)
  val d_cache_busy = (r_state =/= DCacheRefillState.idle) || (m_state =/= MisalignedState.idle)
  val d_uncached_busy = u_state =/= UncachedState.idle
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
  val d_stall =
    d_rs1_var_haz || d_rs2_var_haz || d_rd_var_haz || d_rd_src_var_haz ||
      d_e_raw_haz || d_lsu_base_not_ready || d_jr_not_ready ||
      d_no_vslot || d_tensor_busy || d_div_resource_busy || d_cache_busy || d_uncached_busy ||
      d_store_queue_wait || d_fence_wait || d_misaligned_wait || d_nemu_trap_wait

  io.inst.ready := !d_stall
  val d_fire = io.inst.valid && io.inst.ready
  val d_interrupt_enabled = mstatus(mstatusMieBit) && (enabledInterrupts =/= 0.U)
  val d_interrupt_cause = Mux(
    enabledInterrupts(11),
    11.U(4.W),
    Mux(enabledInterrupts(3), 3.U(4.W), Mux(enabledInterrupts(7), 7.U(4.W), 0.U(4.W)))
  )
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

  val d_dcache_set = p.dcacheGetSet(d_lsu_addr)
  val d_dcache_idx = dcacheDataIdx(d_lsu_addr)
  val d_read_tag = d_enqueue && !d_interrupt_take && d_cache_lookup
  val d_read_data = d_enqueue && !d_interrupt_take && d_is_load && !d_is_uncached && !d_is_cmo
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
  val e_cache_lookup = e_can_side_effect && e_uop.info.isLsu && !e_is_uncached && !e_is_cmo && !e_exception
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
  val e_store_shifted = storeData(e_uop.info.fuOpType, e_store_data, e_addr)
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

  val e_miss = e_can_side_effect && e_uop.info.isLsu && !e_lsu_bypass && !e_exception && !e_is_cmo && !e_hit
  val e_cachable_hit_no_long_latency = e_uop.info.isLsu && (e_exception || (!e_lsu_bypass && (e_is_cmo || e_hit)))
  e_release_vslot := e_valid && !e_uop.flushed && !e_killed_by_w &&
    e_uop.vslot_valid && e_cachable_hit_no_long_latency
  e_release_slot := e_uop.vslot

  val w_csr_op = w_uop.info.fuType === FuType.csr &&
    (w_uop.info.fuOpType === CsrOp.csrrw || w_uop.info.fuOpType === CsrOp.csrrs || w_uop.info.fuOpType === CsrOp.csrrc)
  val w_csr_src = Mux(w_uop.info.src2Type === SrcType.imm, w_uop.src2, w_uop.src1)
  val w_csr_writes = w_csr_op && csrWrites(w_uop.info.fuOpType, w_csr_src)
  val w_csr_write_data = legalizeCsrWrite(w_uop.csrAddr, csrWriteData(w_uop.info.fuOpType, w_result, w_csr_src))
  val w_csr_bypass_valid = w_valid && !w_uop.flushed &&
    !w_uop.isInterrupt && !w_uop.exceptionValid && !w_uop.info.illegal &&
    w_csr_writes && csrModeledWritable(w_uop.csrAddr)
  val e_csr_read_data = Mux(
    w_csr_bypass_valid && w_uop.csrAddr === e_uop.csrAddr,
    w_csr_write_data,
    csrRead(e_uop.csrAddr, cycleCnt)
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
  val w_ecall_code = Mux(privilegeMode === 0.U, 8.U(16.W), Mux(privilegeMode === 1.U, 9.U(16.W), 11.U(16.W)))
  val w_trap_code = Mux(
    w_uop.info.illegal && !w_uop.isInterrupt,
    2.U(16.W),
    Mux(w_is_ecall && !w_uop.isInterrupt, w_ecall_code, w_uop.exceptionCode)
  )
  val w_trap_code_xlen = Cat(0.U((p.xlen - 16).W), w_trap_code)
  val w_interrupt_mcause_flag = Cat(1.B, 0.U((p.xlen - 1).W))
  val w_trap_mcause = Mux(w_uop.isInterrupt, w_interrupt_mcause_flag | w_trap_code_xlen, w_trap_code_xlen)
  val w_trap_mtval = Mux(
    !w_uop.isInterrupt && w_trap_code === 3.U,
    Cat(0.U((p.xlen - p.addrBits).W), w_uop.pc),
    0.U(p.xlen.W)
  )
  val privilegeModeXlen = Cat(0.U((p.xlen - 2).W), privilegeMode)
  val trapMpp = (privilegeModeXlen << 11)(p.xlen - 1, 0)
  val trapMstatusNext = legalizeMstatus(
    (mstatus & ~mstatusMieMask & ~mstatusMpieMask & ~mstatusMppMask) |
      trapMpp |
      Mux(mstatus(mstatusMieBit), mstatusMpieMask, 0.U(p.xlen.W))
  )
  val mretMstatusNext = legalizeMstatus(
    (mstatus & ~mstatusMieMask & ~mstatusMpieMask & ~mstatusMppMask) |
      mstatusMpieMask |
      Mux(mstatus(mstatusMpieBit), mstatusMieMask, 0.U(p.xlen.W))
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
  val w_is_fence_i = w_uop.inst === RV64IInstructions.FENCE_I.value.U
  val w_is_mret = w_uop.info.fuType === FuType.csr && w_uop.info.fuOpType === CsrOp.mret
  val w_mret_commit = w_can_commit && !w_trap && w_is_mret
  val w_flush_pipe = w_can_commit && !w_trap && !w_mret_commit && w_uop.info.flushPipe
  val w_store_commit = w_can_commit && !w_lsu_bypass && w_is_store && w_hit && !w_miss && !w_trap
  val w_bus_available =
    r_state === DCacheRefillState.idle && u_state === UncachedState.idle && m_state === MisalignedState.idle
  val w_start_refill = w_can_commit && !w_trap && !w_lsu_bypass && w_miss && !sq_has_entry &&
    w_bus_available
  val w_start_cmo = w_can_commit && !w_trap && !w_lsu_bypass && w_is_cmo && !sq_has_entry &&
    w_bus_available
  val w_start_cached_misaligned = w_can_commit && w_cached_misaligned && !w_trap && w_bus_available
  val w_start_uncached = w_can_commit && w_is_uncached && !w_trap && w_bus_available
  val w_is_mdu = w_uop.info.fuType === FuType.mdu
  val w_is_tensor = w_uop.info.fuType === FuType.tensor
  val w_mdu_needs_wb = w_uop.vslot_valid && nonZeroReg(w_uop.rd)
  val w_mdu_can_start = w_can_commit && w_is_mdu && w_mdu_needs_wb && !w_trap && !w_miss
  val w_mdu_is_mul = MduCalc.isMulOp(w_uop.info.fuOpType)
  val w_mdu_is_div = MduCalc.isDivOp(w_uop.info.fuOpType)
  val mtvecBase = Cat(mtvec(p.addrBits - 1, 2), 0.U(2.W))
  val w_trap_vector_offset = (Cat(0.U((p.addrBits - 16).W), w_trap_code) << 2)(p.addrBits - 1, 0)
  val w_trap_vector = Mux(
    w_uop.isInterrupt && mtvec(1, 0) === 1.U,
    mtvecBase + w_trap_vector_offset,
    mtvecBase
  )
  val w_csr_write_commit = w_can_commit && !w_trap && !w_mret_commit && !w_miss &&
    w_csr_writes && csrModeledWritable(w_uop.csrAddr)

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
    Mux(w_mret_commit, mepc(p.addrBits - 1, 0), Mux(w_miss, w_uop.pc, (w_uop.pc + 4.U)(p.addrBits - 1, 0)))
  )

  val w_tensor_issue = w_can_commit && !w_trap && !w_mret_commit && w_is_tensor
  tensorIssueQueue.io.enq.valid := w_tensor_issue
  tensorIssueQueue.io.enq.bits.instWord := w_uop.instWord
  tensorIssueQueue.io.enq.bits.rs1Data := w_uop.src1
  tensorIssueQueue.io.enq.bits.rs2Data := w_uop.src2
  tensorIssueQueue.io.enq.bits.rdData := w_uop.rdData
  assert(!w_tensor_issue || tensorIssueQueue.io.enq.ready,
    "Tensor issue queue was full at commit")

  w_rfdata := Mux(w_is_load, w_load_data, Mux(w_uop.info.fuType === FuType.jmp, w_uop.pc + 4.U, w_result))
  w_rfen := w_can_commit && !w_trap && !w_mret_commit && w_uop.info.rwen &&
    nonZeroReg(w_uop.rd) && !w_miss && !w_lsu_bypass && !w_is_mdu && !w_is_tensor
  val w_diff_rfwen = w_can_commit && !w_trap && !w_mret_commit && w_uop.info.rwen && nonZeroReg(w_uop.rd) && !w_miss && !w_lsu_bypass

  io.ifencei := w_flush_pipe && w_is_fence_i && !w_miss

  val w_commit_valid = w_can_commit && !w_miss && !w_trap && !w_lsu_bypass
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

  tensorRespReady := tensorBusy && !long_wb_valid && !w_release_vslot &&
    u_state === UncachedState.idle && m_state === MisalignedState.idle
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

  val diff_rf = Wire(Vec(32, UInt(p.xlen.W)))
  for (i <- 0 until 32) {
    val w_hit_rf = w_rfen && w_uop.rd === i.U
    val v_hit_rf = v_rfen && v_rf === i.U
    diff_rf(i) := Mux(i.U === 0.U, 0.U, Mux(w_hit_rf, w_rfdata, Mux(v_hit_rf, v_rfdata, rf(i))))
  }
  io.diffRf := diff_rf

  val diffMstatus = WireDefault(mstatus)
  val diffMepc = WireDefault(mepc)
  val diffMtval = WireDefault(mtval)
  val diffMtvec = WireDefault(mtvec)
  val diffMcause = WireDefault(mcause)
  val diffMip = WireDefault(mipRead)
  val diffMie = WireDefault(mie)
  val diffMscratch = WireDefault(mscratch)
  val diffPrivilegeMode = WireDefault(Cat(0.U((p.xlen - 2).W), privilegeMode))
  val mretPrivilegeMode = mstatus(12, 11)
  when(w_trap) {
    diffPrivilegeMode := 3.U
    diffMstatus := trapMstatusNext
    diffMepc := w_uop.pc
    diffMtval := w_trap_mtval
    diffMcause := w_trap_mcause
  }.elsewhen(w_mret_commit) {
    diffPrivilegeMode := Cat(0.U((p.xlen - 2).W), mretPrivilegeMode)
    diffMstatus := mretMstatusNext
  }.elsewhen(w_csr_write_commit) {
    switch(w_uop.csrAddr) {
      is("h300".U) { diffMstatus := w_csr_write_data }
      is("h304".U) { diffMie := w_csr_write_data }
      is("h305".U) { diffMtvec := w_csr_write_data }
      is("h340".U) { diffMscratch := w_csr_write_data }
      is("h341".U) { diffMepc := w_csr_write_data }
      is("h342".U) { diffMcause := w_csr_write_data }
      is("h343".U) { diffMtval := w_csr_write_data }
      is("h344".U) { diffMip := w_csr_write_data | linePendingMask }
    }
  }
  io.diffCsr.privilegeMode := diffPrivilegeMode
  io.diffCsr.mstatus := diffMstatus
  io.diffCsr.mepc := diffMepc
  io.diffCsr.mtval := diffMtval
  io.diffCsr.mtvec := diffMtvec
  io.diffCsr.mcause := diffMcause
  io.diffCsr.mip := diffMip
  io.diffCsr.mie := diffMie
  io.diffCsr.mscratch := diffMscratch

  when(w_tensor_issue) {
    tensorBusy := true.B
    tensorRd := w_uop.rd
    tensorHasVslot := w_uop.vslot_valid && nonZeroReg(w_uop.rd)
    tensorVslot := w_uop.vslot
  }
  when(tensorRespFire) {
    assert(io.tensorResp.bits.rd === tensorRd,
      "Tensor response rd did not match the outstanding Tensor request")
    tensorBusy := false.B
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

  when(w_trap) {
    privilegeMode := 3.U
    mstatus := trapMstatusNext
    mepc := w_uop.pc
    mcause := w_trap_mcause
    mtval := w_trap_mtval
  }.elsewhen(w_mret_commit) {
    privilegeMode := mretPrivilegeMode
    mstatus := mretMstatusNext
  }.elsewhen(w_csr_write_commit) {
    switch(w_uop.csrAddr) {
      is("h300".U) { mstatus := w_csr_write_data }
      is("h304".U) { mie := w_csr_write_data }
      is("h305".U) { mtvec := w_csr_write_data }
      is("h340".U) { mscratch := w_csr_write_data }
      is("h341".U) { mepc := w_csr_write_data }
      is("h342".U) { mcause := w_csr_write_data }
      is("h343".U) { mtval := w_csr_write_data }
      is("h344".U) { mip := w_csr_write_data }
      is("h3a0".U) { pmpcfg0 := w_csr_write_data }
      is("h3b0".U) { pmpaddr0 := w_csr_write_data }
    }
  }

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
  when(e_can_side_effect && e_is_load && e_hit) {
    perf.dcacheLoadHit := perf.dcacheLoadHit + 1.U
  }
  when(e_can_side_effect && e_is_store && e_hit) {
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
  when(r_state =/= DCacheRefillState.idle) {
    perf.dcacheRefillCycles := perf.dcacheRefillCycles + 1.U
  }
  when(r_state === DCacheRefillState.recvD && !io.bus.d.fire) {
    perf.dcacheRefillRespWait := perf.dcacheRefillRespWait + 1.U
  }
  when((r_state === DCacheRefillState.cmoRead) || (r_state === DCacheRefillState.cmoWriteback)) {
    perf.dcacheVictimWritebackCycles := perf.dcacheVictimWritebackCycles + 1.U
  }
  when(w_start_cmo) {
    perf.cmoStart := perf.cmoStart + 1.U
  }
  when(r_is_cmo && r_state =/= DCacheRefillState.idle) {
    perf.cmoCycles := perf.cmoCycles + 1.U
  }
  when(w_start_uncached) {
    perf.uncachedAccess := perf.uncachedAccess + 1.U
  }
  when(u_state =/= UncachedState.idle) {
    perf.uncachedCycles := perf.uncachedCycles + 1.U
  }
  when(mulPipe.io.in.valid) {
    perf.mulStart := perf.mulStart + 1.U
  }
  when(divUnit.io.start) {
    perf.divStart := perf.divStart + 1.U
  }
  when(divUnit.io.busy) {
    perf.divBusyCycles := perf.divBusyCycles + 1.U
  }

  // ============================================================
  // Store queue
  // ============================================================

  val sq_drain_sel = Mux(store_queue(0).valid, 0.U(1.W), 1.U(1.W))
  val sq_drain_entry = Mux(sq_drain_sel === 0.U, store_queue(0), store_queue(1))
  val sq_pipeline_lsu_busy = (e_can_side_effect && e_uop.info.isLsu) || (w_can_commit && w_uop.info.isLsu)
  val sq_can_drain =
    r_state === DCacheRefillState.idle && u_state === UncachedState.idle && m_state === MisalignedState.idle &&
      !sq_pipeline_lsu_busy && !d_read_tag && !d_read_data &&
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
  when(w_store_commit) {
    perf.storeQueueEnq := perf.storeQueueEnq + 1.U
  }
  when(m_store_commit && (m_store_entry0_valid || m_store_entry1_valid)) {
    perf.storeQueueEnq := perf.storeQueueEnq + PopCount(Seq(m_store_entry0_valid, m_store_entry1_valid))
  }
  when(sq_drain) {
    perf.storeQueueDrain := perf.storeQueueDrain + 1.U
  }
  when(store_queue(0).valid && store_queue(1).valid) {
    perf.storeQueueFullCycles := perf.storeQueueFullCycles + 1.U
  }.elsewhen(store_queue(0).valid || store_queue(1).valid) {
    perf.storeQueueOneEntryCycles := perf.storeQueueOneEntryCycles + 1.U
  }

  // ============================================================
  // DCache refill and CMO
  // ============================================================

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
  val u_complete_can_write = !long_wb_valid
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
  when(io.bus.a.valid && !io.bus.a.ready) {
    perf.dBusAWait := perf.dBusAWait + 1.U
  }
  when(io.atomicBus.a.valid && !io.atomicBus.a.ready) {
    perf.dBusAWait := perf.dBusAWait + 1.U
  }
  when(d_bus_d_expected && !d_bus_d_fire) {
    perf.dBusDWait := perf.dBusDWait + 1.U
    when(r_uses_bus) {
      perf.dBusDWaitRefill := perf.dBusDWaitRefill + 1.U
    }.otherwise {
      perf.dBusDWaitUncached := perf.dBusDWaitUncached + 1.U
    }
  }

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
    when(w_victim_valid && w_victim_tag.dirty) {
      r_state := DCacheRefillState.cmoRead
      r_a_valid := false.B
    }.otherwise {
      r_state := DCacheRefillState.sendA
      r_a_valid := true.B
    }
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

  // ============================================================
  // Cached misaligned LSU
  // ============================================================

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
    val byteCount = lsuByteCount(w_uop.info.fuOpType)
    val word0Addr = Cat(w_lsu_addr(p.addrBits - 1, wordOffsetBits), 0.U(wordOffsetBits.W))
    val word1Addr = (word0Addr + bytesPerWord.U)(p.addrBits - 1, 0)
    val endOffset = w_lsu_addr(wordOffsetBits - 1, 0) +& byteCount
    val needSecond = endOffset > bytesPerWord.U

    m_pc := w_uop.pc
    m_inst := w_uop.inst
    m_addr := w_lsu_addr
    m_op := w_uop.info.fuOpType
    m_rd := w_uop.rd
    m_vslot := w_uop.vslot
    m_vslot_valid := w_uop.vslot_valid
    m_is_load := w_is_load
    m_is_store := w_is_store
    m_byte_count := byteCount
    m_byte_idx := 0.U
    m_store_data := w_uop.store_data
    m_load_word := 0.U
    m_word0_addr := word0Addr
    m_word1_addr := word1Addr
    m_word0_hit := w_lookup_hit
    m_word1_hit := false.B
    m_word0_way := w_lookup_way
    m_word1_way := 0.U
    m_word0_data := w_cache_word
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
      m_release_vslot := m_vslot_valid
      m_release_slot := m_vslot

      v_rfen := m_is_load && nonZeroReg(m_rd)
      v_rf := m_rd
      v_rfdata := m_load_result

      when(m_is_load && nonZeroReg(m_rd)) {
        rf(m_rd) := m_load_result
      }

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

      io.commit.valid := true.B
      io.commit.bits.pc := m_pc
      io.commit.bits.inst := m_inst
      io.commit.bits.instWord := Cat(0.U(32.W), m_inst)
      io.commit.bits.jump := true.B
      io.commit.bits.target := (m_pc + 4.U)(p.addrBits - 1, 0)

      io.diffCommitValid := true.B
      io.diffCommitPc := m_pc
      io.diffCommitInst := m_inst
      io.diffCommitRfWen := m_is_load && nonZeroReg(m_rd)
      io.diffCommitRd := m_rd
      io.diffCommitIsLoad := m_is_load
      io.diffCommitIsStore := m_is_store
      io.diffCommitIsDelayedWb := false.B
      io.diffExit := m_inst === RV64IInstructions.NEMU_TRAP.value.U
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

  // ============================================================
  // Uncached LSU
  // ============================================================

  when(w_start_uncached) {
    val uncached_size = lsuSize(w_uop.info.fuOpType)
    val byte_mode = w_lsu_misaligned
    val simUartAddr = "h80001ff0".U(p.addrBits.W)
    val simUartStoreData = storeData(w_uop.info.fuOpType, w_uop.store_data, w_lsu_addr)

    when(w_is_store && w_lsu_addr === simUartAddr) {
      simUartValid := true.B
      simUartBits := simUartStoreData(7, 0)
    }

    u_pc := w_uop.pc
    u_inst := w_uop.inst
    u_addr := w_lsu_addr
    u_op := w_uop.info.fuOpType
    u_rd := w_uop.rd
    u_vslot := w_uop.vslot
    u_is_load := w_is_load
    u_is_store := w_is_store
    u_is_amo := w_is_amo
    u_size := uncached_size
    u_beats := Mux(w_is_amo, 1.U, Mux(uncached_size > busOffsetBits.U, refillBeatsPerWord.U(2.W), 1.U(2.W)))
    u_beat := 0.U
    u_byte_mode := byte_mode
    u_byte_idx := 0.U
    u_byte_count := lsuByteCount(w_uop.info.fuOpType)
    u_mask := Mux(w_is_store || w_is_amo, storeMask(w_uop.info.fuOpType, w_lsu_addr), 0.U)
    u_data := Mux(
      w_is_amo,
      amoBusData(w_uop.info.fuOpType, w_uop.store_data, w_lsu_addr),
      Mux(w_is_store, Mux(byte_mode, w_uop.store_data, storeData(w_uop.info.fuOpType, w_uop.store_data, w_lsu_addr)), 0.U)
    )
    u_compare := Mux(w_is_amo, amoCompareData(w_uop.info.fuOpType, w_uop.rdData, w_lsu_addr), 0.U)
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

      u_release_vslot := true.B
      u_release_slot := u_vslot

      val u_resp_op = Mux(LSUOp.isAmoW(u_op), LSUOp.lw, Mux(LSUOp.isAmoD(u_op), LSUOp.ld, u_op))
      val u_writes_rd = u_is_load && nonZeroReg(u_rd)
      v_rfen := u_writes_rd
      v_rf := u_rd
      v_rfdata := loadExtend(u_load_word_next, Mux(u_byte_mode, 0.U(p.addrBits.W), u_addr), u_resp_op)

      when(u_writes_rd) {
        rf(u_rd) := loadExtend(u_load_word_next, Mux(u_byte_mode, 0.U(p.addrBits.W), u_addr), u_resp_op)
      }

      when(u_byte_mode && u_is_store) {
        dc_valid := 0.U.asTypeOf(dc_valid)
      }

      io.commit.valid := true.B
      io.commit.bits.pc := u_pc
      io.commit.bits.inst := u_inst
      io.commit.bits.instWord := Cat(0.U(32.W), u_inst)
      io.commit.bits.jump := true.B
      io.commit.bits.target := (u_pc + 4.U)(p.addrBits - 1, 0)

      io.diffCommitValid := true.B
      io.diffCommitPc := u_pc
      io.diffCommitInst := u_inst
      io.diffCommitRfWen := u_writes_rd
      io.diffCommitRd := u_rd
      io.diffCommitIsLoad := u_is_load
      io.diffCommitIsStore := u_is_store || u_is_amo
      io.diffCommitIsDelayedWb := false.B
      io.diffExit := u_inst === RV64IInstructions.NEMU_TRAP.value.U
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

  // ============================================================
  // Pipeline and scoreboard updates
  // ============================================================

  assert(!(e_release_vslot && (u_release_vslot || m_release_vslot)), "E release and LSU slow release share release(0)")
  assert(!(u_release_vslot && m_release_vslot), "Uncached release and misaligned release share release(0)")
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
  w_cache_word := after_w_store
  w_hit_way := e_hit_way
  w_replace_way := e_replace_way
  w_miss := e_miss
  w_is_cmo := e_is_cmo
  w_victim_tag := e_victim_tag
  w_victim_valid := e_victim_valid
  when(e_is_cmo) {
    w_victim_tag := e_hit_tag
    w_victim_valid := e_hit
  }

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
