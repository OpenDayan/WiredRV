package wiredrv.bus

import chisel3._
import chisel3.util._
import wiredrv.CoreParams

class TLUHChannelA(width: Int)(implicit p: CoreParams) extends Bundle {
  val opcode = UInt(3.W)
  val param = UInt(3.W)
  val size = UInt(4.W)
  val source = UInt(4.W)
  val address = UInt(p.addrBits.W)
  val mask = UInt((width / 8).W)
  val data = UInt(width.W)
  val corrupt = Bool()
}

class TLUHChannelD(width: Int)(implicit p: CoreParams) extends Bundle {
  val opcode = UInt(3.W)
  val param = UInt(2.W)
  val size = UInt(4.W)
  val source = UInt(4.W)
  val sink = UInt(2.W)
  val denied = Bool()
  val data = UInt(width.W)
  val corrupt = Bool()
}

class TLBundleUH(width: Int)(implicit p: CoreParams) extends Bundle {
  val a = Decoupled(new TLUHChannelA(width))
  val d = Flipped(Decoupled(new TLUHChannelD(width)))
}

object TLOpcode {
  val PutFullData = 0.U(3.W)
  val PutPartialData = 1.U(3.W)
  val Get = 4.U(3.W)
}
