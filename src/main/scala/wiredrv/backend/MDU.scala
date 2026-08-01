/*
 * Copyright (c) 2026 Institute of Computing Technology, Chinese Academy of Sciences
 * SPDX-License-Identifier: MulanPSL-2.0
 *
 * WiredRV is licensed under the Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *
 *     http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 */

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

  class MulPipePayload extends Bundle {
    val rd = UInt(5.W)
    val vslot = UInt(p.longLatencySlotsBits.W)
    val op = UInt(MduOp.width.W)
    val negate = Bool()
    val aLo = UInt(32.W)
    val aHi = UInt(32.W)
    val bLo = UInt(32.W)
    val bHi = UInt(32.W)
    val acc = UInt(128.W)
  }

  def to128Low(product: UInt): UInt = Cat(0.U(64.W), product)
  def to128Shift32(product: UInt): UInt = Cat(0.U(32.W), product, 0.U(32.W))
  def to128Shift64(product: UInt): UInt = Cat(product, 0.U(64.W))
  def twos128(x: UInt): UInt = (~x).asUInt + 1.U(128.W)
  def abs64(x: UInt, neg: Bool): UInt = Mux(neg, (~x).asUInt + 1.U(64.W), x)
  def sext32(x: UInt): UInt = Cat(Fill(32, x(31)), x)

  // W stage is the first multiply stage. The following registers carry the
  // remaining three stages, one 32x32 partial product per stage.
  val s1 = RegInit(0.U.asTypeOf(Valid(new MulPipePayload)))
  val s2 = RegInit(0.U.asTypeOf(Valid(new MulPipePayload)))
  val s3 = RegInit(0.U.asTypeOf(Valid(new MulPipePayload)))

  val op = io.in.bits.op
  val lhsSigned = op === MduOp.mulh || op === MduOp.mulhsu
  val rhsSigned = op === MduOp.mulh
  val lhsNeg = lhsSigned && io.in.bits.src1(63)
  val rhsNeg = rhsSigned && io.in.bits.src2(63)
  val aAbs = abs64(io.in.bits.src1, lhsNeg)
  val bAbs = abs64(io.in.bits.src2, rhsNeg)
  val pp0 = aAbs(31, 0) * bAbs(31, 0)

  s1.valid := io.in.valid
  s1.bits.rd := io.in.bits.rd
  s1.bits.vslot := io.in.bits.vslot
  s1.bits.op := op
  s1.bits.negate := lhsNeg ^ rhsNeg
  s1.bits.aLo := aAbs(31, 0)
  s1.bits.aHi := aAbs(63, 32)
  s1.bits.bLo := bAbs(31, 0)
  s1.bits.bHi := bAbs(63, 32)
  s1.bits.acc := to128Low(pp0)

  val pp1 = s1.bits.aLo * s1.bits.bHi
  s2 := s1
  s2.bits.acc := s1.bits.acc + to128Shift32(pp1)

  val pp2 = s2.bits.aHi * s2.bits.bLo
  s3 := s2
  s3.bits.acc := s2.bits.acc + to128Shift32(pp2)

  val pp3 = s3.bits.aHi * s3.bits.bHi
  val productAbs = s3.bits.acc + to128Shift64(pp3)
  val product = Mux(s3.bits.negate, twos128(productAbs), productAbs)
  val outData = MuxLookup(s3.bits.op, product(63, 0))(Seq(
    MduOp.mulh -> product(127, 64),
    MduOp.mulhsu -> product(127, 64),
    MduOp.mulhu -> product(127, 64),
    MduOp.mulw -> sext32(product(31, 0))
  ))

  io.out.valid := s3.valid
  io.out.bits.rd := s3.bits.rd
  io.out.bits.vslot := s3.bits.vslot
  io.out.bits.data := outData
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

  val sIdle :: sRun :: sDone :: Nil = Enum(3)
  val state = RegInit(sIdle)

  val opReg = RegInit(MduOp.div)
  val isWordReg = RegInit(false.B)
  val isRemReg = RegInit(false.B)
  val negQuotReg = RegInit(false.B)
  val negRemReg = RegInit(false.B)
  val iterReg = RegInit(0.U(7.W))
  val dividendReg = RegInit(0.U(64.W))
  val divisorReg = RegInit(0.U(127.W))
  val quotReg = RegInit(0.U(64.W))
  val wbReg = RegInit(0.U.asTypeOf(new MduWriteback))

  io.busy := state === sRun
  io.out.valid := state === sDone
  io.out.bits := wbReg

  def twos(x: UInt): UInt = (~x).asUInt + 1.U(x.getWidth.W)
  def sext32(x: UInt): UInt = Cat(Fill(32, x(31)), x)

  val startIsWord = io.op === MduOp.divw || io.op === MduOp.divuw ||
    io.op === MduOp.remw || io.op === MduOp.remuw
  val startIsRem = io.op === MduOp.rem || io.op === MduOp.remu ||
    io.op === MduOp.remw || io.op === MduOp.remuw
  val startIsSigned = io.op === MduOp.div || io.op === MduOp.rem ||
    io.op === MduOp.divw || io.op === MduOp.remw

  val src1w = io.src1(31, 0)
  val src2w = io.src2(31, 0)
  val src1Neg = startIsSigned && Mux(startIsWord, src1w(31), io.src1(63))
  val src2Neg = startIsSigned && Mux(startIsWord, src2w(31), io.src2(63))
  val src1Abs64 = Mux(src1Neg, twos(io.src1), io.src1)
  val src2Abs64 = Mux(src2Neg, twos(io.src2), io.src2)
  val src1Abs32 = Mux(src1Neg, twos(src1w), src1w)
  val src2Abs32 = Mux(src2Neg, twos(src2w), src2w)
  val dividendInit = Mux(startIsWord, Cat(0.U(32.W), src1Abs32), src1Abs64)
  val divisorInit = Mux(
    startIsWord,
    Cat(0.U(64.W), src2Abs32, 0.U(31.W)),
    Cat(src2Abs64, 0.U(63.W))
  )

  val minS64 = "h8000000000000000".U(64.W)
  val negOne64 = "hffffffffffffffff".U(64.W)
  val minS32 = "h80000000".U(32.W)
  val negOne32 = "hffffffff".U(32.W)
  val divByZero = Mux(startIsWord, src2w === 0.U, io.src2 === 0.U)
  val divOverflow = startIsSigned && Mux(
    startIsWord,
    src1w === minS32 && src2w === negOne32,
    io.src1 === minS64 && io.src2 === negOne64
  )
  val divByZeroResult = Mux(
    startIsRem,
    Mux(startIsWord, sext32(src1w), io.src1),
    "hffffffffffffffff".U(64.W)
  )
  val overflowResult = Mux(
    startIsRem,
    0.U(64.W),
    Mux(startIsWord, sext32(minS32), minS64)
  )
  val specialResult = Mux(divByZero, divByZeroResult, overflowResult)

  def acceptStart(): Unit = {
    wbReg.rd := io.rd
    wbReg.vslot := io.vslot
    opReg := io.op
    isWordReg := startIsWord
    isRemReg := startIsRem
    negQuotReg := src1Neg ^ src2Neg
    negRemReg := src1Neg
    dividendReg := dividendInit
    divisorReg := divisorInit
    quotReg := 0.U
    iterReg := Mux(startIsWord, 32.U, 64.U)

    when(divByZero || divOverflow) {
      wbReg.data := specialResult
      state := sDone
    }.otherwise {
      state := sRun
    }
  }

  val dividendWide = Cat(0.U(63.W), dividendReg)
  val canSubtract = dividendWide >= divisorReg
  val subResult = dividendWide - divisorReg
  val nextDividend = Mux(canSubtract, subResult(63, 0), dividendReg)
  val nextQuot = Cat(quotReg(62, 0), canSubtract)
  val correctedQuot = Mux(negQuotReg, twos(nextQuot), nextQuot)
  val correctedRem = Mux(negRemReg, twos(nextDividend), nextDividend)
  val result64 = Mux(isRemReg, correctedRem, correctedQuot)
  val resultData = Mux(isWordReg, sext32(result64(31, 0)), result64)

  when(state === sRun) {
    dividendReg := nextDividend
    divisorReg := Cat(0.U(1.W), divisorReg(126, 1))
    quotReg := nextQuot
    iterReg := iterReg - 1.U

    when(iterReg === 1.U) {
      wbReg.data := resultData
      state := sDone
    }
  }.otherwise {
    when(io.start) {
      acceptStart()
    }.elsewhen(state === sDone) {
      state := sIdle
    }
  }
}
