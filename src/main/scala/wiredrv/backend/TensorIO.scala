package wiredrv.backend

import chisel3._

class TensorInst extends Bundle {
  val instWord = UInt(64.W)
  val rs1Data = UInt(64.W)
  val rs2Data = UInt(64.W)
  val rdData = UInt(64.W)
  val expectsResp = Bool()
}

class TensorResp extends Bundle {
  val data = UInt(64.W)
  val rd = UInt(5.W)
}
