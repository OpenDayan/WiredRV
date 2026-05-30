#include "riscv_test.h"
#include "test_macros.h"

RVTEST_RV64U
RVTEST_CODE_BEGIN

  li TESTNUM, 1

  li t0, 0x0123456789abcdef
  li t1, 0xfedcba9876543210
  li t2, 0x0badc0ffee0ddf00

  // Directed 32-bit tensor issue: rd=t2, rs1=t0, rs2=t1.
  // The harness stub asserts the issued payload and returns a signature.
  .word 0x2462838b

  li t4, 0x1122334455667788
  bne t2, t4, fail

  li TESTNUM, 2
  addi t3, t2, 1
  li t4, 0x1122334455667789
  bne t3, t4, fail

  li TESTNUM, 3

  li t0, 0x13579bdf2468ace0
  li t1, 0xf0e1d2c3b4a59687
  li t2, 0xdeadbeefcafebabe

  // Directed 64-bit tensor issue: low half has opcode=0111111.
  // The high half is jal x0, 0 and must be treated as tensor payload data.
  .word 0x266283bf
  .word 0x0000006f

  li t4, 0x8877665544332211
  bne t2, t4, fail

  li TESTNUM, 4
  addi t3, t2, 1
  li t4, 0x8877665544332212
  bne t3, t4, fail

pass:
  RVTEST_PASS

fail:
  RVTEST_FAIL

RVTEST_CODE_END

.data
RVTEST_DATA_BEGIN
RVTEST_DATA_END
