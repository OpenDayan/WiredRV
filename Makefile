.PHONY: help deps compile verilog sim-verilog test emu emu-wave emu-cli cli run-cli clean verilog-clean nemu-ref riscv-test-workload emu-riscv-test difftest-riscv-test parallel-riscv-test wave-riscv-test coremark-workload emu-coremark

# -----------------------------------------------------------------------------
# Project and tool configuration
# -----------------------------------------------------------------------------
NOOP_HOME ?= $(abspath .)
export NOOP_HOME

MILL ?= ./mill
CHISEL_VERSION ?= 6.6.0
MILL_WIREDRV_TARGET := wiredrv[$(CHISEL_VERSION)]
SIM_MAIN := wiredrv.harness.WiredCoreHarnessTopMain
SCALA_TEST_DIR := $(NOOP_HOME)/src/test/scala
SIM_UNCACHED_IN_RAM ?= 0
SIM_UNCACHED_FLAG := $(if $(filter 1 true yes,$(SIM_UNCACHED_IN_RAM)),-Dsim-uncached-in-ram=true,)

MILL_FORK_XMX ?= 256G
MILL_FORK_XSS ?= 192m
export MILL_FORK_XMX
export MILL_FORK_XSS

# -----------------------------------------------------------------------------
# External dependencies
# -----------------------------------------------------------------------------
NEMU_HOME ?= /workspace/NEMU
export NEMU_HOME
NEMU_REF_SO ?= $(NEMU_HOME)/build/riscv64-nemu-interpreter-so

HOST_RISCV_GCC := $(shell command -v riscv64-unknown-elf-gcc 2>/dev/null)
RISCV_PREFIX ?= $(if $(HOST_RISCV_GCC),$(patsubst %-gcc,%,$(HOST_RISCV_GCC)),/public/riscv-tools/rv64ima/bin/riscv64-unknown-elf)
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
LOCAL_RISCV_TEST_SRC := $(RISCV_TEST_ENV)/isa/$(ISA_SUITE)/$(TEST).S
UPSTREAM_RISCV_TEST_SRC := $(RISCV_TESTS_HOME)/isa/$(ISA_SUITE)/$(TEST).S
RISCV_TEST_SRC := $(if $(wildcard $(LOCAL_RISCV_TEST_SRC)),$(LOCAL_RISCV_TEST_SRC),$(UPSTREAM_RISCV_TEST_SRC))

# -----------------------------------------------------------------------------
# CoreMark workload configuration
# -----------------------------------------------------------------------------
COREMARK_HOME ?= $(NOOP_HOME)/../coremark
COREMARK_PORT_DIR := $(NOOP_HOME)/tests/coremark/wiredrv
COREMARK_BUILD_DIR := $(NOOP_HOME)/build/coremark
COREMARK_ELF ?= $(COREMARK_BUILD_DIR)/coremark.elf
COREMARK_DISASM ?= $(NOOP_HOME)/build/disasm/coremark.txt
COREMARK_CONFIG := $(COREMARK_BUILD_DIR)/coremark_config.mk
COREMARK_ITERATIONS ?= 1
COREMARK_MAX_INSTR ?= 50000000
COREMARK_RISCV_ARCH ?= rv64im_zicsr
COREMARK_CFLAGS ?= -O2
COREMARK_CPU_HZ ?= 1000000
COREMARK_SRCS := \
	$(COREMARK_HOME)/core_list_join.c \
	$(COREMARK_HOME)/core_main.c \
	$(COREMARK_HOME)/core_matrix.c \
	$(COREMARK_HOME)/core_state.c \
	$(COREMARK_HOME)/core_util.c
COREMARK_PORT_SRCS := \
	$(COREMARK_PORT_DIR)/start.S \
	$(COREMARK_PORT_DIR)/core_portme.c \
	$(COREMARK_PORT_DIR)/ee_printf.c
COREMARK_COMPILE_FLAGS := \
	-march=$(COREMARK_RISCV_ARCH) -mabi=lp64 -mcmodel=medany \
	-static -nostdlib -nostartfiles -ffreestanding -fno-builtin \
	-msmall-data-limit=0 \
	$(COREMARK_CFLAGS) \
	-I$(COREMARK_PORT_DIR) -I$(COREMARK_HOME) \
	-DITERATIONS=$(COREMARK_ITERATIONS) \
	-DPERFORMANCE_RUN=1 \
	-DTOTAL_DATA_SIZE=2000 \
	-DMAIN_HAS_NOARGC=1 \
	-DCOREMARK_CPU_HZ=$(COREMARK_CPU_HZ) \
	-Wl,-T,$(COREMARK_PORT_DIR)/link.ld

COREMARK_CONFIG_TEXT := ITERATIONS=$(COREMARK_ITERATIONS) CPU_HZ=$(COREMARK_CPU_HZ) ARCH=$(COREMARK_RISCV_ARCH) CFLAGS=$(COREMARK_CFLAGS)

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
	@echo "Dependency targets:"
	@echo "  make deps                    Initialize/update git submodules"
	@echo ""
	@echo "Chisel/Mill targets:"
	@echo "  make compile                 Compile Scala/Chisel with Chisel $(CHISEL_VERSION)"
	@echo "  make verilog                 Generate SystemVerilog into build/rtl"
	@echo "  make sim-verilog             Alias of make verilog"
	@echo "  make test                    Run Scala tests when src/test/scala exists"
	@echo ""
	@echo "Difftest and emulator targets:"
	@echo "  make emu                     Build Verilator emulator through difftest"
	@echo "  make difftest-riscv-test     Run one riscv-test with NEMU difftest"
	@echo "  make emu-riscv-test          Run one riscv-test without difftest"
	@echo "  make parallel-riscv-test     Run TESTS in parallel"
	@echo "  make coremark-workload       Build local CoreMark baremetal workload"
	@echo "  make emu-coremark            Run CoreMark without difftest"
	@echo ""
	@echo "CLI and waveform targets:"
	@echo "  make cli                     Build and start the interactive emulator CLI"
	@echo "  make run-cli                 Start an already built interactive emulator CLI"
	@echo "  make wave-riscv-test         Run difftest and dump FST waveform"
	@echo ""
	@echo "Clean targets:"
	@echo "  make verilog-clean           Remove generated RTL outputs"
	@echo "  make clean                   Remove all generated build outputs"
	@echo ""
	@echo "Useful variables:"
	@echo "  TEST=simple ISA_SUITE=rv64ui EMU_MAX_INSTR=200000 EMU_MAX_CYCLES=<empty>"
	@echo "  COREMARK_HOME=$(COREMARK_HOME) COREMARK_ITERATIONS=$(COREMARK_ITERATIONS) COREMARK_CPU_HZ=$(COREMARK_CPU_HZ)"
	@echo "  SIM_UNCACHED_IN_RAM=1       Mark 0x80001000-0x80001fff as uncached for simulation"
	@echo "  MILL_FORK_XMX=$(MILL_FORK_XMX) MILL_FORK_XSS=$(MILL_FORK_XSS)"

deps:
	./scripts/update_submodules.py

compile:
	$(MILL) '$(MILL_WIREDRV_TARGET).compile'

verilog:
	$(MILL) $(SIM_UNCACHED_FLAG) '$(MILL_WIREDRV_TARGET).runMain' $(SIM_MAIN)

sim-verilog: verilog

test:
	@if [ -d "$(SCALA_TEST_DIR)" ]; then \
		$(MILL) '$(MILL_WIREDRV_TARGET).tests.test'; \
	else \
		echo "No Scala tests found at $(SCALA_TEST_DIR); run compile instead."; \
		$(MILL) '$(MILL_WIREDRV_TARGET).compile'; \
	fi

emu: verilog
	@$(MAKE) -C difftest emu \
		WITH_CHISELDB=$(WITH_CHISELDB) \
		WITH_CONSTANTIN=$(WITH_CONSTANTIN) \
		NO_DIFF=$(NO_DIFF) \
		REF="$(REF)"

emu-wave: verilog
	@rm -rf $(NOOP_HOME)/build/verilator-compile $(NOOP_HOME)/build/emu
	@$(MAKE) -C difftest emu \
		WITH_CHISELDB=0 \
		WITH_CONSTANTIN=0 \
		NO_DIFF=$(NO_DIFF) \
		REF=Nemu \
		EMU_TRACE=fst

EMU_TRACE_FOR_CLI := $(if $(strip $(EMU_TRACE)),$(EMU_TRACE),fst)

emu-cli: verilog
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
		$(RISCV_TEST_SRC) \
		$(RISCV_TESTS_HOME)/isa/macros/scalar/test_macros.h \
		$(RISCV_TESTS_HOME)/env/encoding.h
	@mkdir -p $(@D)
	$(RISCV_GCC) -march=$(RISCV_ARCH) -mabi=lp64 -mcmodel=medany -static \
		-nostdlib -nostartfiles \
		-I$(RISCV_TEST_ENV) \
		-I$(RISCV_TESTS_HOME)/isa/macros/scalar \
		-I$(RISCV_TESTS_HOME)/env \
		-T $(RISCV_TEST_ENV)/link.ld \
		$(RISCV_TEST_SRC) -o $@

$(WORKLOAD_DISASM): $(WORKLOAD_ELF)
	@mkdir -p $(@D)
	$(RISCV_OBJDUMP) -d -M no-aliases,numeric $(WORKLOAD_ELF) > $@

riscv-test-workload: $(WORKLOAD_ELF) $(WORKLOAD_DISASM)

emu-riscv-test: riscv-test-workload
	@$(MAKE) emu NO_DIFF=1
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

$(COREMARK_CONFIG): FORCE
	@mkdir -p $(@D)
	@old="$$(cat $@ 2>/dev/null || true)"; \
	if [ "$$old" != "$(COREMARK_CONFIG_TEXT)" ]; then \
		printf '%s\n' "$(COREMARK_CONFIG_TEXT)" > $@; \
	fi

$(COREMARK_ELF): $(COREMARK_CONFIG) $(COREMARK_SRCS) $(COREMARK_PORT_SRCS) \
		$(COREMARK_PORT_DIR)/core_portme.h $(COREMARK_PORT_DIR)/link.ld
	@test -d "$(COREMARK_HOME)" || { echo "COREMARK_HOME not found: $(COREMARK_HOME)" >&2; exit 1; }
	@mkdir -p $(@D)
	$(RISCV_GCC) $(COREMARK_COMPILE_FLAGS) $(COREMARK_PORT_SRCS) $(COREMARK_SRCS) -lgcc -o $@

$(COREMARK_DISASM): $(COREMARK_ELF)
	@mkdir -p $(@D)
	$(RISCV_OBJDUMP) -d -M no-aliases,numeric $(COREMARK_ELF) > $@

coremark-workload: $(COREMARK_ELF) $(COREMARK_DISASM)

emu-coremark: coremark-workload
	@$(MAKE) emu NO_DIFF=1 SIM_UNCACHED_IN_RAM=1
	./build/emu -i $(COREMARK_ELF) --no-diff -I $(COREMARK_MAX_INSTR) $(EMU_CYCLE_FLAG)

verilog-clean:
	rm -rf build/rtl
	rm -rf generated

clean:
	rm -rf build
	rm -rf generated
	rm -rf wave
	rm -rf UnitTest.mk

FORCE:
