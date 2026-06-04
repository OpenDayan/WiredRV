#include "riscv_test.h"
#include "test_macros.h"

RVTEST_RV64M
RVTEST_CODE_BEGIN

  la x1, tdat
  li t0, 0x8877665544332211

  sd t0, 0(x1)

  .word 0x0020A00F  // cbo.flush(x1)

  ld t1, 0(x1)
  bne t0, t1, fail

pass:
  RVTEST_PASS

fail:
  RVTEST_FAIL

RVTEST_CODE_END

.data
RVTEST_DATA_BEGIN
  .align 6
tdat:
  .dword 0x1122334455667788
  .fill 7,8,0
RVTEST_DATA_END
