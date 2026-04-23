package wiredrv.backend

import chisel3._
import wiredrv.common._

class Writeback(implicit p: CoreParams) extends Module {
  val io = IO(new Bundle {
    val inValid = Input(Bool())
    val rd = Input(UInt(5.W))
    val data = Input(UInt(p.xlen.W))
  })
}
