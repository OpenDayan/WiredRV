package wiredrv.frontend

import chisel3._
import chisel3.util._
import wiredrv.common._

class NextPCIO(implicit p: CoreParams) extends Bundle {
  val redirects = Input(Vec(5, Valid(new RedirectReq)))
  val currentPc = Input(UInt(p.addrBits.W))
  val nextPc = Output(UInt(p.addrBits.W))
  val selected = Output(Valid(new RedirectReq))
}

class NextPC(implicit p: CoreParams) extends Module {
  val io = IO(new NextPCIO)

  val selected = Wire(Valid(new RedirectReq))
  selected.valid := false.B
  selected.bits := 0.U.asTypeOf(new RedirectReq)

  when(io.redirects(RedirectCause.backendReplayOrException).valid) {
    selected := io.redirects(RedirectCause.backendReplayOrException)
  }.elsewhen(io.redirects(RedirectCause.exuFlush).valid) {
    selected := io.redirects(RedirectCause.exuFlush)
  }.elsewhen(io.redirects(RedirectCause.jumpEarlyDecode).valid) {
    selected := io.redirects(RedirectCause.jumpEarlyDecode)
  }.elsewhen(io.redirects(RedirectCause.frontendMissReplay).valid) {
    selected := io.redirects(RedirectCause.frontendMissReplay)
  }.elsewhen(io.redirects(RedirectCause.nlp).valid) {
    selected := io.redirects(RedirectCause.nlp)
  }

  io.selected := selected
  io.nextPc := Mux(selected.valid, selected.bits.target, io.currentPc + 4.U)
}
