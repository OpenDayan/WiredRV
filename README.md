# WiredRV

`WiredRV` is a Chisel-based RISC-V processor project. The current simulation
flow is built around `difftest + NEMU`, while the top-level build entry is kept
as a small Makefile wrapper over Mill and the difftest Makefile.

## Environment

- `NOOP_HOME` defaults to the repository root.
- `NEMU_HOME` defaults to `$(NOOP_HOME)/../NEMU`; for a checkout at `~/code/WiredRV`, this is `~/code/NEMU`.
- The RISC-V toolchain is auto-detected from `PATH` when
  `riscv64-unknown-elf-gcc` exists. Otherwise, `RISCV_PREFIX` falls back to
  `/public/riscv-tools/rv64ima/bin/riscv64-unknown-elf`. Override it with
  `RISCV_PREFIX=/path/to/riscv64-unknown-elf`.
- The project currently keeps `Chisel 7.9.0 + Mill 1.0.4`.

## Recommended Build Flow

Initialize submodules:

```bash
make deps
```

External repositories live under `deps/`:

```text
deps/difftest
deps/riscv-tests
deps/AxiInfra
deps/AxiInfra/dep/rocket-chip
deps/AxiInfra/dep/xs-utils
```

Compile Scala/Chisel:

```bash
make compile
```

Generate SystemVerilog into `generated/WiredCoreHarnessTop`:

```bash
make verilog
```

`make sim-verilog` is kept as a compatibility alias of `make verilog`. To
generate a different zero-argument top module, pass its fully qualified class
name:

```bash
make gen VERILOG_TOP=dayan.wiredrv.Test
```

Generated RTL directories include `filelist.f` for downstream EDA integration.

Build the Verilator emulator through difftest:

```bash
make emu WITH_CHISELDB=0 WITH_CONSTANTIN=0 REF=Nemu
```

Run one `riscv-tests` workload with NEMU difftest:

```bash
EMU_MAX_CYCLES=500000 make difftest-riscv-test TEST=simple
```

## AxiInfra AXI Dependency

WiredRV uses AXI bundle/type definitions and the fixed `AxiNarrowToWide`
implementation from the `deps/AxiInfra` submodule. The submodule should point
at the `dev/axi5` branch commit:

```text
56964049b0be78ce5d653161d711395e418d8852
```

The active AXI sources are compiled from the `xs.infra.axi` package:

```text
deps/AxiInfra/src/main/scala/xs/infra/axi/package.scala
deps/AxiInfra/src/main/scala/xs/infra/axi/AxiNarrowToWide.scala
deps/AxiInfra/dep/cde/cde/src
deps/AxiInfra/dep/xs-utils/src/main/scala/xs/utils
```

WiredRV's Mill build uses the top-level `deps/AxiInfra/dep/*` dependencies
directly. Nested `deps/AxiInfra/dep/xs-utils/dep/*` submodules are not a
WiredRV build entry.

## Makefile Targets

Dependency targets:

- `make deps`: initialize/update all configured git submodules through
  `scripts/update_submodules.py`.

Chisel/Mill targets:

- `make compile`: compile `wiredrv[7.9.0]`.
- `make gen`: run the template-style Mill `gen` command and generate RTL into
  `generated/<Top>`.
- `make verilog`: compatibility alias of `make gen`.
- `make sim-verilog`: compatibility alias of `make verilog`.
- `make test`: run Scala tests if `src/test/scala` exists; otherwise run
  `make compile` and print a notice.

Difftest and emulator targets:

- `make emu`: build the Verilator emulator through `difftest`, using the RTL in
  `generated/<Top>`.
- `make emu-riscv-test`: run one workload without difftest.
- `make difftest-riscv-test`: run one workload with NEMU difftest.
- `make parallel-riscv-test`: run the selected `TESTS` list in parallel. If
  `TESTS` is omitted, it defaults to `TEST`, and `TEST` defaults to `simple`.

CLI and waveform targets:

- `make cli`: build and launch the interactive emulator CLI when the terminal
  is interactive.
- `make run-cli`: launch an already built CLI emulator.
- `make wave-riscv-test`: run difftest, dump an FST waveform, and optionally
  start `surver`.

Clean targets:

- `make verilog-clean`: remove generated RTL outputs only.
- `make clean`: remove all generated build outputs, including `build/`,
  `generated/`, `wave/`, and `UnitTest.mk`.

## NEMU Configuration

Use:

```bash
make nemu-ref

# Or use an explicit NEMU checkout:
NEMU_HOME=/path/to/NEMU make nemu-ref
```

Do not use `riscv64-xs-ref_defconfig` for the current DUT. The exported
difftest state is smaller than the XS reference state and can fail with:

```text
ERROR: NEMU (1400B) is configured with different states than DUT (408B).
```

## Common Commands

Generate RTL for the default difftest harness:

```bash
make verilog
```

Direct Mill entry matching the RVT template flow:

```bash
./mill -Dtop=dayan.wiredrv.harness.WiredCoreHarnessTop gen
```

Use `generated/WiredCoreHarnessTop/filelist.f` when handing the generated RTL
to external EDA flows.

Set `REPL_SEQ_MEM=1` to pass `--repl-seq-mem` through the generator.

Build NEMU reference:

```bash
make nemu-ref
```

Build and start the interactive CLI emulator:

```bash
make cli TEST=simple WITH_CHISELDB=0 WITH_CONSTANTIN=0 REF=Nemu
```

Start an already built CLI emulator:

```bash
make run-cli TEST=simple
```

Directly run the emulator:

```bash
./build/emu \
  -i build/workloads/rv64ui-simple.elf \
  --diff ../NEMU/build/riscv64-nemu-interpreter-so \
  -I 200000
```

Limit maximum cycles:

```bash
./build/emu \
  -i build/workloads/rv64ui-simple.elf \
  --diff ../NEMU/build/riscv64-nemu-interpreter-so \
  -I 200000 \
  -C 500000
```

Options:

- `-I`: maximum instruction count.
- `-C`: maximum cycle count; empty by default.

## Validated Regression Commands

`parallel-riscv-test` runs only `TEST=simple` by default. Pass `TESTS`
explicitly when validating a group.

Smoke test:

```bash
NO_DIFF=1 TESTS="simple add sub" make parallel-riscv-test ISA_SUITE=rv64ui
```

Common `rv64ui` regression, excluding `fence_i`:

```bash
NO_DIFF=1 \
TESTS="simple add addi addiw addw and andi auipc beq bge bgeu blt bltu bne jal jalr lb lbu ld ld_st lh lhu lui lw lwu ma_data or ori sb sd sh sll slli slliw sllw slt slti sltiu sltu sra srai sraiw sraw srl srli srliw srlw st_ld sub subw sw xor xori" \
EMU_MAX_INSTR=500000 \
make parallel-riscv-test ISA_SUITE=rv64ui
```

`rv64um` M-extension regression:

```bash
NO_DIFF=1 \
TESTS="mul mulh mulhsu mulhu mulw div divu divw divuw rem remu remw remuw" \
EMU_MAX_INSTR=500000 \
make parallel-riscv-test ISA_SUITE=rv64um
```

MDU stress tests:

```bash
NO_DIFF=1 \
TESTS="mdu-flush-longwb mdu-stress" \
EMU_MAX_INSTR=1000000 \
make parallel-riscv-test ISA_SUITE=rv64um
```

## CLI Notes

The CLI accepts these commands:

- `help`: show available CLI commands.
- `status`: print cycle, instruction count, trap code, and finish state.
- `n <N>`: run `N` cycles; `N` defaults to 1.
- `ni <N>`: run until `N` more instructions commit; `N` defaults to 1.
- `c`: continue until simulation finishes.
- `q` / `quit` / `exit`: exit the CLI cleanly.

The first CLI version does not support dynamic waveform commands such as `d`,
`nd`, or `t`. Use the one-shot wave debug flow below for waveform-oriented
debugging.

## One-shot Wave Debug

Recommended command:

```bash
./scripts/run-difftest-wave.sh simple
```

The script:

1. Builds the workload.
2. Generates disassembly.
3. Rebuilds `emu` with `EMU_TRACE=fst`.
4. Runs difftest.
5. Dumps an FST waveform.
6. Starts `surver` in the foreground unless disabled.

Useful variables:

```bash
NO_SERVER=1 ./scripts/run-difftest-wave.sh simple
SURVER_PORT=4010 ./scripts/run-difftest-wave.sh simple
SURVER_TOKEN=12345678 ./scripts/run-difftest-wave.sh simple
WAVE_END=5000 ./scripts/run-difftest-wave.sh simple
EMU_MAX_INSTR=500000 ./scripts/run-difftest-wave.sh simple
EMU_MAX_CYCLES=500000 ./scripts/run-difftest-wave.sh simple
NEMU_HOME=/path/to/NEMU ./scripts/run-difftest-wave.sh simple
```

Outputs:

- ELF: `build/workloads/<isa>-<test>.elf`
- Disassembly: `build/disasm/<isa>-<test>.txt`
- Waveform: `wave/<isa>-<test>.fst`

## Available rv64ui Tests

Common tests include:

```text
simple
add addi addiw addw
and andi or ori xor xori
sll slli slliw sllw
srl srli srliw srlw
sra srai sraiw sraw
slt slti sltiu sltu
sub subw
lui auipc jal jalr
beq bne blt bge bltu bgeu
lb lbu lh lhu lw lwu ld
sb sh sw sd
```

### fence_i Note

`rv64ui-fence_i` is a known special case and is not part of the recommended
README regression set. WiredRV currently uses FENCE.I to trigger I-cache /
frontend refresh behavior, but does not claim to fully pass the standard
`riscv-tests` `rv64ui-fence_i` program.

If `TEST=fence_i` is run manually and fails, treat it as a frontend
FENCE.I/I-cache coherence debugging item, not as a failure of the normal
`rv64ui` integer, branch, load/store, or difftest paths.

List tests with:

```bash
cd deps/riscv-tests/isa/rv64ui
printf '%s\n' *.S | sed 's/\.S$//' | sort
```
