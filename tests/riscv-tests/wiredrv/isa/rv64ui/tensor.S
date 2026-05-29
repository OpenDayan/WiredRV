#include "riscv_test.h"
#include "test_macros.h"

RVTEST_RV64U
RVTEST_CODE_BEGIN

  li TESTNUM, 1

  li t0, 0x11111111
  li t1, 0x22222222
  li t2, 0x33333333

  // custom-0 R-type: rd=t2, rs1=t0, rs2=t1, funct3=0, funct7=0.
  // The default harness Tensor stub returns data=0 to rd.
  .word 0x0062838b

  bnez t2, fail

  li TESTNUM, 2
  addi t3, t2, 5
  li t4, 5
  bne t3, t4, fail

pass:
  RVTEST_PASS

fail:
  RVTEST_FAIL

RVTEST_CODE_END

.data
RVTEST_DATA_BEGIN
RVTEST_DATA_END
