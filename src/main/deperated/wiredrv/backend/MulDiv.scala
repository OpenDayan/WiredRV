package wiredrv.backend

import chisel3._
import wiredrv.common._

class MulDiv(implicit p: CoreParams) extends Module {
  val io = IO(new Bundle {
    val reqValid = Input(Bool())
    val respValid = Output(Bool())
    val respData = Output(UInt(p.xlen.W))
  })

  io.respValid := false.B
  io.respData := 0.U
}
