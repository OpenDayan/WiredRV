#include "riscv_test.h"
#include "test_macros.h"

RVTEST_RV64U
RVTEST_CODE_BEGIN

  // Fill the four-entry multiply pipeline with independent operations.
  li TESTNUM, 1
  li t0, 2
  li t1, 3
  li t2, -4
  li t3, 5
  li t4, 0x12345678
  li t5, 0x100

  mul s1, t0, t1
  mul s2, t2, t3
  mul s3, t4, t5
  mul s4, t5, t5

  li t6, 6
  bne s1, t6, fail
  li t6, -20
  bne s2, t6, fail
  li t6, 0x1234567800
  bne s3, t6, fail
  li t6, 0x10000
  bne s4, t6, fail

  // Cover high-half signed/unsigned variants and MULW sign extension.
  li TESTNUM, 2
  li t0, -1
  li t1, 2
  mulh s1, t0, t1
  li t6, -1
  bne s1, t6, fail

  li TESTNUM, 3
  li t0, -2
  li t1, 3
  mulhsu s2, t0, t1
  li t6, -1
  bne s2, t6, fail

  li TESTNUM, 4
  li t0, -1
  li t1, 2
  mulhu s3, t0, t1
  li t6, 1
  bne s3, t6, fail

  li TESTNUM, 5
  li t0, -1
  li t1, 2
  mulw s4, t0, t1
  li t6, -2
  bne s4, t6, fail

  // A dependent consumer should wait for the long writeback result.
  li TESTNUM, 6
  li t0, 7
  li t1, 6
  mul s1, t0, t1
  addi s2, s1, 1
  li t6, 43
  bne s2, t6, fail

pass:
  RVTEST_PASS

fail:
  RVTEST_FAIL

RVTEST_CODE_END

.data
RVTEST_DATA_BEGIN
RVTEST_DATA_END
