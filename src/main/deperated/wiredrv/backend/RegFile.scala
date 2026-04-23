package wiredrv.backend

import chisel3._
import chisel3.util._
import difftest._
import wiredrv.common._

class RegFile(implicit p: CoreParams) extends Module {
  val io = IO(new Bundle {
    val raddr = Input(Vec(3, UInt(5.W)))
    val rdata = Output(Vec(3, UInt(p.xlen.W)))
    val wen = Input(Vec(2, Bool()))
    val waddr = Input(Vec(2, UInt(5.W)))
    val wdata = Input(Vec(2, UInt(p.xlen.W)))
    val debug = Output(Vec(32, UInt(p.xlen.W)))
  })

  val regs = RegInit(VecInit(Seq.fill(32)(0.U(p.xlen.W))))
  val diffGpr = DifftestModule(new DiffPhyIntRegState(32), delay = 1, dontCare = true)
  val archRegs = Wire(Vec(32, UInt(p.xlen.W)))

  archRegs := regs

  for (i <- 0 until 2) {
    when(io.wen(i) && io.waddr(i).orR) {
      regs(io.waddr(i)) := io.wdata(i)
      archRegs(io.waddr(i)) := io.wdata(i)
    }
  }

  for (i <- 0 until 3) {
    io.rdata(i) := Mux(io.raddr(i).orR, regs(io.raddr(i)), 0.U)
  }

  diffGpr.coreid := 0.U
  diffGpr.value := archRegs

  io.debug := archRegs
}
