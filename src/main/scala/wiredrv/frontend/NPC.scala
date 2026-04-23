package wiredrv.frontend

import chisel3._
import chisel3.util._
import wiredrv.CoreParams

class NPC(implicit p: CoreParams) extends Module {
    val io = IO(new Bundle {
        val redirects = Input(Vec(4, Valid(UInt(p.addrBits.W))))
        val pc = Decoupled(UInt(p.addrBits.W))
    })

    // 处理器 F0 级的 PC 地址
    val f0pc = RegInit(p.resetPc.U(p.addrBits.W))

    // 跳转处理后的 PC 逻辑
    val fwdPc = Wire(UInt(p.addrBits.W))
    fwdPc := f0pc
    for(i <- 3 to 0 by -1) {
        when(io.redirects(i).valid) {
            fwdPc := io.redirects(i).bits
        }
    }

    io.pc.valid := 1.B
    io.pc.bits := fwdPc

    // 只考虑跳转的 pc
    val jmpPc = Wire(UInt(p.addrBits.W))
    jmpPc := io.redirects(3).bits
    for(i <- 2 to 0 by -1) {
        when(io.redirects(i).valid) {
            jmpPc := io.redirects(i).bits
        }
    }

    val anyRedir = VecInit(io.redirects.map(_.valid)).asUInt.orR
    when(io.pc.ready) {
        f0pc := fwdPc + 4.U
    }.elsewhen(anyRedir) {
        f0pc := jmpPc
    }
}