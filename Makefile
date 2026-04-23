
.PHONY: sim-verilog emu emu-cli cli run-cli clean nemu-ref riscv-test-workload emu-riscv-test difftest-riscv-test wave-riscv-test

NOOP_HOME ?= $(abspath .)
export NOOP_HOME
NEMU_HOME ?= /workspace/NEMU
export NEMU_HOME
NEMU_REF_SO ?= $(NEMU_HOME)/build/riscv64-nemu-interpreter-so
RISCV_PREFIX ?= /public/riscv-tools/rv64ima/bin/riscv64-unknown-elf
RISCV_GCC ?= $(RISCV_PREFIX)-gcc
RISCV_OBJDUMP ?= $(RISCV_PREFIX)-objdump
RISCV_TESTS_HOME ?= $(NOOP_HOME)/third_party/riscv-tests
RISCV_TEST_ENV ?= $(NOOP_HOME)/tests/riscv-tests/wiredrv
ISA_SUITE ?= rv64ui
TEST ?= simple
EMU_MAX_INSTR ?= 200000
EMU_MAX_CYCLES ?=
WORKLOAD_ELF ?= $(NOOP_HOME)/build/workloads/$(ISA_SUITE)-$(TEST).elf
WORKLOAD_DISASM ?= $(NOOP_HOME)/build/disasm/$(ISA_SUITE)-$(TEST).txt
EMU_CYCLE_FLAG := $(if $(strip $(EMU_MAX_CYCLES)),-C $(EMU_MAX_CYCLES),)
EMU_CLI_FLAGS := -i $(WORKLOAD_ELF) --diff $(NEMU_REF_SO) -I $(EMU_MAX_INSTR) $(EMU_CYCLE_FLAG) --cli

sim-verilog:
	mill 'wiredrv[6.6.0].runMain' wiredrv.harness.WiredCoreHarnessTopMain

emu: sim-verilog
	@$(MAKE) -C difftest emu \
		WITH_CHISELDB=$(WITH_CHISELDB) \
		WITH_CONSTANTIN=$(WITH_CONSTANTIN) \
		NO_DIFF=$(NO_DIFF) \
		REF="$(REF)"

EMU_TRACE_FOR_CLI := $(if $(strip $(EMU_TRACE)),$(EMU_TRACE),fst)

emu-cli: sim-verilog
	@rm -rf $(NOOP_HOME)/build/verilator-compile $(NOOP_HOME)/build/emu
	@$(MAKE) -C difftest emu \
		WITH_CHISELDB=$(WITH_CHISELDB) \
		WITH_CONSTANTIN=$(WITH_CONSTANTIN) \
		NO_DIFF=$(NO_DIFF) \
		REF="$(REF)" \
		EMU_TRACE=$(EMU_TRACE_FOR_CLI)

cli: nemu-ref riscv-test-workload
	@$(MAKE) emu-cli \
		WITH_CHISELDB=$(WITH_CHISELDB) \
		WITH_CONSTANTIN=$(WITH_CONSTANTIN) \
		REF=$(if $(REF),$(REF),Nemu)
	./build/emu $(EMU_CLI_FLAGS)

run-cli: riscv-test-workload nemu-ref
	./build/emu $(EMU_CLI_FLAGS)

nemu-ref:
	@$(MAKE) -C $(NEMU_HOME) riscv64-nutshell-ref_defconfig
	@$(MAKE) -C $(NEMU_HOME) -j2

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

$(WORKLOAD_DISASM): $(WORKLOAD_ELF)
	@mkdir -p $(@D)
	$(RISCV_OBJDUMP) -d -M no-aliases,numeric $(WORKLOAD_ELF) > $@

riscv-test-workload: $(WORKLOAD_ELF) $(WORKLOAD_DISASM)

emu-riscv-test: emu riscv-test-workload
	./build/emu -i $(WORKLOAD_ELF) --no-diff -I $(EMU_MAX_INSTR) $(EMU_CYCLE_FLAG)

difftest-riscv-test: riscv-test-workload nemu-ref
	@$(MAKE) emu WITH_CHISELDB=0 WITH_CONSTANTIN=0 REF=Nemu
	./build/emu -i $(WORKLOAD_ELF) -I $(EMU_MAX_INSTR) $(EMU_CYCLE_FLAG)

wave-riscv-test:
	./scripts/run-difftest-wave.sh $(TEST)

clean:
	rm -rf build
	rm -rf generated
	rm -rf wave
	rm -rf UnitTest.mk
