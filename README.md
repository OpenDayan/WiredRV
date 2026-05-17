# WiredRV

`WiredRV` is a Chisel-based RISC-V processor project. The current simulation
flow is built around `difftest + NEMU`, while the top-level build entry is kept
as a small Makefile wrapper over Mill and the difftest Makefile.

## Environment

- `NOOP_HOME` defaults to the repository root.
- `NEMU_HOME` defaults to `/workspace/NEMU`.
- The RISC-V toolchain prefix defaults to
  `/public/riscv-tools/rv64ima/bin/riscv64-unknown-elf`.
- The project currently keeps `Chisel 6.6.0 + Mill 0.11.8`.

## Recommended Build Flow

Initialize submodules:

```bash
make deps
```

Compile Scala/Chisel:

```bash
make compile
```

Generate SystemVerilog into `build/rtl`:

```bash
make verilog
```

`make sim-verilog` is kept as a compatibility alias of `make verilog`.

Build the Verilator emulator through difftest:

```bash
make emu WITH_CHISELDB=0 WITH_CONSTANTIN=0 REF=Nemu
```

Run one `riscv-tests` workload with NEMU difftest:

```bash
EMU_MAX_CYCLES=500000 make difftest-riscv-test TEST=simple
```

## LaomaSubsystem AXI Dependency

WiredRV uses AXI bundle/type definitions and the fixed `AxiNarrowToWide`
implementation from the `third_party/LaomaSubsystem` submodule. The submodule
should point at the `dev/axi5` branch commit that contains:

```text
Initial commit: add AXI5 Crossbar design files
```

The local `src/main/scala/lmss/axi/AxiCompat.scala` compatibility file has been
removed to avoid conflicts with the Laoma definitions. The active AXI sources
are compiled from:

```text
third_party/LaomaSubsystem/src/main/scala/lmss/axi/package.scala
third_party/LaomaSubsystem/src/main/scala/lmss/axi/AxiNarrowToWide.scala
```

## Makefile Targets

Dependency targets:

- `make deps`: initialize/update all configured git submodules through
  `scripts/update_submodules.py`.

Chisel/Mill targets:

- `make compile`: compile `wiredrv[6.6.0]`.
- `make verilog`: run `wiredrv.harness.WiredCoreHarnessTopMain` and generate
  RTL into `build/rtl`.
- `make sim-verilog`: compatibility alias of `make verilog`.
- `make test`: run Scala tests if `src/test/scala` exists; otherwise run
  `make compile` and print a notice.

Difftest and emulator targets:

- `make emu`: build the Verilator emulator through `difftest`.
- `make emu-riscv-test`: run one workload without difftest.
- `make difftest-riscv-test`: run one workload with NEMU difftest.
- `make parallel-riscv-test`: run the selected `TESTS` list in parallel.

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
make -C /workspace/NEMU riscv64-nutshell-ref_defconfig
make -C /workspace/NEMU -j2
```

Do not use `riscv64-xs-ref_defconfig` for the current DUT. The exported
difftest state is smaller than the XS reference state and can fail with:

```text
ERROR: NEMU (1400B) is configured with different states than DUT (408B).
```

## Common Commands

Build NEMU reference:

```bash
make nemu-ref
```

Build a waveform-capable CLI emulator:

```bash
make emu-cli WITH_CHISELDB=0 WITH_CONSTANTIN=0 REF=Nemu
```

Build and start CLI:

```bash
make cli TEST=simple WITH_CHISELDB=0 WITH_CONSTANTIN=0 REF=Nemu
```

Start an already built CLI:

```bash
make run-cli TEST=simple
```

Directly run the emulator:

```bash
./build/emu \
  -i build/workloads/rv64ui-simple.elf \
  --diff /workspace/NEMU/build/riscv64-nemu-interpreter-so \
  -I 200000
```

Limit maximum cycles:

```bash
./build/emu \
  -i build/workloads/rv64ui-simple.elf \
  --diff /workspace/NEMU/build/riscv64-nemu-interpreter-so \
  -I 200000 \
  -C 500000
```

Options:

- `-I`: maximum instruction count.
- `-C`: maximum cycle count; empty by default.

## CLI Notes

The underlying CLI launch command is equivalent to:

```bash
./build/emu -i build/workloads/rv64ui-simple.elf --diff /workspace/NEMU/build/riscv64-nemu-interpreter-so -I 200000 --cli
```

Useful CLI commands:

- `n <N>`: run `N` cycles.
- `nd <N>`: run `N` cycles, dump waveform, and start `surver`.
- `c`: continue until simulation ends.
- `d`: dump waveform and start `surver`.
- `t`: toggle waveform tracing.
- `status`: show current state.
- `q` / `quit` / `exit`: exit.

`make cli` and `make run-cli` use a trace-capable emulator by default. If
`--wave-path` is not specified, the emulator creates a default waveform path.
For `d`, `nd`, or `t`, rebuild with `make emu-cli ...` or explicitly set
`EMU_TRACE=fst`.

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
NEMU_REF_SO=/workspace/NEMU/build/riscv64-nemu-interpreter-so ./scripts/run-difftest-wave.sh simple
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
fence_i
```

List tests with:

```bash
cd third_party/riscv-tests/isa/rv64ui
printf '%s\n' *.S | sed 's/\.S$//' | sort
```
