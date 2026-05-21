#include "riscv_test.h"
#include "test_macros.h"

#ifndef EXPECTED_INTERRUPT_CAUSE
#define EXPECTED_INTERRUPT_CAUSE 11
#endif

RVTEST_RV64U
RVTEST_CODE_BEGIN

  la t0, trap_handler
  csrw mtvec, t0

  li t0, 0
  la t1, trap_seen
  sd t0, 0(t1)

  li t0, (1 << EXPECTED_INTERRUPT_CAUSE)
  csrw mie, t0
  li t0, (1 << 3)
  csrs mstatus, t0

  li s0, 0
wait_interrupt:
  la t0, trap_seen
  ld t1, 0(t0)
  bnez t1, pass
  addi s0, s0, 1
  li t2, 4096
  blt s0, t2, wait_interrupt

  li TESTNUM, 2
  j fail

trap_handler:
  csrr t0, mcause
  li t1, 1
  slli t1, t1, 63
  li t2, EXPECTED_INTERRUPT_CAUSE
  or t1, t1, t2
  bne t0, t1, trap_fail_mcause

  csrw mie, zero
  li t3, 1
  la t4, trap_seen
  sd t3, 0(t4)
  mret

trap_fail_mcause:
  li TESTNUM, 3
  j fail

pass:
  RVTEST_PASS

fail:
  RVTEST_FAIL

RVTEST_CODE_END

.data
RVTEST_DATA_BEGIN
  .align 3
trap_seen:
  .dword 0
RVTEST_DATA_END
