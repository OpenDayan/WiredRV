OUTPUT_ARCH("riscv")
ENTRY(_start)

SECTIONS
{
  . = 0x80000000;
  .text.init : { *(.text.init) }
  . = ALIGN(0x1000);
  .text : { *(.text) }
  . = ALIGN(0x1000);
  .rodata : { *(.rodata*) }
  . = ALIGN(0x1000);
  .data : { *(.data) }
  .bss : { *(.bss) *(COMMON) }
  . = ALIGN(0x1000);
  _stack_top = . + 0x1000;
  _end = .;
}
