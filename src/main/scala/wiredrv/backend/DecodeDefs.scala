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
  val mdu = 6.U(width.W)
  val tensor = 7.U(width.W)
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
  val mret = 5.U(width.W)
}

object MduOp {
  val width = 4
  val mul = 0.U(width.W)
  val mulh = 1.U(width.W)
  val mulhsu = 2.U(width.W)
  val mulhu = 3.U(width.W)
  val div = 4.U(width.W)
  val divu = 5.U(width.W)
  val rem = 6.U(width.W)
  val remu = 7.U(width.W)
  val mulw = 8.U(width.W)
  val divw = 9.U(width.W)
  val divuw = 10.U(width.W)
  val remw = 11.U(width.W)
  val remuw = 12.U(width.W)
}

object LSUOp {
  val width = 8
  val none = "h00".U(width.W)
  val lb = "h01".U(width.W)
  val lh = "h02".U(width.W)
  val lw = "h03".U(width.W)
  val ld = "h04".U(width.W)
  val lbu = "h05".U(width.W)
  val lhu = "h06".U(width.W)
  val lwu = "h07".U(width.W)
  val sb = "h08".U(width.W)
  val sh = "h09".U(width.W)
  val sw = "h0a".U(width.W)
  val sd = "h0b".U(width.W)
  val cboInval = "h10".U(width.W)
  val cboClean = "h11".U(width.W)
  val cboFlush = "h12".U(width.W)

  def isStore(op: UInt): Bool =
    op === sb || op === sh || op === sw || op === sd
}

object RV64IInstructions {
  val CUSTOM0 = BitPat("b?????????????????????????0001011")
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
  val SLLI = BitPat("b000000???????????001?????0010011")
  val SRLI = BitPat("b000000???????????101?????0010011")
  val SRAI = BitPat("b010000???????????101?????0010011")
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
  val MRET = BitPat("b00110000001000000000000001110011")
  val WFI = BitPat("b00010000010100000000000001110011")
}

object RV64MInstructions {
  val MUL = BitPat("b0000001??????????000?????0110011")
  val MULH = BitPat("b0000001??????????001?????0110011")
  val MULHSU = BitPat("b0000001??????????010?????0110011")
  val MULHU = BitPat("b0000001??????????011?????0110011")
  val DIV = BitPat("b0000001??????????100?????0110011")
  val DIVU = BitPat("b0000001??????????101?????0110011")
  val REM = BitPat("b0000001??????????110?????0110011")
  val REMU = BitPat("b0000001??????????111?????0110011")
  val MULW = BitPat("b0000001??????????000?????0111011")
  val DIVW = BitPat("b0000001??????????100?????0111011")
  val DIVUW = BitPat("b0000001??????????101?????0111011")
  val REMW = BitPat("b0000001??????????110?????0111011")
  val REMUW = BitPat("b0000001??????????111?????0111011")
}

abstract class RVDecodeBase {
  def generate(): List[UInt]
}

case class RVDecode(
  src1: UInt = SrcType.none,
  src2: UInt = SrcType.none,
  fu: UInt = FuType.none,
  fuOp: UInt = 0.U(LSUOp.width.W),
  rwen: Boolean = false,
  selImm: UInt = ImmSel.none,
  flushPipe: Boolean = false,
  illegal: Boolean = false
) extends RVDecodeBase {
  def generate(): List[UInt] = List(
    src1,
    src2,
    fu,
    fuOp,
    rwen.B,
    selImm,
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
  import RV64MInstructions._

  val table: Array[(BitPat, List[UInt])] = Array(
    CUSTOM0 -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.tensor, rwen = true).generate(),
    NEMU_TRAP -> RVDecode().generate(),
    LUI    -> RVDecode(src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.copy2, rwen = true, selImm = ImmSel.u).generate(),
    AUIPC  -> RVDecode(src1 = SrcType.pc, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.add, rwen = true, selImm = ImmSel.u).generate(),
    JAL    -> RVDecode(src1 = SrcType.pc, src2 = SrcType.imm, fu = FuType.jmp, fuOp = BruOp.jal, rwen = true, selImm = ImmSel.j).generate(),
    JALR   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.jmp, fuOp = BruOp.jalr, rwen = true, selImm = ImmSel.i).generate(),
    BEQ    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.bru, fuOp = BruOp.beq, selImm = ImmSel.b).generate(),
    BNE    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.bru, fuOp = BruOp.bne, selImm = ImmSel.b).generate(),
    BLT    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.bru, fuOp = BruOp.blt, selImm = ImmSel.b).generate(),
    BGE    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.bru, fuOp = BruOp.bge, selImm = ImmSel.b).generate(),
    BLTU   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.bru, fuOp = BruOp.bltu, selImm = ImmSel.b).generate(),
    BGEU   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.bru, fuOp = BruOp.bgeu, selImm = ImmSel.b).generate(),
    LB     -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.lsu, fuOp = LSUOp.lb, rwen = true, selImm = ImmSel.i).generate(),
    LH     -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.lsu, fuOp = LSUOp.lh, rwen = true, selImm = ImmSel.i).generate(),
    LW     -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.lsu, fuOp = LSUOp.lw, rwen = true, selImm = ImmSel.i).generate(),
    LD     -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.lsu, fuOp = LSUOp.ld, rwen = true, selImm = ImmSel.i).generate(),
    LBU    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.lsu, fuOp = LSUOp.lbu, rwen = true, selImm = ImmSel.i).generate(),
    LHU    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.lsu, fuOp = LSUOp.lhu, rwen = true, selImm = ImmSel.i).generate(),
    LWU    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.lsu, fuOp = LSUOp.lwu, rwen = true, selImm = ImmSel.i).generate(),
    SB     -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.lsu, fuOp = LSUOp.sb, selImm = ImmSel.s).generate(),
    SH     -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.lsu, fuOp = LSUOp.sh, selImm = ImmSel.s).generate(),
    SW     -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.lsu, fuOp = LSUOp.sw, selImm = ImmSel.s).generate(),
    SD     -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.lsu, fuOp = LSUOp.sd, selImm = ImmSel.s).generate(),
    ADDI   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.add, rwen = true, selImm = ImmSel.i).generate(),
    SLTI   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.slt, rwen = true, selImm = ImmSel.i).generate(),
    SLTIU  -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.sltu, rwen = true, selImm = ImmSel.i).generate(),
    XORI   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.xor, rwen = true, selImm = ImmSel.i).generate(),
    ORI    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.or, rwen = true, selImm = ImmSel.i).generate(),
    ANDI   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.and, rwen = true, selImm = ImmSel.i).generate(),
    SLLI   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.sll, rwen = true, selImm = ImmSel.i).generate(),
    SRLI   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.srl, rwen = true, selImm = ImmSel.i).generate(),
    SRAI   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.sra, rwen = true, selImm = ImmSel.i).generate(),
    ADD    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.add, rwen = true).generate(),
    SUB    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.sub, rwen = true).generate(),
    SLL    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.sll, rwen = true).generate(),
    SLT    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.slt, rwen = true).generate(),
    SLTU   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.sltu, rwen = true).generate(),
    XOR    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.xor, rwen = true).generate(),
    SRL    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.srl, rwen = true).generate(),
    SRA    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.sra, rwen = true).generate(),
    OR     -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.or, rwen = true).generate(),
    AND    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.and, rwen = true).generate(),
    ADDIW  -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.addw, rwen = true, selImm = ImmSel.i).generate(),
    SLLIW  -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.sllw, rwen = true, selImm = ImmSel.i).generate(),
    SRLIW  -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.srlw, rwen = true, selImm = ImmSel.i).generate(),
    SRAIW  -> RVDecode(src1 = SrcType.reg, src2 = SrcType.imm, fu = FuType.alu, fuOp = AluOp.sraw, rwen = true, selImm = ImmSel.i).generate(),
    ADDW   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.addw, rwen = true).generate(),
    SUBW   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.subw, rwen = true).generate(),
    SLLW   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.sllw, rwen = true).generate(),
    SRLW   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.srlw, rwen = true).generate(),
    SRAW   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.alu, fuOp = AluOp.sraw, rwen = true).generate(),
    MUL    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.mdu, fuOp = MduOp.mul, rwen = true).generate(),
    MULH   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.mdu, fuOp = MduOp.mulh, rwen = true).generate(),
    MULHSU -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.mdu, fuOp = MduOp.mulhsu, rwen = true).generate(),
    MULHU  -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.mdu, fuOp = MduOp.mulhu, rwen = true).generate(),
    DIV    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.mdu, fuOp = MduOp.div, rwen = true).generate(),
    DIVU   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.mdu, fuOp = MduOp.divu, rwen = true).generate(),
    REM    -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.mdu, fuOp = MduOp.rem, rwen = true).generate(),
    REMU   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.mdu, fuOp = MduOp.remu, rwen = true).generate(),
    MULW   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.mdu, fuOp = MduOp.mulw, rwen = true).generate(),
    DIVW   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.mdu, fuOp = MduOp.divw, rwen = true).generate(),
    DIVUW  -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.mdu, fuOp = MduOp.divuw, rwen = true).generate(),
    REMW   -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.mdu, fuOp = MduOp.remw, rwen = true).generate(),
    REMUW  -> RVDecode(src1 = SrcType.reg, src2 = SrcType.reg, fu = FuType.mdu, fuOp = MduOp.remuw, rwen = true).generate(),
    FENCE  -> RVDecode().generate(),
    FENCE_I -> RVDecode(flushPipe = true).generate(),
    CBO_INVAL -> RVDecode(src1 = SrcType.reg, fu = FuType.lsu, fuOp = LSUOp.cboInval).generate(),
    CBO_CLEAN -> RVDecode(src1 = SrcType.reg, fu = FuType.lsu, fuOp = LSUOp.cboClean).generate(),
    CBO_FLUSH -> RVDecode(src1 = SrcType.reg, fu = FuType.lsu, fuOp = LSUOp.cboFlush).generate(),
    CSRRW  -> RVDecode(src1 = SrcType.reg, fu = FuType.csr, fuOp = CsrOp.csrrw, rwen = true).generate(),
    CSRRS  -> RVDecode(src1 = SrcType.reg, fu = FuType.csr, fuOp = CsrOp.csrrs, rwen = true).generate(),
    CSRRC  -> RVDecode(src1 = SrcType.reg, fu = FuType.csr, fuOp = CsrOp.csrrc, rwen = true).generate(),
    CSRRWI -> RVDecode(src2 = SrcType.imm, fu = FuType.csr, fuOp = CsrOp.csrrw, rwen = true, selImm = ImmSel.z).generate(),
    CSRRSI -> RVDecode(src2 = SrcType.imm, fu = FuType.csr, fuOp = CsrOp.csrrs, rwen = true, selImm = ImmSel.z).generate(),
    CSRRCI -> RVDecode(src2 = SrcType.imm, fu = FuType.csr, fuOp = CsrOp.csrrc, rwen = true, selImm = ImmSel.z).generate(),
    ECALL  -> RVDecode(fu = FuType.csr, fuOp = CsrOp.ecall, flushPipe = true).generate(),
    EBREAK -> RVDecode(fu = FuType.csr, fuOp = CsrOp.ecall, flushPipe = true).generate(),
    MRET   -> RVDecode(fu = FuType.csr, fuOp = CsrOp.mret, flushPipe = true).generate(),
    WFI    -> RVDecode().generate()
  )
}
