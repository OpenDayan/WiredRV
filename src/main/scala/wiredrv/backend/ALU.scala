package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.CoreParams

class ALU(implicit p: CoreParams) extends Module {
  val io = IO(new Bundle {
    val src1 = Input(UInt(p.xlen.W))
    val src2 = Input(UInt(p.xlen.W))
    val op   = Input(UInt(AluOp.width.W))
    val out  = Output(UInt(p.xlen.W))
  })

  // 共享移位量，RV64 的字操作移位只使用低 5 位。
  val shamt = io.src2(5, 0)
  val shamtw = io.src2(4, 0)
  val word = WireDefault(0.U(32.W))

  io.out := 0.U

  switch(io.op) {
    is(AluOp.add)   { io.out := io.src1 + io.src2 }
    is(AluOp.sub)   { io.out := io.src1 - io.src2 }
    is(AluOp.sll)   { io.out := io.src1 << shamt }
    is(AluOp.slt)   { io.out := (io.src1.asSInt < io.src2.asSInt).asUInt }
    is(AluOp.sltu)  { io.out := io.src1 < io.src2 }
    is(AluOp.xor)   { io.out := io.src1 ^ io.src2 }
    is(AluOp.srl)   { io.out := io.src1 >> shamt }
    is(AluOp.sra)   { io.out := (io.src1.asSInt >> shamt).asUInt }
    is(AluOp.or)    { io.out := io.src1 | io.src2 }
    is(AluOp.and)   { io.out := io.src1 & io.src2 }
    is(AluOp.copy1) { io.out := io.src1 }
    is(AluOp.copy2) { io.out := io.src2 }
  }

  switch(io.op) {
    is(AluOp.addw) {
      word := io.src1(31, 0) + io.src2(31, 0)
      io.out := Cat(Fill(32, word(31)), word)
    }
    is(AluOp.subw) {
      word := io.src1(31, 0) - io.src2(31, 0)
      io.out := Cat(Fill(32, word(31)), word)
    }
    is(AluOp.sllw) {
      word := io.src1(31, 0) << shamtw
      io.out := Cat(Fill(32, word(31)), word)
    }
    is(AluOp.srlw) {
      word := io.src1(31, 0) >> shamtw
      io.out := Cat(Fill(32, word(31)), word)
    }
    is(AluOp.sraw) {
      word := (io.src1(31, 0).asSInt >> shamtw).asUInt
      io.out := Cat(Fill(32, word(31)), word)
    }
  }
}
