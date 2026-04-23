package wiredrv.harness

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import difftest.common.DifftestMem
import wiredrv._
import wiredrv.bus._
import wiredrv.frontend._
import difftest._

class TLUHDiffMem(width: Int)(implicit p: CoreParams) extends Module {
  require(isPow2(width / 8), "TL beat bytes must be power-of-two")
  require(width > 0 && (width % 64 == 0 || width == 32), "TLUHDiffMem supports 32-bit or multiples of 64-bit")

  val io = IO(new Bundle {
    val tl = Flipped(new TLBundleUH(width))
  })

  private val lanes = if (width <= 64) 1 else width / 64
  private val beatBytes = width / 8
  private val beatLg = log2Ceil(beatBytes)
  private val memBase = p.cachedRegions.head.base.U(p.addrBits.W)
  private val memBytes = p.cachedRegions.head.size
  private val mem = DifftestMem(memBytes, 8, 8, lanes, lanes)

  def memIndex(addr: UInt): UInt = (addr - memBase) >> 3

  val reqFire = io.tl.a.valid && io.tl.a.ready
  val isRead = io.tl.a.bits.opcode === TLOpcode.Get
  val isWrite = io.tl.a.bits.opcode === TLOpcode.PutFullData || io.tl.a.bits.opcode === TLOpcode.PutPartialData
  val alignedAddr = Cat(io.tl.a.bits.address(p.addrBits - 1, beatLg), 0.U(beatLg.W))
  val readDataLanes = Wire(Vec(lanes, UInt(64.W)))
  for (i <- 0 until lanes) {
    readDataLanes(i) := mem.readAndHold(memIndex(alignedAddr + (i * 8).U), reqFire && isRead).asUInt
  }
  val readDataWide = Cat((lanes - 1 to 0 by -1).map(readDataLanes(_)))
  val respValid = RegNext(reqFire, false.B)
  val respSize = RegEnable(io.tl.a.bits.size, reqFire)
  val respSource = RegEnable(io.tl.a.bits.source, reqFire)
  val respAddrLo = RegEnable(io.tl.a.bits.address(2, 0), reqFire)
  val respDataWide = RegEnable(readDataWide, reqFire && isRead)

  val respData = Wire(UInt(width.W))
  respData := respDataWide(width - 1, 0)
  if (width == 32) {
    respData := Mux(respAddrLo(2), respDataWide(63, 32), respDataWide(31, 0))
  }

  io.tl.a.ready := true.B
  io.tl.d.valid := respValid
  io.tl.d.bits.opcode := 0.U
  io.tl.d.bits.param := 0.U
  io.tl.d.bits.size := respSize
  io.tl.d.bits.source := respSource
  io.tl.d.bits.sink := 0.U
  io.tl.d.bits.denied := false.B
  io.tl.d.bits.data := Mux(isWrite, 0.U, respData)
  io.tl.d.bits.corrupt := false.B

  val writeDataWide = Wire(UInt((lanes * 64).W))
  val writeMaskWide = Wire(UInt((lanes * 8).W))
  writeDataWide := io.tl.a.bits.data
  writeMaskWide := io.tl.a.bits.mask
  if (width == 32) {
    writeDataWide := Mux(io.tl.a.bits.address(2), io.tl.a.bits.data << 32, io.tl.a.bits.data)
    writeMaskWide := Mux(io.tl.a.bits.address(2), io.tl.a.bits.mask << 4, io.tl.a.bits.mask)
  }

  val writeDataVec = writeDataWide.asTypeOf(Vec(lanes, UInt(64.W)))
  val writeMaskVec = writeMaskWide.asTypeOf(Vec(lanes, UInt(8.W)))

  for (i <- 0 until lanes) {
    mem.write(i).valid := reqFire && isWrite
    mem.write(i).index := memIndex(alignedAddr + (i * 8).U)
    mem.write(i).data := VecInit(Seq(writeDataVec(i)))
    mem.write(i).mask := VecInit(Seq(FillInterleaved(8, writeMaskVec(i))))
  }
}

class WiredCoreHarnessTop(implicit p: CoreParams) extends Module with HasDiffTestInterfaces {
  val io = IO(new Bundle {
    val inst = Decoupled(new FetchPacket)
  })

  // Dummy
  val d0 = DifftestModule(new DiffArchEvent, delay = 1, dontCare = true)
  val d1 = DifftestModule(new DiffPhyIntRegState(32), delay = 1, dontCare = true)
  val d2 = DifftestModule(new DiffCSRState, delay = 1, dontCare = true)
  val d3 = DifftestModule(new DiffInstrCommit, delay = 1, dontCare = true)
  val d4 = DifftestModule(new DiffTrapEvent, delay = 1, dontCare = true)

  val core = Module(new WiredCore)
  val unburst = Module(new TLUHUnburstify(p.outerBusWidth))
  val mem = Module(new TLUHDiffMem(p.outerBusWidth))

  io.inst <> core.io.inst
  unburst.io.in <> core.io.mem
  mem.io.tl <> unburst.io.out
}

object WiredCoreHarnessTopMain extends App {
  implicit val p: CoreParams = CoreParams()
  ChiselStage.emitSystemVerilog(
    DifftestModule.top(new WiredCoreHarnessTop()),
    firtoolOpts = Array(
      "--mlir-disable-threading",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=build/rtl"
    )
  )
}
