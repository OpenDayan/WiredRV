
.PHONY: sim-verilog emu clean nemu-ref riscv-test-workload emu-riscv-test difftest-riscv-test

NOOP_HOME ?= $(abspath .)
export NOOP_HOME
NEMU_HOME ?= /workspace/NEMU
export NEMU_HOME
NEMU_STATIC_REF ?= $(NEMU_HOME)/build/libriscv64-nemu-interpreter.a
RISCV_PREFIX ?= /public/riscv-tools/rv64ima/bin/riscv64-unknown-elf
RISCV_GCC ?= $(RISCV_PREFIX)-gcc
RISCV_TESTS_HOME ?= $(NOOP_HOME)/third_party/riscv-tests
RISCV_TEST_ENV ?= $(NOOP_HOME)/tests/riscv-tests/wiredrv
ISA_SUITE ?= rv64ui
TEST ?= simple
EMU_MAX_INSTR ?= 200000
WORKLOAD_ELF ?= $(NOOP_HOME)/build/workloads/$(ISA_SUITE)-$(TEST).elf

sim-verilog:
	mill 'wiredrv[6.6.0].runMain' wiredrv.top.WiredRV64S4TopMain

emu: sim-verilog
	@$(MAKE) -C difftest emu \
		WITH_CHISELDB=$(WITH_CHISELDB) \
		WITH_CONSTANTIN=$(WITH_CONSTANTIN) \
		NO_DIFF=$(NO_DIFF) \
		REF="$(REF)"

nemu-ref:
	@$(MAKE) -C $(NEMU_HOME) riscv64-xs-ref_defconfig
	@$(MAKE) -C $(NEMU_HOME) -j2 || true
	@$(MAKE) -C $(NEMU_HOME) staticlib

$(WORKLOAD_ELF): $(RISCV_TEST_ENV)/riscv_test.h $(RISCV_TEST_ENV)/link.ld \
		$(RISCV_TESTS_HOME)/isa/$(ISA_SUITE)/$(TEST).S \
		$(RISCV_TESTS_HOME)/isa/macros/scalar/test_macros.h \
		$(RISCV_TESTS_HOME)/env/encoding.h
	@mkdir -p $(@D)
	$(RISCV_GCC) -march=rv64i -mabi=lp64 -mcmodel=medany -static \
		-nostdlib -nostartfiles \
		-I$(RISCV_TEST_ENV) \
		-I$(RISCV_TESTS_HOME)/isa/macros/scalar \
		-I$(RISCV_TESTS_HOME)/env \
		-T $(RISCV_TEST_ENV)/link.ld \
		$(RISCV_TESTS_HOME)/isa/$(ISA_SUITE)/$(TEST).S -o $@

riscv-test-workload: $(WORKLOAD_ELF)

emu-riscv-test: emu riscv-test-workload
	./build/emu -i $(WORKLOAD_ELF) --no-diff -I $(EMU_MAX_INSTR)

difftest-riscv-test: riscv-test-workload nemu-ref
	@$(MAKE) emu WITH_CHISELDB=0 WITH_CONSTANTIN=0 REF="$(NEMU_STATIC_REF)"
	./build/emu -i $(WORKLOAD_ELF) -I $(EMU_MAX_INSTR)

clean:
	rm -rf build
	rm -rf generated
	rm -rf wave
	rm -rf UnitTest.mk
