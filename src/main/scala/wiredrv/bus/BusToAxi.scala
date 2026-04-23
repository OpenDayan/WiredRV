package wiredrv.bus

import chisel3._
import chisel3.util._
import lmss.axi._
import wiredrv.CoreParams

class BusToAxi(width: Int)(implicit p: CoreParams) extends Module {
  require(isPow2(width / 8), "Bus/AXI beat bytes must be power-of-two")

  private val axiP = AxiParams(addrBits = p.addrBits, idBits = 4, dataBits = width)

  val io = IO(new Bundle {
    val in = Flipped(new BusBundle(width))
    val out = new AxiBundle(axiP)
  })

  private val beatBytes = width / 8
  private val beatLg = log2Ceil(beatBytes)
  private val countBits = 16

  def txnBytes(size: UInt): UInt = {
    val bytes = Wire(UInt(countBits.W))
    bytes := (1.U(countBits.W) << size)(countBits - 1, 0)
    bytes
  }

  def txnBeats(size: UInt): UInt = {
    val beats = Wire(UInt(countBits.W))
    beats := Mux(size <= beatLg.U, 1.U, txnBytes(size) >> beatLg)
    beats
  }

  val sIdle :: sReadResp :: Nil = Enum(2)
  val state = RegInit(sIdle)
  val reqId = Reg(UInt(4.W))
  val reqSize = Reg(UInt(4.W))

  val isRead = io.in.a.bits.op === BusOp.ReadOnly
  val isWrite = io.in.a.bits.op === BusOp.StoreOnly

  when(io.in.a.valid) {
    assert(isRead || isWrite, "BusToAxi only supports ReadOnly/StoreOnly")
    assert(!isWrite, "current BusToAxi only wires the read path")
  }

  io.out.aw.valid := false.B
  io.out.aw.bits := 0.U.asTypeOf(io.out.aw.bits)
  io.out.w.valid := false.B
  io.out.w.bits := 0.U.asTypeOf(io.out.w.bits)
  io.out.b.ready := true.B

  io.out.ar.valid := state === sIdle && io.in.a.valid && isRead
  io.out.ar.bits.id := io.in.a.bits.id
  io.out.ar.bits.addr := io.in.a.bits.address
  io.out.ar.bits.len := (txnBeats(io.in.a.bits.size) - 1.U)(axiP.lenBits - 1, 0)
  io.out.ar.bits.size := beatLg.U
  io.out.ar.bits.burst := AxiComputeFunction.INCR
  io.out.ar.bits.lock := 0.U
  io.out.ar.bits.cache := 0.U
  io.out.ar.bits.prot := 0.U
  io.out.ar.bits.qos := 0.U
  io.out.ar.bits.region := 0.U
  io.out.ar.bits.user := 0.U

  io.in.a.ready := io.out.ar.ready && state === sIdle && isRead

  io.in.d.valid := state === sReadResp && io.out.r.valid
  io.in.d.bits.id := reqId
  io.in.d.bits.size := reqSize
  io.in.d.bits.data := io.out.r.bits.data
  io.out.r.ready := state === sReadResp && io.in.d.ready

  when(io.out.ar.fire) {
    reqId := io.in.a.bits.id
    reqSize := io.in.a.bits.size
    state := sReadResp
  }

  when(io.out.r.fire && io.out.r.bits._last) {
    state := sIdle
  }
}
