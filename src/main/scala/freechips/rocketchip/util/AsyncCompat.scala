package freechips.rocketchip.util

import chisel3._
import chisel3.util.Decoupled

case class AsyncQueueParams(
  depth: Int = 8,
  sync: Int = 3,
  safe: Boolean = true,
  narrow: Boolean = false)

class AsyncBundle[T <: Data](gen: T, params: AsyncQueueParams) extends Bundle {
  val enq = Flipped(Decoupled(gen))
  val deq = Decoupled(gen)
}
