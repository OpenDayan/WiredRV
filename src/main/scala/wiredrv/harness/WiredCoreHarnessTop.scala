package wiredrv.harness

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import difftest._
import difftest.common.DifftestMem
import lmss.axi._
import wiredrv._
import wiredrv.frontend._

class AxiDiffMem(axiP: AxiParams)(implicit p: CoreParams) extends Module {
  require(axiP.dataBits > 0 && (axiP.dataBits % 64 == 0 || axiP.dataBits == 32))

  val io = IO(new Bundle {
    val axi = Flipped(new AxiBundle(axiP))
  })

  private val lanes = if (axiP.dataBits <= 64) 1 else axiP.dataBits / 64
  private val beatBytes = axiP.dataBits / 8
  private val memBase = p.cachedRegions.head.base.U(p.addrBits.W)
  private val memBytes = p.cachedRegions.head.size
  private val mem = DifftestMem(memBytes, 8, 8, lanes, lanes)

  def memIndex(addr: UInt): UInt = (addr - memBase) >> 3
  def beatStep(size: UInt): UInt = (1.U(p.addrBits.W) << size)

  io.axi.aw.ready := false.B
  io.axi.w.ready := false.B
  io.axi.b.valid := false.B
  io.axi.b.bits := 0.U.asTypeOf(io.axi.b.bits)

  val rIdle :: rResp :: Nil = Enum(2)
  val rState = RegInit(rIdle)
  val rAddr = Reg(UInt(p.addrBits.W))
  val rLenLeft = Reg(UInt(axiP.lenBits.W))
  val rSize = Reg(UInt(axiP.sizeBits.W))
  val rId = Reg(UInt(axiP.idBits.W))
  val rUser = Reg(UInt(axiP.userBits.W))

  io.axi.ar.ready := rState === rIdle
  when(io.axi.ar.fire) {
    rState := rResp
    rAddr := io.axi.ar.bits.addr
    rLenLeft := io.axi.ar.bits.len
    rSize := io.axi.ar.bits.size
    rId := io.axi.ar.bits.id
    rUser := io.axi.ar.bits.user
  }

  val alignedAddr = Cat(rAddr(p.addrBits - 1, log2Ceil(beatBytes)), 0.U(log2Ceil(beatBytes).W))
  val readDataLanes = Wire(Vec(lanes, UInt(64.W)))
  for (i <- 0 until lanes) {
    readDataLanes(i) := mem.readAndHold(memIndex(alignedAddr + (i * 8).U), rState === rResp).asUInt
  }
  val readDataWide = Cat((lanes - 1 to 0 by -1).map(readDataLanes(_)))

  io.axi.r.valid := rState === rResp
  io.axi.r.bits.id := rId
  io.axi.r.bits.data := readDataWide(axiP.dataBits - 1, 0)
  io.axi.r.bits.resp := 0.U
  io.axi.r.bits.last := Mux(rLenLeft === 0.U, 1.U, 0.U)
  io.axi.r.bits.user := rUser

  when(io.axi.r.fire) {
    when(rLenLeft === 0.U) {
      rState := rIdle
    }.otherwise {
      rAddr := rAddr + beatStep(rSize)
      rLenLeft := rLenLeft - 1.U
    }
  }
}

class WiredCoreHarnessTop(implicit p: CoreParams) extends Module with HasDiffTestInterfaces {

  val io = IO(new Bundle {
    val inst = Valid(UInt(32.W))
    val pc = UInt(p.addrBits.W)
  })

  val d0 = DifftestModule(new DiffArchEvent, delay = 1, dontCare = true)
  val d1 = DifftestModule(new DiffPhyIntRegState(32), delay = 1, dontCare = true)
  val d2 = DifftestModule(new DiffCSRState, delay = 1, dontCare = true)
  val d3 = DifftestModule(new DiffInstrCommit, delay = 1, dontCare = true)
  val d4 = DifftestModule(new DiffTrapEvent, delay = 1, dontCare = true)

  val core = Module(new WiredCore)
  val mem = Module(new AxiDiffMem(AxiSlvParamsCalc(Seq(
    AxiParams(addrBits = p.addrBits, idBits = 4, dataBits = p.outerBusWidth),
    AxiParams(addrBits = p.addrBits, idBits = 4, dataBits = p.outerBusWidth)
  ))))
  val r = RegInit(0.B)
  r := !r
  // core.io.inst.ready := r
  core.io.inst.ready := 1.B
  mem.io.axi <> core.io.mem

  io.pc := core.io.inst.bits.pc
  io.inst.valid := core.io.inst.fire
  io.inst.bits := core.io.inst.bits.inst
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
