#!/usr/bin/env bash
set -uo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <test-name|isa-suite-test>" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

INPUT="$1"
DEFAULT_ISA_SUITE="${ISA_SUITE:-rv64ui}"
if [[ "$INPUT" == *-* ]]; then
  ISA_SUITE="${INPUT%-*}"
  TEST="${INPUT#*-}"
else
  ISA_SUITE="$DEFAULT_ISA_SUITE"
  TEST="$INPUT"
fi

EMU_MAX_INSTR="${EMU_MAX_INSTR:-200000}"
EMU_MAX_CYCLES="${EMU_MAX_CYCLES:-}"
WAVE_END="${WAVE_END:-2000}"
SURVER_PORT="${SURVER_PORT:-4000}"
SURVER_TOKEN="${SURVER_TOKEN:-00000000}"
NO_SERVER="${NO_SERVER:-0}"
NEMU_HOME="${NEMU_HOME:-$ROOT/../NEMU}"
NEMU_REF_SO="${NEMU_REF_SO:-$NEMU_HOME/build/riscv64-nemu-interpreter-so}"
WAVE_DIR="${WAVE_DIR:-$ROOT/wave}"
WAVE_PATH="${WAVE_PATH:-$WAVE_DIR/${ISA_SUITE}-${TEST}.fst}"
DISASM_PATH="${DISASM_PATH:-$ROOT/build/disasm/${ISA_SUITE}-${TEST}.txt}"

mkdir -p "$WAVE_DIR"

EMU_CYCLE_ARGS=()
if [[ -n "$EMU_MAX_CYCLES" ]]; then
  EMU_CYCLE_ARGS=(-C "$EMU_MAX_CYCLES")
fi

if [[ ! -f "$NEMU_REF_SO" ]]; then
  echo "reference shared object not found: $NEMU_REF_SO" >&2
  echo "build it first, for example: make nemu-ref" >&2
  exit 1
fi

echo "[1/4] build workload: ${ISA_SUITE}-${TEST}"
make riscv-test-workload ISA_SUITE="$ISA_SUITE" TEST="$TEST"
echo "disasm: $DISASM_PATH"

echo "[2/4] build emulator with FST waveform support"
rm -rf "$ROOT/build/verilator-compile" "$ROOT/build/emu"
make emu WITH_CHISELDB=0 WITH_CONSTANTIN=0 REF=Nemu EMU_TRACE=fst

echo "[3/4] run difftest and dump waveform to $WAVE_PATH"
set +e
stdbuf -o0 -e0 ./build/emu \
  -i "build/workloads/${ISA_SUITE}-${TEST}.elf" \
  --diff "$NEMU_REF_SO" \
  -I "$EMU_MAX_INSTR" \
  "${EMU_CYCLE_ARGS[@]}" \
  --dump-wave-full \
  --wave-path="$WAVE_PATH" \
  -e "$WAVE_END"
SIM_RC=$?
set -e

if [[ ! -f "$WAVE_PATH" ]]; then
  echo "waveform not generated: $WAVE_PATH" >&2
  exit "$SIM_RC"
fi

if [[ "$NO_SERVER" != "1" ]]; then
  echo "[4/4] start surver in foreground on port $SURVER_PORT"
  echo "wave: $WAVE_PATH"
  echo "token: $SURVER_TOKEN"
  echo "url: http://127.0.0.1:${SURVER_PORT}"
  exec surver "$WAVE_PATH" --port "$SURVER_PORT" --token "$SURVER_TOKEN"
else
  echo "[4/4] skip surver because NO_SERVER=1"
  echo "wave: $WAVE_PATH"
  echo "disasm: $DISASM_PATH"
fi

exit "$SIM_RC"
