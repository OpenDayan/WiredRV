package wiredrv.bus

import chisel3._
import chisel3.util._
import wiredrv.CoreParams

class TLUHUnburstify(width: Int)(implicit p: CoreParams) extends Module {
  require(isPow2(width / 8), "TL-UH beat bytes must be power-of-two")

  val io = IO(new Bundle {
    val in = Flipped(new TLBundleUH(width))
    val out = new TLBundleUH(width)
  })

  val beatBytes = width / 8
  val beatLg = log2Ceil(beatBytes)
  val countBits = 16

  def pow2Bytes(size: UInt): UInt = {
    val bytes = Wire(UInt(countBits.W))
    bytes := (1.U(countBits.W) << size)(countBits - 1, 0)
    bytes
  }

  val reqReg = RegInit(0.U.asTypeOf(new TLUHChannelA(width)))
  val reqActive = RegInit(false.B)
  val offsetReg = RegInit(0.U(countBits.W))

  val curReq = Mux(reqActive, reqReg, io.in.a.bits)
  val curOffset = Mux(reqActive, offsetReg, 0.U(countBits.W))
  val curReqBytes = pow2Bytes(curReq.size)
  val curIsBurst = curReq.size > beatLg.U

  when(io.in.a.valid || reqActive) {
    assert(
      curReq.opcode === TLOpcode.Get || curReq.size <= beatLg.U,
      "TLUHUnburstify only supports burst reads and single-beat writes"
    )
  }

  val subReq = Wire(new TLUHChannelA(width))
  subReq := curReq
  subReq.address := curReq.address + curOffset
  subReq.size := Mux(curIsBurst, beatLg.U, curReq.size)
  subReq.mask := Mux(curIsBurst, Fill(beatBytes, true.B), curReq.mask)

  io.in.a.ready := !reqActive && io.out.a.ready
  io.out.a.valid := reqActive || io.in.a.valid
  io.out.a.bits := subReq

  when(io.out.a.fire) {
    when(reqActive) {
      when(curReqBytes <= (offsetReg + beatBytes.U)) {
        reqActive := false.B
        offsetReg := 0.U
      }.otherwise {
        offsetReg := offsetReg + beatBytes.U
      }
    }.elsewhen(curReqBytes > beatBytes.U) {
      reqReg := io.in.a.bits
      reqActive := true.B
      offsetReg := beatBytes.U
    }
  }

  io.in.d <> io.out.d
}
