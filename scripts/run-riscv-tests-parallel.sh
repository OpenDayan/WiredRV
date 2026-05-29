#!/usr/bin/env bash
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DEFAULT_SUITE="${ISA_SUITE:-rv64ui}"
DEFAULT_TEST="${TEST:-simple}"
DEFAULT_JOBS="$(nproc 2>/dev/null || echo 1)"
if [[ "$DEFAULT_JOBS" -gt 8 ]]; then
  DEFAULT_JOBS=8
fi
JOBS="${JOBS:-$DEFAULT_JOBS}"
BUILD_WORKLOADS="${BUILD_WORKLOADS:-1}"
BUILD_NEMU="${BUILD_NEMU:-1}"
BUILD_EMU="${BUILD_EMU:-1}"
NO_DIFF="${NO_DIFF:-0}"
REF="${REF:-Nemu}"
EMU="${EMU:-$ROOT/build/emu}"
EMU_MAX_INSTR="${EMU_MAX_INSTR:-200000}"
EMU_MAX_CYCLES="${EMU_MAX_CYCLES:-}"
NEMU_HOME="${NEMU_HOME:-/workspace/NEMU}"
NEMU_REF_SO="${NEMU_REF_SO:-$NEMU_HOME/build/riscv64-nemu-interpreter-so}"
LOG_DIR="${LOG_DIR:-$ROOT/build/riscv-test-runs}"
TESTS_TEXT="${TESTS:-}"
DRY_RUN=0
USE_ALL=0

usage() {
  cat <<EOF
usage: $0 [options] [test...]

test format:
  add              使用 ISA_SUITE，默认为 rv64ui
  rv64ui-add       指定测试套件
  rv64ui/add       指定测试套件
  rv64ui:add       指定测试套件

options:
  -j, --jobs N           并行运行的测试数，默认使用 nproc
  -s, --suite SUITE      默认测试套件，默认 rv64ui
      --all              运行默认测试套件下所有 .S 测试
      --list FILE        从文件读取测试名，支持空行和 # 注释
      --no-build-workloads
      --no-build-nemu
      --no-build-emu
      --no-diff
      --dry-run
  -h, --help

environment:
  TESTS="add sub rv64ui-and" JOBS=8 $0
EOF
}

targets=()

add_tests_text() {
  local text="$1"
  local item
  text="${text//,/ }"
  for item in $text; do
    [[ -n "$item" ]] && targets+=("$item")
  done
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -j|--jobs)
      [[ $# -ge 2 ]] || { echo "$1 requires an argument" >&2; exit 2; }
      JOBS="$2"
      shift 2
      ;;
    -s|--suite)
      [[ $# -ge 2 ]] || { echo "$1 requires an argument" >&2; exit 2; }
      DEFAULT_SUITE="$2"
      shift 2
      ;;
    --all)
      USE_ALL=1
      shift
      ;;
    --list)
      [[ $# -ge 2 ]] || { echo "$1 requires an argument" >&2; exit 2; }
      while IFS= read -r line; do
        line="${line%%#*}"
        add_tests_text "$line"
      done < "$2"
      shift 2
      ;;
    --no-build-workloads)
      BUILD_WORKLOADS=0
      shift
      ;;
    --no-build-nemu)
      BUILD_NEMU=0
      shift
      ;;
    --no-build-emu)
      BUILD_EMU=0
      shift
      ;;
    --no-diff)
      NO_DIFF=1
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      while [[ $# -gt 0 ]]; do
        targets+=("$1")
        shift
      done
      ;;
    -*)
      echo "unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      targets+=("$1")
      shift
      ;;
  esac
done

add_tests_text "$TESTS_TEXT"

if [[ "$USE_ALL" == 1 ]]; then
  suite_dir="$ROOT/deps/riscv-tests/isa/$DEFAULT_SUITE"
  if [[ ! -d "$suite_dir" ]]; then
    echo "suite not found: $suite_dir" >&2
    exit 1
  fi
  while IFS= read -r src; do
    test_name="$(basename "$src" .S)"
    targets+=("$DEFAULT_SUITE-$test_name")
  done < <(find "$suite_dir" -maxdepth 1 -type f -name '*.S' | sort)
fi

if [[ ${#targets[@]} -eq 0 ]]; then
  targets+=("$DEFAULT_SUITE-$DEFAULT_TEST")
fi

if ! [[ "$JOBS" =~ ^[0-9]+$ ]] || [[ "$JOBS" -lt 1 ]]; then
  echo "invalid JOBS: $JOBS" >&2
  exit 2
fi

parse_target() {
  local raw="$1"
  local suite test
  if [[ "$raw" == */* ]]; then
    suite="${raw%%/*}"
    test="${raw#*/}"
  elif [[ "$raw" == *:* ]]; then
    suite="${raw%%:*}"
    test="${raw#*:}"
  elif [[ "$raw" == *-* && -d "$ROOT/deps/riscv-tests/isa/${raw%%-*}" ]]; then
    suite="${raw%%-*}"
    test="${raw#*-}"
  else
    suite="$DEFAULT_SUITE"
    test="$raw"
  fi
  printf '%s\t%s\n' "$suite" "$test"
}

names=()
suites=()
tests=()
elfs=()
declare -A seen=()

for raw in "${targets[@]}"; do
  parsed="$(parse_target "$raw")"
  suite="${parsed%%$'\t'*}"
  test="${parsed#*$'\t'}"
  local_src="$ROOT/tests/riscv-tests/wiredrv/isa/$suite/$test.S"
  upstream_src="$ROOT/deps/riscv-tests/isa/$suite/$test.S"
  src="$local_src"
  if [[ ! -f "$src" ]]; then
    src="$upstream_src"
  fi
  name="$suite-$test"
  if [[ ! -f "$src" ]]; then
    echo "test source not found: $local_src or $upstream_src" >&2
    exit 1
  fi
  if [[ -n "${seen[$name]:-}" ]]; then
    continue
  fi
  seen[$name]=1
  names+=("$name")
  suites+=("$suite")
  tests+=("$test")
  elfs+=("$ROOT/build/workloads/$name.elf")
done

mkdir -p "$LOG_DIR"

run_limited() {
  local -n _cmds=$1
  local running=0
  local failed=0
  local cmd

  for cmd in "${_cmds[@]}"; do
    while [[ "$running" -ge "$JOBS" ]]; do
      if ! wait -n; then
        failed=1
      fi
      running=$((running - 1))
    done
    bash -c "$cmd" &
    running=$((running + 1))
  done

  while [[ "$running" -gt 0 ]]; do
    if ! wait -n; then
      failed=1
    fi
    running=$((running - 1))
  done

  return "$failed"
}

quote() {
  printf '%q' "$1"
}

echo "tests: ${names[*]}"
echo "jobs: $JOBS"
echo "log dir: $LOG_DIR"

if [[ "$DRY_RUN" == 1 ]]; then
  echo "dry-run: skip build and execution"
  for i in "${!names[@]}"; do
    echo "  ${names[$i]} -> ${elfs[$i]}"
  done
  exit 0
fi

if [[ "$BUILD_WORKLOADS" == 1 ]]; then
  echo "[1/3] build workloads"
  build_cmds=()
  for i in "${!names[@]}"; do
    log="$LOG_DIR/${names[$i]}.build.log"
    build_cmds+=("make --no-print-directory riscv-test-workload ISA_SUITE=$(quote "${suites[$i]}") TEST=$(quote "${tests[$i]}") >$(quote "$log") 2>&1")
  done
  if ! run_limited build_cmds; then
    echo "workload build failed; see $LOG_DIR/*.build.log" >&2
    exit 1
  fi
else
  echo "[1/3] skip workload build"
fi

missing_elfs=()
for i in "${!names[@]}"; do
  if [[ ! -f "${elfs[$i]}" ]]; then
    missing_elfs+=("${names[$i]}  missing elf: ${elfs[$i]}")
  fi
done
if [[ "${#missing_elfs[@]}" -ne 0 ]]; then
  echo "workload missing; rerun with BUILD_WORKLOADS=1 or remove unavailable tests:" >&2
  for item in "${missing_elfs[@]}"; do
    echo "  $item" >&2
  done
  exit 1
fi

if [[ "$NO_DIFF" != 1 && "$BUILD_NEMU" == 1 ]]; then
  echo "[2/3] build NEMU reference"
  make nemu-ref
elif [[ "$NO_DIFF" != 1 && ! -f "$NEMU_REF_SO" ]]; then
  echo "reference shared object not found: $NEMU_REF_SO" >&2
  echo "run with BUILD_NEMU=1 or make nemu-ref first" >&2
  exit 1
else
  echo "[2/3] skip NEMU reference build"
fi

if [[ "$BUILD_EMU" == 1 ]]; then
  echo "[3/3] build emulator once"
  make emu WITH_CHISELDB=0 WITH_CONSTANTIN=0 NO_DIFF="$NO_DIFF" REF="$REF"
elif [[ ! -x "$EMU" ]]; then
  echo "emulator not found: $EMU" >&2
  echo "run with BUILD_EMU=1 or make emu first" >&2
  exit 1
else
  echo "[3/3] reuse emulator: $EMU"
fi

echo "[run] execute tests in parallel"
run_cmds=()
pass_pattern='HIT GOOD TRAP|good trap|PASS|passed'
for i in "${!names[@]}"; do
  name="${names[$i]}"
  elf="${elfs[$i]}"
  run_dir="$LOG_DIR/$name"
  log="$run_dir/sim.log"
  status="$run_dir/status"
  args=("-i" "$elf" "-I" "$EMU_MAX_INSTR")
  if [[ -n "$EMU_MAX_CYCLES" ]]; then
    args+=("-C" "$EMU_MAX_CYCLES")
  fi
  if [[ "$NO_DIFF" == 1 ]]; then
    args+=("--no-diff")
  else
    args+=("--diff" "$NEMU_REF_SO")
  fi
  if [[ -n "${EMU_ARGS:-}" ]]; then
    read -r -a extra_args <<< "$EMU_ARGS"
    args+=("${extra_args[@]}")
  fi

  cmd="mkdir -p $(quote "$run_dir"); cd $(quote "$run_dir"); stdbuf -o0 -e0 $(quote "$EMU")"
  for arg in "${args[@]}"; do
    cmd+=" $(quote "$arg")"
  done
  cmd+=" >$(quote "$log") 2>&1; rc=\$?; printf '%s\n' \"\$rc\" >$(quote "$status"); exit \"\$rc\""
  run_cmds+=("$cmd")
done

run_limited run_cmds || true

failed=()
for i in "${!names[@]}"; do
  name="${names[$i]}"
  log="$LOG_DIR/${names[$i]}/sim.log"
  status="$LOG_DIR/${names[$i]}/status"
  disasm="$ROOT/build/disasm/$name.txt"
  rc=""
  [[ -f "$status" ]] && read -r rc < "$status"
  if [[ ! -f "$log" ]]; then
    failed+=("${names[$i]}  log missing: $log")
  elif [[ ! -f "$status" || "$rc" != 0 ]]; then
    failed+=("${names[$i]}  status: ${rc:-missing}  log: $log")
  else
    exit_pc="$(grep -aoE 'EXIT at pc = 0x[0-9a-fA-F]+' "$log" | tail -n 1 | sed 's/.*0x//')"
    exit_ok=0
    if [[ -n "$exit_pc" && -f "$disasm" ]]; then
      fail_pc="$(awk '/<fail>:/ { gsub(":", "", $1); print $1; exit }' "$disasm")"
      pass_pc="$(awk '/<pass>:/ { gsub(":", "", $1); print $1; exit }' "$disasm")"
      if [[ -n "$fail_pc" && -n "$pass_pc" ]]; then
        exit_dec=$((16#$exit_pc))
        fail_dec=$((16#$fail_pc))
        pass_dec=$((16#$pass_pc))
        if (( exit_dec >= fail_dec && exit_dec < pass_dec )); then
          failed+=("${names[$i]}  exit pc in fail path: 0x$exit_pc  log: $log")
          continue
        elif (( exit_dec >= pass_dec )); then
          exit_ok=1
        fi
      fi
      if [[ "$exit_ok" != 1 ]]; then
        trap_ok="$(awk -v pc="$exit_pc" '
          BEGIN { ok = 0 }
          {
            addr = tolower($1)
            gsub(":", "", addr)
            if (addr == tolower(pc)) {
              exit_line = $0
              trap_line = ""
              if (getline > 0) {
                trap_line = $0
              }
              if (exit_line ~ /00000513/ && trap_line ~ /0000006b/) {
                ok = 1
                exit
              }
            }
          }
          END { print ok }
        ' "$disasm")"
        if [[ "$trap_ok" == 1 ]]; then
          exit_ok=1
        fi
      fi
    fi
    if [[ "$exit_ok" != 1 ]] && ! tail -n 20 "$log" | grep -Eiq "$pass_pattern"; then
      failed+=("${names[$i]}  log: $log")
    fi
  fi
done

if [[ "${#failed[@]}" -ne 0 ]]; then
  echo
  echo "FAILED:"
  for item in "${failed[@]}"; do
    echo "  $item"
  done
  exit 1
fi

echo
echo "PASSED:"
for name in "${names[@]}"; do
  echo "  $name"
done
