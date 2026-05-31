package wiredrv.bus

import chisel3._
import chisel3.util._
import wiredrv.CoreParams

object BusOp extends ChiselEnum {
  val ReadOnly = Value
  val StoreOnly = Value
  val Add = Value
  val Clr = Value
  val Eor = Value
  val Set = Value
  val SMax = Value
  val SMin = Value
  val UMax = Value
  val UMin = Value
  val Swap = Value
  val CAS = Value
  val Flush = Value
}

class BusA(width: Int)(implicit p: CoreParams) extends Bundle {
  val op = BusOp()
  val id = UInt(4.W)
  val size = UInt(4.W) // log2 Bytes
  val address = UInt(p.addrBits.W)
  val mask = UInt((width / 8).W)
  val data = UInt(width.W)
  val compare = UInt(width.W)
}

class BusD(width: Int)(implicit p: CoreParams) extends Bundle {
  val id = UInt(4.W)
  val size = UInt(4.W)
  val data = UInt(width.W)
}

class BusBundle(width: Int)(implicit p: CoreParams) extends Bundle {
  val a = Decoupled(new BusA(width))
  val d = Flipped(Decoupled(new BusD(width)))
}
