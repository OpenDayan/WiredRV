#include "riscv_test.h"
#include "test_macros.h"

RVTEST_RV64U
RVTEST_CODE_BEGIN

  li t0, 0x80001000

  li t1, 0x12345678
  sw t1, 0(t0)
  lw t2, 0(t0)
  bne t1, t2, fail

  li t1, 0x55
  sb t1, 8(t0)
  lbu t2, 8(t0)
  bne t1, t2, fail

  li t1, 0x6677
  sh t1, 16(t0)
  lhu t2, 16(t0)
  bne t1, t2, fail

pass:
  RVTEST_PASS

fail:
  RVTEST_FAIL

RVTEST_CODE_END

.data
RVTEST_DATA_BEGIN
RVTEST_DATA_END
