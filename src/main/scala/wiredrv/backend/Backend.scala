package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.CoreParams
import wiredrv.bus._
import wiredrv.frontend._
import xs.utils.sram.SRAMTemplate
import freechips.rocketchip.util._

class Backend(implicit p: CoreParams) extends Module {

    val io = IO(new Bundle {
        val inst = Flipped(Decoupled(new FetchPacket))
        val redirects = Output(Vec(3, Valid(UInt(p.addrBits.W))))
        val ifencei = Output(Bool())
    })

    // RF
    val rf = Reg(Vec(32, UInt(p.xlen.W)))
    
    // Write RF Port
    val w_rfen = Wire(Bool())
    val w_rfdata = Wire(UInt(p.xlen.W))
    val v_rfen = Wire(Bool())
    val v_rf   = Wire(UInt(5.W))
    val v_rfdata = Wire(UInt(p.xlen.W))

    // Fixed-Latency Scoreboard
    val eGpr = Reg(UInt(5.W))
    val wGpr = Reg(UInt(5.W))

    // Var-Latency Scoreboard
    val vGpr = Reg(Vec(p.longLatencySlots, UInt(5.W)))
    val vAllocator = Module(new SBAllocator)

    // D Stage
    val decoder = Module(new Decode).io
    decoder.inst := io.inst.bits.inst
    
    val d_dec = decoder.out

    // RF Read
    val d_rs1 = Mux(d_dec.rs1 === wGpr, w_rfdata, rf(d_dec.rs1))
    val d_rs2 = Mux(d_dec.rs2 === wGpr, w_rfdata, rf(d_dec.rs1))
    val d_rd  = Mux(d_dec.rd  === wGpr, w_rfdata, rf(d_dec.rs1))

    // RAW Hazard
    val d_rs1_e_haz = d_dec.rs1 === eGpr
    val d_rs1_v_haz = vGpr.map(d_dec.rs1 === _).orR
    val d_rs2_e_haz = d_dec.rs2 === eGpr
    val d_rs2_v_haz = vGpr.map(d_dec.rs2 === _).orR
    val d_rd_e_haz = d_dec.rd === eGpr

    // WAW / RAW Hazard
    val d_rd_v_haz = vGpr.map(d_dec.rs2 === _).orR

    // Hazard Stall
    val d_haz_stall = (d_dec.info.rs1en && (d_rs1_v_haz || (d_rs1_e_haz && d_dec.info.isLsu)))      ||
                      (d_dec.info.rs2en && (d_rs2_v_haz/* || (d_rs2_e_haz && d_dec.info.isLsu) */)) || // 对于 LSU 指令来说， rs2 可以在 E 级转发
                      (d_dec.info.rden  && (d_rd_v_haz/* || (d_rd_e_haz && d_dec.info.isLsu) */))   || // 对于需要读取 rd 的指令，其实就是 RVT 拓展指令和 CAS 拓展指令，都可以在 E 级转发
                      (d_dec.info.rwen  && (d_rd_v_haz))                                               // WAW 防止乱序
}
