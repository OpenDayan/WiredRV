OUTPUT_ARCH("riscv")
ENTRY(_start)

SECTIONS
{
  . = 0x80000000;

  .text.init : { *(.text.init) }
  . = 0x80002000;
  .text : { *(.text*) }

  . = ALIGN(0x1000);
  .rodata : { *(.rodata*) }

  . = ALIGN(0x1000);
  .data : { *(.sdata*) *(.data*) }

  . = ALIGN(0x1000);
  .bss : {
    *(.sbss*)
    *(.bss*)
    *(COMMON)
  }

  . = ALIGN(0x1000);
  _end = .;
  _stack_top = . + 0x4000;
}
