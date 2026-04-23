package wiredrv.backend

import chisel3._
import chisel3.util._

object SrcType {
  val width = 3
  val none = 0.U(width.W)
  val reg = 1.U(width.W)
  val imm = 2.U(width.W)
  val pc = 3.U(width.W)
}

object FuType {
  val width = 4
  val none = 0.U(width.W)
  val alu = 1.U(width.W)
  val bru = 2.U(width.W)
  val jmp = 3.U(width.W)
  val lsu = 4.U(width.W)
  val csr = 5.U(width.W)
}

object ImmSel {
  val width = 4
  val none = 0.U(width.W)
  val i = 1.U(width.W)
  val s = 2.U(width.W)
  val b = 3.U(width.W)
  val u = 4.U(width.W)
  val j = 5.U(width.W)
  val z = 6.U(width.W)
}

object AluOp {
  val width = 5
  val add = 0.U(width.W)
  val sub = 1.U(width.W)
  val sll = 2.U(width.W)
  val slt = 3.U(width.W)
  val sltu = 4.U(width.W)
  val xor = 5.U(width.W)
  val srl = 6.U(width.W)
  val sra = 7.U(width.W)
  val or = 8.U(width.W)
  val and = 9.U(width.W)
  val copy1 = 10.U(width.W)
  val copy2 = 11.U(width.W)
  val addw = 12.U(width.W)
  val subw = 13.U(width.W)
  val sllw = 14.U(width.W)
  val srlw = 15.U(width.W)
  val sraw = 16.U(width.W)
}

object BruOp {
  val width = 4
  val none = 0.U(width.W)
  val beq = 1.U(width.W)
  val bne = 2.U(width.W)
  val blt = 3.U(width.W)
  val bge = 4.U(width.W)
  val bltu = 5.U(width.W)
  val bgeu = 6.U(width.W)
  val jal = 7.U(width.W)
  val jalr = 8.U(width.W)
}

object CsrOp {
  val width = 3
  val none = 0.U(width.W)
  val csrrw = 1.U(width.W)
  val csrrs = 2.U(width.W)
  val csrrc = 3.U(width.W)
  val ecall = 4.U(width.W)
}

object CacheMaintainOp {
  val width = 2
  val none = 0.U(width.W)
  val inval = 1.U(width.W)
  val clean = 2.U(width.W)
  val flush = 3.U(width.W)
}

object RV64IInstructions {
  val NEMU_TRAP = BitPat("b00000000000000000000000001101011")
  val LUI = BitPat("b?????????????????????????0110111")
  val AUIPC = BitPat("b?????????????????????????0010111")
  val JAL = BitPat("b?????????????????????????1101111")
  val JALR = BitPat("b?????????????????000?????1100111")
  val BEQ = BitPat("b?????????????????000?????1100011")
  val BNE = BitPat("b?????????????????001?????1100011")
  val BLT = BitPat("b?????????????????100?????1100011")
  val BGE = BitPat("b?????????????????101?????1100011")
  val BLTU = BitPat("b?????????????????110?????1100011")
  val BGEU = BitPat("b?????????????????111?????1100011")
  val LB = BitPat("b?????????????????000?????0000011")
  val LH = BitPat("b?????????????????001?????0000011")
  val LW = BitPat("b?????????????????010?????0000011")
  val LD = BitPat("b?????????????????011?????0000011")
  val LBU = BitPat("b?????????????????100?????0000011")
  val LHU = BitPat("b?????????????????101?????0000011")
  val LWU = BitPat("b?????????????????110?????0000011")
  val SB = BitPat("b?????????????????000?????0100011")
  val SH = BitPat("b?????????????????001?????0100011")
  val SW = BitPat("b?????????????????010?????0100011")
  val SD = BitPat("b?????????????????011?????0100011")
  val ADDI = BitPat("b?????????????????000?????0010011")
  val SLTI = BitPat("b?????????????????010?????0010011")
  val SLTIU = BitPat("b?????????????????011?????0010011")
  val XORI = BitPat("b?????????????????100?????0010011")
  val ORI = BitPat("b?????????????????110?????0010011")
  val ANDI = BitPat("b?????????????????111?????0010011")
  val SLLI = BitPat("b0000000??????????001?????0010011")
  val SRLI = BitPat("b0000000??????????101?????0010011")
  val SRAI = BitPat("b0100000??????????101?????0010011")
  val ADD = BitPat("b0000000??????????000?????0110011")
  val SUB = BitPat("b0100000??????????000?????0110011")
  val SLL = BitPat("b0000000??????????001?????0110011")
  val SLT = BitPat("b0000000??????????010?????0110011")
  val SLTU = BitPat("b0000000??????????011?????0110011")
  val XOR = BitPat("b0000000??????????100?????0110011")
  val SRL = BitPat("b0000000??????????101?????0110011")
  val SRA = BitPat("b0100000??????????101?????0110011")
  val OR = BitPat("b0000000??????????110?????0110011")
  val AND = BitPat("b0000000??????????111?????0110011")
  val ADDIW = BitPat("b?????????????????000?????0011011")
  val SLLIW = BitPat("b0000000??????????001?????0011011")
  val SRLIW = BitPat("b0000000??????????101?????0011011")
  val SRAIW = BitPat("b0100000??????????101?????0011011")
  val ADDW = BitPat("b0000000??????????000?????0111011")
  val SUBW = BitPat("b0100000??????????000?????0111011")
  val SLLW = BitPat("b0000000??????????001?????0111011")
  val SRLW = BitPat("b0000000??????????101?????0111011")
  val SRAW = BitPat("b0100000??????????101?????0111011")
  val FENCE = BitPat("b?????????????????000?????0001111")
  val FENCE_I = BitPat("b?????????????????001?????0001111")
  val CBO_INVAL = BitPat("b000000000000?????0100000000001111")
  val CBO_CLEAN = BitPat("b000000000001?????0100000000001111")
  val CBO_FLUSH = BitPat("b000000000010?????0100000000001111")
  val CSRRW = BitPat("b?????????????????001?????1110011")
  val CSRRS = BitPat("b?????????????????010?????1110011")
  val CSRRC = BitPat("b?????????????????011?????1110011")
  val CSRRWI = BitPat("b?????????????????101?????1110011")
  val CSRRSI = BitPat("b?????????????????110?????1110011")
  val CSRRCI = BitPat("b?????????????????111?????1110011")
  val ECALL = BitPat("b00000000000000000000000001110011")
  val EBREAK = BitPat("b00000000000100000000000001110011")
}

abstract class RVDecodeBase {
  def generate(): List[UInt]
}

case class RVDecode(
  src1: UInt = SrcType.none,
  src2: UInt = SrcType.none,
  fu: UInt = FuType.none,
  fuOp: UInt = 0.U(8.W),
  xWen: Boolean = false,
  readRd: Boolean = false,
  selImm: UInt = ImmSel.none,
  isLoad: Boolean = false,
  isStore: Boolean = false,
  isCacheMaintain: Boolean = false,
  cacheMaintainOp: UInt = CacheMaintainOp.none,
  isBranch: Boolean = false,
  isJump: Boolean = false,
  isCsr: Boolean = false,
  isFence: Boolean = false,
  flushPipe: Boolean = false,
  illegal: Boolean = false
) extends RVDecodeBase {
  def generate(): List[UInt] = List(
    src1,
    src2,
    fu,
    fuOp,
    xWen.B,
    readRd.B,
    true.B,
    false.B,
    selImm,
    isLoad.B,
    isStore.B,
    isCacheMaintain.B,
    cacheMaintainOp,
    isBranch.B,
    isJump.B,
    isCsr.B,
    false.B,
    false.B,
    false.B,
    isFence.B,
    flushPipe.B,
    illegal.B
  )
}

trait DecodeConstants {
  def decodeDefault: List[UInt] = RVDecode(illegal = true).generate()
  val table: Array[(BitPat, List[UInt])]
}

object RV64IDecodeTable extends DecodeConstants {
  import RV64IInstructions._

  val table: Array[(BitPat, List[UInt])] = Array(
    NEMU_TRAP -> RVDecode().generate(),
    LUI    -> RVDecode(src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.copy2, xWen = true, selImm = ImmSel.u).generate(),
    AUIPC  -> RVDecode(src1 = SrcType.pc, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.add, xWen = true, selImm = ImmSel.u).generate(),
    JAL    -> RVDecode(src1 = SrcType.pc, src2 = SrcType.imm, fu = FuType.jmp, fuOp = BruOp.jal, xWen = true, selImm = ImmSel.j, isJump = true).generate(),
    JALR   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.jmp, fuOp = BruOp.jalr, xWen = true, selImm = ImmSel.i, isJump = true).generate(),
    BEQ    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.bru, fuOp = BruOp.beq, selImm = ImmSel.b, isBranch = true).generate(),
    BNE    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.bru, fuOp = BruOp.bne, selImm = ImmSel.b, isBranch = true).generate(),
    BLT    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.bru, fuOp = BruOp.blt, selImm = ImmSel.b, isBranch = true).generate(),
    BGE    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.bru, fuOp = BruOp.bge, selImm = ImmSel.b, isBranch = true).generate(),
    BLTU   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.bru, fuOp = BruOp.bltu, selImm = ImmSel.b, isBranch = true).generate(),
    BGEU   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.bru, fuOp = BruOp.bgeu, selImm = ImmSel.b, isBranch = true).generate(),
    LB     -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.lsu, fuOp = "b000".U, xWen = true, selImm = ImmSel.i, isLoad = true).generate(),
    LH     -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.lsu, fuOp = "b001".U, xWen = true, selImm = ImmSel.i, isLoad = true).generate(),
    LW     -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.lsu, fuOp = "b010".U, xWen = true, selImm = ImmSel.i, isLoad = true).generate(),
    LD     -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.lsu, fuOp = "b011".U, xWen = true, selImm = ImmSel.i, isLoad = true).generate(),
    LBU    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.lsu, fuOp = "b100".U, xWen = true, selImm = ImmSel.i, isLoad = true).generate(),
    LHU    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.lsu, fuOp = "b101".U, xWen = true, selImm = ImmSel.i, isLoad = true).generate(),
    LWU    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.lsu, fuOp = "b110".U, xWen = true, selImm = ImmSel.i, isLoad = true).generate(),
    SB     -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.lsu, fuOp = "b000".U, selImm = ImmSel.s, isStore = true).generate(),
    SH     -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.lsu, fuOp = "b001".U, selImm = ImmSel.s, isStore = true).generate(),
    SW     -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.lsu, fuOp = "b010".U, selImm = ImmSel.s, isStore = true).generate(),
    SD     -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.lsu, fuOp = "b011".U, selImm = ImmSel.s, isStore = true).generate(),
    ADDI   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.add, xWen = true, selImm = ImmSel.i).generate(),
    SLTI   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.slt, xWen = true, selImm = ImmSel.i).generate(),
    SLTIU  -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.sltu, xWen = true, selImm = ImmSel.i).generate(),
    XORI   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.xor, xWen = true, selImm = ImmSel.i).generate(),
    ORI    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.or, xWen = true, selImm = ImmSel.i).generate(),
    ANDI   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.and, xWen = true, selImm = ImmSel.i).generate(),
    SLLI   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.sll, xWen = true, selImm = ImmSel.i).generate(),
    SRLI   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.srl, xWen = true, selImm = ImmSel.i).generate(),
    SRAI   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.sra, xWen = true, selImm = ImmSel.i).generate(),
    ADD    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.add, xWen = true).generate(),
    SUB    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.sub, xWen = true).generate(),
    SLL    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.sll, xWen = true).generate(),
    SLT    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.slt, xWen = true).generate(),
    SLTU   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.sltu, xWen = true).generate(),
    XOR    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.xor, xWen = true).generate(),
    SRL    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.srl, xWen = true).generate(),
    SRA    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.sra, xWen = true).generate(),
    OR     -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.or, xWen = true).generate(),
    AND    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.and, xWen = true).generate(),
    ADDIW  -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.addw, xWen = true, selImm = ImmSel.i).generate(),
    SLLIW  -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.sllw, xWen = true, selImm = ImmSel.i).generate(),
    SRLIW  -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.srlw, xWen = true, selImm = ImmSel.i).generate(),
    SRAIW  -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.sraw, xWen = true, selImm = ImmSel.i).generate(),
    ADDW   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.addw, xWen = true).generate(),
    SUBW   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.subw, xWen = true).generate(),
    SLLW   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.sllw, xWen = true).generate(),
    SRLW   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.srlw, xWen = true).generate(),
    SRAW   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.sraw, xWen = true).generate(),
    FENCE  -> RVDecode(isFence = true).generate(),
    FENCE_I -> RVDecode(isFence = true, flushPipe = true).generate(),
    CBO_INVAL -> RVDecode(src1 = SrcType.reg, fu = FuType.lsu, isCacheMaintain = true, cacheMaintainOp = CacheMaintainOp.inval).generate(),
    CBO_CLEAN -> RVDecode(src1 = SrcType.reg, fu = FuType.lsu, isCacheMaintain = true, cacheMaintainOp = CacheMaintainOp.clean).generate(),
    CBO_FLUSH -> RVDecode(src1 = SrcType.reg, fu = FuType.lsu, isCacheMaintain = true, cacheMaintainOp = CacheMaintainOp.flush).generate(),
    CSRRW  -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.csr, fuOp = CsrOp.csrrw, xWen = true, isCsr = true).generate(),
    CSRRS  -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.csr, fuOp = CsrOp.csrrs, xWen = true, isCsr = true).generate(),
    CSRRC  -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.csr, fuOp = CsrOp.csrrc, xWen = true, isCsr = true).generate(),
    CSRRWI -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.csr, fuOp = CsrOp.csrrw, xWen = true, selImm = ImmSel.z, isCsr = true).generate(),
    CSRRSI -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.csr, fuOp = CsrOp.csrrs, xWen = true, selImm = ImmSel.z, isCsr = true).generate(),
    CSRRCI -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.csr, fuOp = CsrOp.csrrc, xWen = true, selImm = ImmSel.z, isCsr = true).generate(),
    ECALL  -> RVDecode(fu = FuType.csr, fuOp = CsrOp.ecall, isCsr = true, flushPipe = true).generate(),
    EBREAK -> RVDecode(fu = FuType.csr, fuOp = CsrOp.ecall, isCsr = true, flushPipe = true).generate()
  )
}
