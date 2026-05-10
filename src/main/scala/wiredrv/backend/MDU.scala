package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.CoreParams

class MduWriteback(implicit p: CoreParams) extends Bundle {
  val rd = UInt(5.W)
  val vslot = UInt(p.longLatencySlotsBits.W)
  val data = UInt(p.xlen.W)
}

object MduCalc {
  def isMulOp(op: UInt): Bool =
    op === MduOp.mul || op === MduOp.mulh || op === MduOp.mulhsu ||
      op === MduOp.mulhu || op === MduOp.mulw

  def isDivOp(op: UInt): Bool =
    op === MduOp.div || op === MduOp.divu || op === MduOp.rem ||
      op === MduOp.remu || op === MduOp.divw || op === MduOp.divuw ||
      op === MduOp.remw || op === MduOp.remuw

  private def twos(x: UInt): UInt = (~x).asUInt + 1.U(x.getWidth.W)
  private def sext32(x: UInt): UInt = Cat(Fill(32, x(31)), x)

  def mulResult(src1: UInt, src2: UInt, op: UInt): UInt = {
    val src1w = src1(31, 0)
    val src2w = src2(31, 0)
    val lhsSigned = op === MduOp.mulh || op === MduOp.mulhsu
    val rhsSigned = op === MduOp.mulh
    val lhsMul = Cat(lhsSigned && src1(63), src1).asSInt
    val rhsMul = Cat(rhsSigned && src2(63), src2).asSInt
    val mulWide = (lhsMul * rhsMul).asUInt
    val mulw = sext32((src1w * src2w)(31, 0))

    MuxLookup(op, (src1 * src2)(63, 0))(Seq(
      MduOp.mulh -> mulWide(127, 64),
      MduOp.mulhsu -> mulWide(127, 64),
      MduOp.mulhu -> mulWide(127, 64),
      MduOp.mulw -> mulw
    ))
  }

  def divResult(src1: UInt, src2: UInt, op: UInt): UInt = {
    val src1w = src1(31, 0)
    val src2w = src2(31, 0)

    val minS64 = "h8000000000000000".U(64.W)
    val negOne64 = "hffffffffffffffff".U(64.W)
    val allOnes64 = "hffffffffffffffff".U(64.W)
    val divByZero = src2 === 0.U
    val divOverflow = src1 === minS64 && src2 === negOne64

    val src1Neg = src1(63)
    val src2Neg = src2(63)
    val src1Abs = Mux(src1Neg, twos(src1), src1)
    val src2Abs = Mux(src2Neg, twos(src2), src2)
    val safeSrc2Abs = Mux(src2Abs === 0.U, 1.U(64.W), src2Abs)
    val quotAbs = src1Abs / safeSrc2Abs
    val remAbs = src1Abs % safeSrc2Abs
    val quotS = Mux(src1Neg ^ src2Neg, twos(quotAbs), quotAbs)
    val remS = Mux(src1Neg, twos(remAbs), remAbs)
    val quotU = src1 / Mux(divByZero, 1.U(64.W), src2)
    val remU = src1 % Mux(divByZero, 1.U(64.W), src2)

    val minS32 = "h80000000".U(32.W)
    val negOne32 = "hffffffff".U(32.W)
    val allOnes32 = "hffffffff".U(32.W)
    val divwByZero = src2w === 0.U
    val divwOverflow = src1w === minS32 && src2w === negOne32

    val src1wNeg = src1w(31)
    val src2wNeg = src2w(31)
    val src1wAbs = Mux(src1wNeg, twos(src1w), src1w)
    val src2wAbs = Mux(src2wNeg, twos(src2w), src2w)
    val safeSrc2wAbs = Mux(src2wAbs === 0.U, 1.U(32.W), src2wAbs)
    val quotwAbs = src1wAbs / safeSrc2wAbs
    val remwAbs = src1wAbs % safeSrc2wAbs
    val quotwS = Mux(src1wNeg ^ src2wNeg, twos(quotwAbs), quotwAbs)
    val remwS = Mux(src1wNeg, twos(remwAbs), remwAbs)
    val quotwU = src1w / Mux(divwByZero, 1.U(32.W), src2w)
    val remwU = src1w % Mux(divwByZero, 1.U(32.W), src2w)

    MuxLookup(op, 0.U(64.W))(Seq(
      MduOp.div -> Mux(divByZero, allOnes64, Mux(divOverflow, minS64, quotS)),
      MduOp.divu -> Mux(divByZero, allOnes64, quotU),
      MduOp.rem -> Mux(divByZero, src1, Mux(divOverflow, 0.U(64.W), remS)),
      MduOp.remu -> Mux(divByZero, src1, remU),
      MduOp.divw -> sext32(Mux(divwByZero, allOnes32, Mux(divwOverflow, minS32, quotwS))),
      MduOp.divuw -> sext32(Mux(divwByZero, allOnes32, quotwU)),
      MduOp.remw -> sext32(Mux(divwByZero, src1w, Mux(divwOverflow, 0.U(32.W), remwS))),
      MduOp.remuw -> sext32(Mux(divwByZero, src1w, remwU))
    ))
  }
}

class MulPipeline(implicit p: CoreParams) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Valid(new Bundle {
      val src1 = UInt(p.xlen.W)
      val src2 = UInt(p.xlen.W)
      val op = UInt(MduOp.width.W)
      val rd = UInt(5.W)
      val vslot = UInt(p.longLatencySlotsBits.W)
    }))
    val out = Valid(new MduWriteback)
  })

  val stages = RegInit(VecInit(Seq.fill(4)(0.U.asTypeOf(Valid(new MduWriteback)))))

  io.out := stages.last

  stages(0).valid := io.in.valid
  stages(0).bits.rd := io.in.bits.rd
  stages(0).bits.vslot := io.in.bits.vslot
  stages(0).bits.data := MduCalc.mulResult(io.in.bits.src1, io.in.bits.src2, io.in.bits.op)

  for (i <- 1 until 4) {
    stages(i) := stages(i - 1)
  }
}

class DivUnit(implicit p: CoreParams) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val src1 = Input(UInt(p.xlen.W))
    val src2 = Input(UInt(p.xlen.W))
    val op = Input(UInt(MduOp.width.W))
    val rd = Input(UInt(5.W))
    val vslot = Input(UInt(p.longLatencySlotsBits.W))
    val busy = Output(Bool())
    val out = Valid(new MduWriteback)
  })

  val busyReg = RegInit(false.B)
  val doneReg = RegInit(false.B)
  val count = RegInit(0.U(4.W))
  val wbReg = RegInit(0.U.asTypeOf(new MduWriteback))

  io.busy := busyReg
  io.out.valid := doneReg
  io.out.bits := wbReg

  doneReg := false.B
  when(io.start && !busyReg) {
    wbReg.rd := io.rd
    wbReg.vslot := io.vslot
    wbReg.data := MduCalc.divResult(io.src1, io.src2, io.op)
    busyReg := true.B
    count := 7.U
  }.elsewhen(busyReg) {
    when(count === 0.U) {
      busyReg := false.B
      doneReg := true.B
    }.otherwise {
      count := count - 1.U
    }
  }
}
