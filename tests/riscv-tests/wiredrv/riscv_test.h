// Minimal riscv-tests environment for WiredRV baremetal difftest.

#ifndef _WIREDRV_RISCV_TEST_H
#define _WIREDRV_RISCV_TEST_H

#include "encoding.h"

#define RVTEST_RV64U \
  .macro init; \
  .endm

#define RVTEST_RV32U \
  .macro init; \
  .endm

#define WIREDRV_INIT_MTVEC \
  la t0, mtvec_handler; \
  beqz t0, 9f; \
  csrw mtvec, t0; \
9:

#define RVTEST_RV64M \
  .macro init; \
  WIREDRV_INIT_MTVEC; \
  .endm

#define RVTEST_RV64S RVTEST_RV64M
#define RVTEST_RV32M RVTEST_RV32U
#define RVTEST_RV32S RVTEST_RV32U

#define TESTNUM gp

#define CHECK_XLEN

#define INIT_XREG \
  li x1, 0; \
  li x2, 0; \
  li x3, 0; \
  li x4, 0; \
  li x5, 0; \
  li x6, 0; \
  li x7, 0; \
  li x8, 0; \
  li x9, 0; \
  li x10, 0; \
  li x11, 0; \
  li x12, 0; \
  li x13, 0; \
  li x14, 0; \
  li x15, 0; \
  li x16, 0; \
  li x17, 0; \
  li x18, 0; \
  li x19, 0; \
  li x20, 0; \
  li x21, 0; \
  li x22, 0; \
  li x23, 0; \
  li x24, 0; \
  li x25, 0; \
  li x26, 0; \
  li x27, 0; \
  li x28, 0; \
  li x29, 0; \
  li x30, 0; \
  li x31, 0

#define RVTEST_CODE_BEGIN \
  .section .text.init; \
  .align 6; \
  .weak mtvec_handler; \
  .globl _start; \
_start: \
  .option push; \
  .option norvc; \
  INIT_XREG; \
  la sp, _stack_top; \
  li TESTNUM, 0; \
  CHECK_XLEN; \
  init; \
  .option pop

#define RVTEST_CODE_END \
  unimp

#define RVTEST_PASS \
  fence; \
  li TESTNUM, 1; \
  li a0, 0; \
  .word 0x0000006b

#define RVTEST_FAIL \
  fence; \
1: \
  beqz TESTNUM, 1b; \
  sll TESTNUM, TESTNUM, 1; \
  or TESTNUM, TESTNUM, 1; \
  addi a0, TESTNUM, 0; \
  .word 0x0000006b

#define EXTRA_DATA

#define RVTEST_DATA_BEGIN \
  EXTRA_DATA; \
  .align 4; \
  .global begin_signature; \
begin_signature:

#define RVTEST_DATA_END \
  .align 4; \
  .global end_signature; \
end_signature:

#endif
