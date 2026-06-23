package wiredrv.bus

import chisel3._
import chisel3.util._
import xs.infra.axi._
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

  private val AtopNone = 0.U(6.W)
  private val AtopAtomicSwap = "b110000".U(6.W)
  private val AtopAtomicCompare = "b110001".U(6.W)
  private def atopAtomicLoad(op: UInt): UInt =
    Cat("b10".U(2.W), 0.U(1.W), op(2, 0))

  private def atop(op: BusOp.Type): UInt = {
    val atomicLoadOp = MuxLookup(op.asUInt, 0.U(3.W))(Seq(
      BusOp.Add.asUInt -> "b000".U,
      BusOp.Clr.asUInt -> "b001".U,
      BusOp.Eor.asUInt -> "b010".U,
      BusOp.Set.asUInt -> "b011".U,
      BusOp.SMax.asUInt -> "b100".U,
      BusOp.SMin.asUInt -> "b101".U,
      BusOp.UMax.asUInt -> "b110".U,
      BusOp.UMin.asUInt -> "b111".U
    ))
    MuxLookup(op.asUInt, AtopNone)(Seq(
      BusOp.Add.asUInt -> atopAtomicLoad(atomicLoadOp),
      BusOp.Clr.asUInt -> atopAtomicLoad(atomicLoadOp),
      BusOp.Eor.asUInt -> atopAtomicLoad(atomicLoadOp),
      BusOp.Set.asUInt -> atopAtomicLoad(atomicLoadOp),
      BusOp.SMax.asUInt -> atopAtomicLoad(atomicLoadOp),
      BusOp.SMin.asUInt -> atopAtomicLoad(atomicLoadOp),
      BusOp.UMax.asUInt -> atopAtomicLoad(atomicLoadOp),
      BusOp.UMin.asUInt -> atopAtomicLoad(atomicLoadOp),
      BusOp.Swap.asUInt -> AtopAtomicSwap,
      BusOp.CAS.asUInt -> AtopAtomicCompare
    ))
  }

  private def isAxiAtomic(op: BusOp.Type): Bool =
    op === BusOp.Add || op === BusOp.Clr || op === BusOp.Eor || op === BusOp.Set ||
      op === BusOp.SMax || op === BusOp.SMin || op === BusOp.UMax || op === BusOp.UMin ||
      op === BusOp.Swap || op === BusOp.CAS

  val sIdle :: sReadResp :: sWriteSend :: sWriteResp :: sAtomicResp :: Nil = Enum(5)
  val state = RegInit(sIdle)
  val reqOp = RegInit(BusOp.ReadOnly)
  val reqId = Reg(UInt(4.W))
  val reqSize = Reg(UInt(4.W))
  val reqAddr = Reg(UInt(p.addrBits.W))
  val reqMask = Reg(UInt((width / 8).W))
  val reqData = Reg(UInt(width.W))
  val reqCompare = Reg(UInt(width.W))
  val awSent = Reg(Bool())
  val wSent = Reg(Bool())
  val wBeat = Reg(UInt(1.W))
  val atomicRSeen = Reg(Bool())
  val atomicBSeen = Reg(Bool())
  val atomicRData = Reg(UInt(width.W))

  val isRead = io.in.a.bits.op === BusOp.ReadOnly
  val isWrite = io.in.a.bits.op === BusOp.StoreOnly
  val isAtomic = isAxiAtomic(io.in.a.bits.op)
  val reqIsAtomic = isAxiAtomic(reqOp)
  val reqIsCas = reqOp === BusOp.CAS

  when(io.in.a.valid) {
    assert(isRead || isWrite || isAtomic, "BusToAxi saw an unknown bus op")
  }

  io.out.aw.valid := state === sWriteSend && !awSent
  io.out.aw.bits.id := reqId
  io.out.aw.bits.addr := reqAddr
  io.out.aw.bits.len := Mux(reqIsCas, 1.U, 0.U)
  io.out.aw.bits.size := reqSize
  io.out.aw.bits.burst := AxiComputeFunction.INCR
  io.out.aw.bits.lock := 0.U
  io.out.aw.bits.cache := 0.U
  io.out.aw.bits.prot := 0.U
  io.out.aw.bits.qos := 0.U
  io.out.aw.bits.region := 0.U
  io.out.aw.bits.user := 0.U
  io.out.aw.bits.atop := atop(reqOp)

  io.out.w.valid := state === sWriteSend && !wSent
  io.out.w.bits.data := Mux(reqIsCas && wBeat === 0.U, reqCompare, reqData)
  io.out.w.bits.strb := reqMask
  io.out.w.bits.last := Mux(reqIsCas, wBeat === 1.U, true.B)
  io.out.w.bits.user := 0.U
  io.out.b.ready := (state === sWriteResp && io.in.d.ready) ||
    (state === sAtomicResp && !atomicBSeen)

  io.out.ar.valid := state === sIdle && io.in.a.valid && isRead
  io.out.ar.bits.id := io.in.a.bits.id
  io.out.ar.bits.addr := io.in.a.bits.address
  io.out.ar.bits.len := (txnBeats(io.in.a.bits.size) - 1.U)(axiP.lenBits - 1, 0)
  io.out.ar.bits.size := Mux(io.in.a.bits.size <= beatLg.U, io.in.a.bits.size, beatLg.U)
  io.out.ar.bits.burst := AxiComputeFunction.INCR
  io.out.ar.bits.lock := 0.U
  io.out.ar.bits.cache := 0.U
  io.out.ar.bits.prot := 0.U
  io.out.ar.bits.qos := 0.U
  io.out.ar.bits.region := 0.U
  io.out.ar.bits.user := 0.U

  io.in.a.ready := state === sIdle && ((isRead && io.out.ar.ready) || isWrite || isAtomic)

  io.in.d.valid := (state === sReadResp && io.out.r.valid) ||
    (state === sWriteResp && io.out.b.valid) ||
    (state === sAtomicResp && atomicRSeen && atomicBSeen)
  io.in.d.bits.id := reqId
  io.in.d.bits.size := reqSize
  io.in.d.bits.data := Mux(state === sReadResp, io.out.r.bits.data, Mux(state === sAtomicResp, atomicRData, 0.U))
  io.out.r.ready := (state === sReadResp && io.in.d.ready) ||
    (state === sAtomicResp && !atomicRSeen)

  when(io.out.ar.fire) {
    reqOp := BusOp.ReadOnly
    reqId := io.in.a.bits.id
    reqSize := io.in.a.bits.size
    state := sReadResp
  }

  when(io.in.a.fire && (isWrite || isAtomic)) {
    reqOp := io.in.a.bits.op
    reqId := io.in.a.bits.id
    reqSize := io.in.a.bits.size
    reqAddr := io.in.a.bits.address
    reqMask := io.in.a.bits.mask
    reqData := io.in.a.bits.data
    reqCompare := io.in.a.bits.compare
    awSent := false.B
    wSent := false.B
    wBeat := 0.U
    atomicRSeen := false.B
    atomicBSeen := false.B
    atomicRData := 0.U
    state := sWriteSend
  }

  when(io.out.aw.fire) {
    awSent := true.B
  }
  when(io.out.w.fire) {
    when(reqIsCas && wBeat === 0.U) {
      wBeat := 1.U
    }.otherwise {
      wSent := true.B
    }
  }
  val writeDataDone = wSent || (io.out.w.fire && (!reqIsCas || wBeat === 1.U))
  when(state === sWriteSend && (awSent || io.out.aw.fire) && writeDataDone) {
    state := Mux(reqIsAtomic, sAtomicResp, sWriteResp)
  }

  when(io.out.r.fire && io.out.r.bits.last.asBool) {
    when(state === sReadResp) {
      state := sIdle
    }.otherwise {
      atomicRSeen := true.B
      atomicRData := io.out.r.bits.data
    }
  }

  when(io.out.b.fire) {
    when(state === sWriteResp) {
      state := sIdle
    }.otherwise {
      atomicBSeen := true.B
    }
  }

  when(state === sAtomicResp && io.in.d.fire) {
    state := sIdle
  }
}
