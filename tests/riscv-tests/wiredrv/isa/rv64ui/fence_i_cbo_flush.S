#include "riscv_test.h"
#include "test_macros.h"

RVTEST_RV64U
RVTEST_CODE_BEGIN

  li a3, 111
  lh a0, insn
  lh a1, insn+2

  # Prime target1 into I-cache with the old instruction.
  li TESTNUM, 1
  la a5, target1
  jalr t1, a5, 0
  li t0, 333
  bne a3, t0, fail

  # Patch target1, clean/invalidate the D-cache line, then flush I-cache.
  li a3, 111
  sh a0, target1, t0
  sh a1, target1+2, t0
  la x1, target1
  .word 0x0020A00F  # cbo.flush(x1)
  .word 0x0000100F  # fence.i

  li TESTNUM, 2
  la a5, target1
  jalr t1, a5, 0
  li t0, 444
  bne a3, t0, fail

  # Repeat on a second cache line to cover consecutive self-modifying sites.
  li a3, 111
  li TESTNUM, 3
  la a5, target2
  jalr t1, a5, 0
  li t0, 666
  bne a3, t0, fail

  li a3, 111
  sh a0, target2, t0
  sh a1, target2+2, t0
  la x1, target2
  .word 0x0020A00F  # cbo.flush(x1)
  .word 0x0000100F  # fence.i

  li TESTNUM, 4
  la a5, target2
  jalr t1, a5, 0
  li t0, 444
  bne a3, t0, fail

pass:
  RVTEST_PASS

fail:
  RVTEST_FAIL

RVTEST_CODE_END

.data
RVTEST_DATA_BEGIN

  .align 6
insn:
  addi a3, a3, 333

  .align 6
target1:
  addi a3, a3, 222
  jalr a5, t1, 0
  .fill 6,8,0

  .align 6
target2:
  addi a3, a3, 555
  jalr a5, t1, 0
  .fill 6,8,0

RVTEST_DATA_END
