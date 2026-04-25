package xs.utils.sram

import chisel3._
import chisel3.util._

class SRAMReadReq(addrBits: Int) extends Bundle {
  val addr = UInt(addrBits.W)

  def apply(addr: UInt): Unit = {
    this.addr := addr
  }
}

class SRAMWriteReq[T <: Data](gen: T, addrBits: Int, maskBits: Int) extends Bundle {
  val data = gen.cloneType
  val addr = UInt(addrBits.W)
  val mask = UInt(maskBits.W)

  def apply(data: T, addr: UInt, mask: UInt): Unit = {
    this.data := data
    this.addr := addr
    this.mask := mask
  }

  def apply(data: T, addr: UInt, enable: UInt, mask: UInt): Unit = {
    this.data := data
    this.addr := addr
    this.mask := Mux(enable.asBool, mask, 0.U(maskBits.W))
  }
}

class SRAMReadResp[T <: Data](gen: T) extends Bundle {
  val data = Vec(1, gen.cloneType)
}

class SRAMTemplate[T <: Data](
  gen:        T,
  set:        Int,
  singlePort: Boolean = false,
  useBitmask: Boolean = false)
    extends Module {
  require(set > 0, "SRAMTemplate entries must be positive")
  private val addrBits = log2Ceil(set max 2)
  private val maskBits = gen.getWidth max 1

  val io = IO(new Bundle {
    val r = new Bundle {
      val req = Flipped(Valid(new SRAMReadReq(addrBits)))
      val resp = Output(new SRAMReadResp(gen))
    }
    val w = new Bundle {
      val req = Flipped(Valid(new SRAMWriteReq(gen, addrBits, maskBits)))
    }
  })

  if (useBitmask) {
    val mem = SyncReadMem(set, Vec(maskBits, Bool()))
    val readData = mem.read(io.r.req.bits.addr, io.r.req.valid)
    when(io.w.req.valid) {
      mem.write(
        io.w.req.bits.addr,
        VecInit(io.w.req.bits.data.asUInt.asBools),
        io.w.req.bits.mask.asBools)
    }
    io.r.resp.data(0) := readData.asUInt.asTypeOf(gen)
  } else {
    val mem = SyncReadMem(set, gen.cloneType)
    io.r.resp.data(0) := mem.read(io.r.req.bits.addr, io.r.req.valid)
    when(io.w.req.valid) {
      mem.write(io.w.req.bits.addr, io.w.req.bits.data)
    }
  }
}
