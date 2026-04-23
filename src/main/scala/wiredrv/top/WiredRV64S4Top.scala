package wiredrv.top

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import difftest._
import difftest.common.DifftestMem
import wiredrv.common._

class WiredRV64S4Top(implicit p: CoreParams) extends Module with HasDiffTestInterfaces {
  val core = Module(new WiredRV64S4Core)
  val memBase = p.cachedRegions.head.base.U(p.addrBits.W)
  val memBytes = p.cachedRegions.head.size
  val mem = DifftestMem(memBytes, 8, 8, 2, 1)

  def memIndex(addr: UInt): UInt = (addr - memBase) >> 3

  val imemReadFire = core.io.imem.a.valid && core.io.imem.a.ready &&
    core.io.imem.a.bits.opcode === TLBundleUH.Get
  val imemReadData = mem.readAndHold(memIndex(core.io.imem.a.bits.address), imemReadFire).asUInt
  val imemRespValid = RegNext(imemReadFire, false.B)
  val imemRespSize = RegEnable(core.io.imem.a.bits.size, imemReadFire)
  val imemRespSource = RegEnable(core.io.imem.a.bits.source, imemReadFire)

  core.io.imem.a.ready := true.B
  core.io.imem.d.valid := imemRespValid
  core.io.imem.d.bits.opcode := 0.U
  core.io.imem.d.bits.param := 0.U
  core.io.imem.d.bits.size := imemRespSize
  core.io.imem.d.bits.source := imemRespSource
  core.io.imem.d.bits.sink := 0.U
  core.io.imem.d.bits.denied := false.B
  core.io.imem.d.bits.data := imemReadData
  core.io.imem.d.bits.corrupt := false.B

  val dmemReqFire = core.io.dmem.a.valid && core.io.dmem.a.ready
  val dmemReadFire = dmemReqFire && core.io.dmem.a.bits.opcode === TLBundleUH.Get
  val dmemWriteFire = dmemReqFire && core.io.dmem.a.bits.opcode =/= TLBundleUH.Get
  val dmemReadData = mem.readAndHold(memIndex(core.io.dmem.a.bits.address), dmemReadFire).asUInt
  val dmemRespValid = RegNext(dmemReadFire || dmemWriteFire, false.B)
  val dmemRespIsRead = RegNext(dmemReadFire, false.B)
  val dmemRespSize = RegEnable(core.io.dmem.a.bits.size, dmemReqFire)
  val dmemRespSource = RegEnable(core.io.dmem.a.bits.source, dmemReqFire)
  val dmemRespData = RegEnable(dmemReadData, dmemReadFire)

  core.io.dmem.a.ready := true.B
  core.io.dmem.d.valid := dmemRespValid
  core.io.dmem.d.bits.opcode := 0.U
  core.io.dmem.d.bits.param := 0.U
  core.io.dmem.d.bits.size := dmemRespSize
  core.io.dmem.d.bits.source := dmemRespSource
  core.io.dmem.d.bits.sink := 0.U
  core.io.dmem.d.bits.denied := false.B
  core.io.dmem.d.bits.data := Mux(dmemRespIsRead, dmemRespData, 0.U)
  core.io.dmem.d.bits.corrupt := false.B

  mem.write(0).valid := dmemWriteFire
  mem.write(0).index := memIndex(core.io.dmem.a.bits.address)
  mem.write(0).data := VecInit(Seq(core.io.dmem.a.bits.data))
  mem.write(0).mask := VecInit(Seq(FillInterleaved(8, core.io.dmem.a.bits.mask)))
}

object WiredRV64S4TopMain extends App {
  implicit val p: CoreParams = CoreParams()
  ChiselStage.emitSystemVerilog(
    DifftestModule.top(new WiredRV64S4Top),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=build/rtl"
    )
  )
}

object WiredRV64S4BareTopMain extends App {
  implicit val p: CoreParams = CoreParams()
  ChiselStage.emitSystemVerilog(
    new WiredRV64S4Top,
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=build/rtl"
    )
  )
}
