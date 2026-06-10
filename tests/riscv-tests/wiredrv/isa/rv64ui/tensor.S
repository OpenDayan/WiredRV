#include "riscv_test.h"
#include "test_macros.h"

RVTEST_RV64U
RVTEST_CODE_BEGIN

  li TESTNUM, 1

  li t0, 0x11111111
  li t1, 0x22222222
  li t2, 0x33333333

  // 32-bit tensor issue: rd is a source pointer/config value, not a GPR
  // writeback destination.
  .word 0x0062838b

  li t4, 0x33333333
  bne t2, t4, fail

  li TESTNUM, 2
  li t2, 0x33333333

  // 64-bit tensor: low half has opcode=0111111, rd=t2, rs1=t0, rs2=t1.
  // The high half is jal x0, 0; it must be treated as data, not fetched as an
  // independent instruction. Plain 64-bit async tensor instructions do not
  // write back through TensorResp.
  .word 0x006283bf
  .word 0x0000006f

  li t4, 0x33333333
  bne t2, t4, fail

  li TESTNUM, 3
  addi t3, t2, 5
  li t4, 0x33333338
  bne t3, t4, fail

pass:
  RVTEST_PASS

fail:
  RVTEST_FAIL

RVTEST_CODE_END

.data
RVTEST_DATA_BEGIN
RVTEST_DATA_END
