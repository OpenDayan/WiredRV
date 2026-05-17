#include "riscv_test.h"
#include "test_macros.h"

RVTEST_RV64U
RVTEST_CODE_BEGIN

  li t0, 0x80001ff0

  li t1, 'U'
  sb t1, 0(t0)
  li t1, 'A'
  sb t1, 0(t0)
  li t1, 'R'
  sb t1, 0(t0)
  li t1, 'T'
  sb t1, 0(t0)
  li t1, ' '
  sb t1, 0(t0)
  li t1, 'O'
  sb t1, 0(t0)
  li t1, 'K'
  sb t1, 0(t0)
  li t1, 10
  sb t1, 0(t0)

pass:
  RVTEST_PASS

fail:
  RVTEST_FAIL

RVTEST_CODE_END

.data
RVTEST_DATA_BEGIN
RVTEST_DATA_END
