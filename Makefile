.PHONY: help compile sim-verilog emu emu-wave emu-cli cli run-cli clean nemu-ref riscv-test-workload emu-riscv-test difftest-riscv-test parallel-riscv-test wave-riscv-test

# -----------------------------------------------------------------------------
# Project and tool configuration
# -----------------------------------------------------------------------------
NOOP_HOME ?= $(abspath .)
export NOOP_HOME

MILL ?= ./mill
CHISEL_VERSION ?= 6.6.0
MILL_WIREDRV_TARGET := wiredrv[$(CHISEL_VERSION)]
SIM_MAIN := wiredrv.harness.WiredCoreHarnessTopMain

# -----------------------------------------------------------------------------
# External dependencies
# -----------------------------------------------------------------------------
NEMU_HOME ?= /workspace/NEMU
export NEMU_HOME
NEMU_REF_SO ?= $(NEMU_HOME)/build/riscv64-nemu-interpreter-so

RISCV_PREFIX ?= /public/riscv-tools/rv64ima/bin/riscv64-unknown-elf
RISCV_GCC ?= $(RISCV_PREFIX)-gcc
RISCV_OBJDUMP ?= $(RISCV_PREFIX)-objdump
RISCV_TESTS_HOME ?= $(NOOP_HOME)/third_party/riscv-tests
RISCV_TEST_ENV ?= $(NOOP_HOME)/tests/riscv-tests/wiredrv

# -----------------------------------------------------------------------------
# RISC-V test and emulator configuration
# -----------------------------------------------------------------------------
ISA_SUITE ?= rv64ui
TEST ?= simple
TESTS ?= $(TEST)
RISCV_ARCH ?= rv64i
TEST_JOBS ?= $(shell n=$$(nproc 2>/dev/null || echo 1); if [ $$n -gt 8 ]; then echo 8; else echo $$n; fi)

EMU_MAX_INSTR ?= 200000
EMU_MAX_CYCLES ?=
EMU_CYCLE_FLAG := $(if $(strip $(EMU_MAX_CYCLES)),-C $(EMU_MAX_CYCLES),)

WORKLOAD_ELF ?= $(NOOP_HOME)/build/workloads/$(ISA_SUITE)-$(TEST).elf
WORKLOAD_DISASM ?= $(NOOP_HOME)/build/disasm/$(ISA_SUITE)-$(TEST).txt
EMU_CLI_FLAGS := -i $(WORKLOAD_ELF) --diff $(NEMU_REF_SO) -I $(EMU_MAX_INSTR) $(EMU_CYCLE_FLAG) --cli

# -----------------------------------------------------------------------------
# Waveform configuration
# -----------------------------------------------------------------------------
WAVE_END ?= 2000
SURVER_PORT ?= 4000
SURVER_TOKEN ?= 00000000
NO_SERVER ?= 0
WAVE_DIR ?= $(NOOP_HOME)/wave
WAVE_PATH ?= $(WAVE_DIR)/$(ISA_SUITE)-$(TEST).fst
EMU_WAVE_FLAGS := -i $(WORKLOAD_ELF) --diff $(NEMU_REF_SO) -I $(EMU_MAX_INSTR) $(EMU_CYCLE_FLAG) --dump-wave-full --wave-path=$(WAVE_PATH) -e $(WAVE_END)

help:
	@echo "Common targets:"
	@echo "  make compile                 Compile Scala/Chisel with Chisel $(CHISEL_VERSION)"
	@echo "  make sim-verilog             Generate SystemVerilog into build/rtl"
	@echo "  make emu                     Build Verilator emulator through difftest"
	@echo "  make difftest-riscv-test     Run one riscv-test with NEMU difftest"
	@echo "  make wave-riscv-test         Run difftest and dump FST waveform"
	@echo "  make cli                     Build and start the interactive emulator CLI"
	@echo ""
	@echo "Useful variables:"
	@echo "  TEST=simple ISA_SUITE=rv64ui EMU_MAX_INSTR=200000 EMU_MAX_CYCLES=<empty>"

compile:
	$(MILL) '$(MILL_WIREDRV_TARGET).compile'

sim-verilog:
	$(MILL) '$(MILL_WIREDRV_TARGET).runMain' $(SIM_MAIN)

emu: sim-verilog
	@$(MAKE) -C difftest emu \
		WITH_CHISELDB=$(WITH_CHISELDB) \
		WITH_CONSTANTIN=$(WITH_CONSTANTIN) \
		NO_DIFF=$(NO_DIFF) \
		REF="$(REF)"

emu-wave: sim-verilog
	@rm -rf $(NOOP_HOME)/build/verilator-compile $(NOOP_HOME)/build/emu
	@$(MAKE) -C difftest emu \
		WITH_CHISELDB=0 \
		WITH_CONSTANTIN=0 \
		NO_DIFF=$(NO_DIFF) \
		REF=Nemu \
		EMU_TRACE=fst

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
	@if [ -t 0 ] && [ -t 1 ]; then \
		./build/emu $(EMU_CLI_FLAGS); \
	else \
		echo "CLI emulator is built, but this is not an interactive terminal; skip launch."; \
		echo "Run in a terminal: make run-cli TEST=$(TEST)"; \
	fi

run-cli: riscv-test-workload nemu-ref
	@if [ -t 0 ] && [ -t 1 ]; then \
		./build/emu $(EMU_CLI_FLAGS); \
	else \
		echo "run-cli requires an interactive terminal."; \
		exit 1; \
	fi

nemu-ref:
	@$(MAKE) -C $(NEMU_HOME) riscv64-nutshell-ref_defconfig
	@$(MAKE) -C $(NEMU_HOME) -j2

$(WORKLOAD_ELF): $(RISCV_TEST_ENV)/riscv_test.h $(RISCV_TEST_ENV)/link.ld \
		$(RISCV_TESTS_HOME)/isa/$(ISA_SUITE)/$(TEST).S \
		$(RISCV_TESTS_HOME)/isa/macros/scalar/test_macros.h \
		$(RISCV_TESTS_HOME)/env/encoding.h
	@mkdir -p $(@D)
	$(RISCV_GCC) -march=$(RISCV_ARCH) -mabi=lp64 -mcmodel=medany -static \
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

parallel-riscv-test:
	@JOBS="$(TEST_JOBS)" \
	TESTS="$(TESTS)" \
	ISA_SUITE="$(ISA_SUITE)" \
	TEST="$(TEST)" \
	CHISEL_VERSION="$(CHISEL_VERSION)" \
	EMU_MAX_INSTR="$(EMU_MAX_INSTR)" \
	EMU_MAX_CYCLES="$(EMU_MAX_CYCLES)" \
	NEMU_HOME="$(NEMU_HOME)" \
	NEMU_REF_SO="$(NEMU_REF_SO)" \
	NO_DIFF="$(NO_DIFF)" \
	REF="$(if $(REF),$(REF),Nemu)" \
	./scripts/run-riscv-tests-parallel.sh

wave-riscv-test: riscv-test-workload nemu-ref emu-wave
	@mkdir -p $(WAVE_DIR)
	@echo "run difftest and dump waveform to $(WAVE_PATH)"
	@set +e; \
	stdbuf -o0 -e0 ./build/emu $(EMU_WAVE_FLAGS); \
	sim_rc=$$?; \
	if [ ! -f "$(WAVE_PATH)" ]; then \
		echo "waveform not generated: $(WAVE_PATH)" >&2; \
		if [ $$sim_rc -eq 0 ]; then exit 1; else exit $$sim_rc; fi; \
	fi; \
	if [ "$(NO_SERVER)" != "1" ]; then \
		echo "start surver in foreground on port $(SURVER_PORT)"; \
		echo "wave: $(WAVE_PATH)"; \
		echo "token: $(SURVER_TOKEN)"; \
		echo "url: http://127.0.0.1:$(SURVER_PORT)"; \
		exec surver "$(WAVE_PATH)" --port "$(SURVER_PORT)" --token "$(SURVER_TOKEN)"; \
	else \
		echo "skip surver because NO_SERVER=1"; \
		echo "wave: $(WAVE_PATH)"; \
		echo "disasm: $(WORKLOAD_DISASM)"; \
		exit $$sim_rc; \
	fi

clean:
	rm -rf build
	rm -rf generated
	rm -rf wave
	rm -rf UnitTest.mk
