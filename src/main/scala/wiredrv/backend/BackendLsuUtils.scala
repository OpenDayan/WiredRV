package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.{AddrRegion, CoreParams}

class BackendLsuUtils(implicit p: CoreParams) {
  private val bytesPerWord = p.xlen / 8
  private val wordOffsetBits = log2Ceil(bytesPerWord)
  private val byteCountBits = log2Ceil(bytesPerWord + 1)

  def nonZeroReg(reg: UInt): Bool = reg =/= 0.U

  def inRegions(addr: UInt, regions: Seq[AddrRegion]): Bool =
    regions
      .map(r => addr >= r.base.U(p.addrBits.W) && addr < (r.base + r.size).U(p.addrBits.W))
      .reduceOption(_ || _)
      .getOrElse(false.B)

  def dcacheDataIdx(set: UInt, word: UInt): UInt = set ## word

  def dcacheDataIdx(addr: UInt): UInt =
    dcacheDataIdx(p.dcacheGetSet(addr), p.dcacheGetWord(addr))

  def lineBase(addr: UInt): UInt =
    Cat(addr(p.addrBits - 1, p.dcacheLineBits), 0.U(p.dcacheLineBits.W))

  def lineBaseFromTag(tag: UInt, set: UInt): UInt =
    Cat(tag, set, 0.U(p.dcacheLineBits.W))

  def sameWordAddr(a: UInt, b: UInt): Bool =
    a(p.addrBits - 1, wordOffsetBits) === b(p.addrBits - 1, wordOffsetBits)

  def byteMask(mask: UInt): UInt =
    Cat(mask.asBools.reverse.map(bit => Fill(8, bit)))

  def isCmoOp(op: UInt): Bool =
    op === LSUOp.cboInval || op === LSUOp.cboClean || op === LSUOp.cboFlush

  def amoNeedsAlign(op: UInt, addr: UInt): Bool =
    (LSUOp.isAmoW(op) && addr(1, 0).orR) || (LSUOp.isAmoD(op) && addr(2, 0).orR)

  def loadNeedsAlign(op: UInt, addr: UInt): Bool =
    MuxLookup(op, false.B)(Seq(
      LSUOp.lh -> addr(0),
      LSUOp.lhu -> addr(0),
      LSUOp.lw -> addr(1, 0).orR,
      LSUOp.lwu -> addr(1, 0).orR,
      LSUOp.ld -> addr(2, 0).orR
    ))

  def storeNeedsAlign(op: UInt, addr: UInt): Bool =
    MuxLookup(op, false.B)(Seq(
      LSUOp.sh -> addr(0),
      LSUOp.sw -> addr(1, 0).orR,
      LSUOp.sd -> addr(2, 0).orR
    ))

  def lsuSize(op: UInt): UInt =
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

  def lsuByteCount(op: UInt): UInt =
    (1.U(byteCountBits.W) << lsuSize(op))(byteCountBits - 1, 0)

  def storeMask(op: UInt, addr: UInt): UInt = {
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

  def storeData(op: UInt, data: UInt, addr: UInt): UInt = {
    val storeBytes = MuxLookup(op, data)(Seq(
      LSUOp.sb -> Cat(0.U((p.xlen - 8).W), data(7, 0)),
      LSUOp.sh -> Cat(0.U((p.xlen - 16).W), data(15, 0)),
      LSUOp.sw -> Cat(0.U((p.xlen - 32).W), data(31, 0)),
      LSUOp.sd -> data
    ))
    storeBytes << (addr(wordOffsetBits - 1, 0) << 3)
  }

  def amoOperand(op: UInt, data: UInt): UInt = {
    val wordData = Cat(0.U((p.xlen - 32).W), data(31, 0))
    val sizedData = Mux(LSUOp.isAmoW(op), wordData, data)
    Mux(op === LSUOp.amoandW, Cat(0.U((p.xlen - 32).W), ~data(31, 0)),
      Mux(op === LSUOp.amoandD, ~data, sizedData))
  }

  def amoBusData(op: UInt, data: UInt, addr: UInt): UInt =
    (amoOperand(op, data) << (addr(wordOffsetBits - 1, 0) << 3))(p.xlen - 1, 0)

  def amoCompareData(op: UInt, data: UInt, addr: UInt): UInt = {
    val wordData = Cat(0.U((p.xlen - 32).W), data(31, 0))
    val sizedData = Mux(LSUOp.isAmoW(op), wordData, data)
    (sizedData << (addr(wordOffsetBits - 1, 0) << 3))(p.xlen - 1, 0)
  }

  def mergeByMask(base: UInt, data: UInt, mask: UInt): UInt = {
    val bitMask = byteMask(mask)
    (base & ~bitMask) | (data & bitMask)
  }

  def loadExtend(word: UInt, addr: UInt, op: UInt): UInt = {
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
}
