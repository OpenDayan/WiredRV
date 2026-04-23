package wiredrv.bus

import chisel3._
import chisel3.util._
import wiredrv.CoreParams

class BusToTLUH(width: Int)(implicit p: CoreParams) extends Module {
  require(isPow2(width / 8), "Bus/TL beat bytes must be power-of-two")

  val io = IO(new Bundle {
    val in = Flipped(new BusBundle(width))
    val out = new TLBundleUH(width)
  })

  private val beatBytes = width / 8

  val isRead = io.in.a.bits.op === BusOp.ReadOnly
  val isStore = io.in.a.bits.op === BusOp.StoreOnly
  when(io.in.a.valid) {
    assert(isRead || isStore, "BusToTLUH only supports ReadOnly/StoreOnly")
  }

  io.out.a.valid := io.in.a.valid
  io.out.a.bits.opcode := Mux(isRead, TLOpcode.Get, Mux(io.in.a.bits.mask.andR, TLOpcode.PutFullData, TLOpcode.PutPartialData))
  io.out.a.bits.param := 0.U
  io.out.a.bits.size := io.in.a.bits.size
  io.out.a.bits.source := io.in.a.bits.id
  io.out.a.bits.address := io.in.a.bits.address
  io.out.a.bits.mask := io.in.a.bits.mask
  io.out.a.bits.data := io.in.a.bits.data
  io.out.a.bits.corrupt := false.B
  io.in.a.ready := io.out.a.ready

  io.in.d.valid := io.out.d.valid
  io.in.d.bits.id := io.out.d.bits.source
  io.in.d.bits.size := io.out.d.bits.size
  io.in.d.bits.data := io.out.d.bits.data
  io.out.d.ready := io.in.d.ready
}
