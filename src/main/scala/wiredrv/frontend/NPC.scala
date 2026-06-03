package wiredrv.frontend

import chisel3._
import chisel3.util._
import wiredrv.CoreParams

class NPC(implicit p: CoreParams) extends Module {
  val io = IO(new Bundle {
    val redirects = Input(Vec(5, Valid(UInt(p.addrBits.W))))
    val pc = Decoupled(UInt(p.addrBits.W))
  })

  private val redirectPriority = io.redirects.map(r => r.valid -> r.bits)
  val anyRedirect = VecInit(io.redirects.map(_.valid)).asUInt.orR

  val f0pc = RegInit(p.resetPc.U(p.addrBits.W))
  val nextPc = PriorityMux(redirectPriority :+ (true.B -> f0pc))

  io.pc.valid := true.B
  io.pc.bits := nextPc

  when(io.pc.ready) {
    f0pc := nextPc + 4.U
  }.elsewhen(anyRedirect) {
    f0pc := nextPc
  }
}
