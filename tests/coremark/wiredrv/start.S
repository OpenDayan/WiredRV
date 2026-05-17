  .section .text.init
  .globl _start
  .align 6

_start:
  .option push
  .option norvc
  la sp, _stack_top
  call main

  li a0, 0
  fence
  .word 0x0000006b

1:
  j 1b
  .option pop
