# WiredRV

`WiredRV` 是一个基于 Chisel 的 RISC-V 处理器工程，当前主仿真链路基于 `difftest + NEMU`。

## 环境

- `NOOP_HOME` 默认是仓库根目录
- `NEMU_HOME` 默认是 `/workspace/NEMU`
- RISC-V 工具链默认是 `/public/riscv-tools/rv64ima/bin/riscv64-unknown-elf-*`

## NEMU 配置

必须使用：

```bash
make -C /workspace/NEMU riscv64-nutshell-ref_defconfig
make -C /workspace/NEMU -j2
```

不要使用 `riscv64-xs-ref_defconfig`。  
当前 DUT 导出的 difftest 状态较小，`xs-ref` 会打开过多状态，运行时会报：

```text
ERROR: NEMU (1400B) is configured with different states than DUT (408B).
```

## 常用命令

编译 Scala/Chisel：

```bash
mill 'wiredrv[6.6.0].compile'
```

生成 Verilog：

```bash
make sim-verilog
```

构建 NEMU 参考模型：

```bash
make nemu-ref
```

构建 `emu`：

```bash
make emu WITH_CHISELDB=0 WITH_CONSTANTIN=0 REF=Nemu
```

构建支持波形的 CLI 版本：

```bash
make emu-cli WITH_CHISELDB=0 WITH_CONSTANTIN=0 REF=Nemu
```

编译并启动 CLI：

```bash
make cli TEST=simple WITH_CHISELDB=0 WITH_CONSTANTIN=0 REF=Nemu
```

仅启动已编译好的 CLI：

```bash
make run-cli TEST=simple
```

## Difftest

运行单个 `riscv-tests`：

```bash
EMU_MAX_CYCLES=500000 make difftest-riscv-test TEST=simple
```

直接运行 `emu`：

```bash
./build/emu \
  -i build/workloads/rv64ui-simple.elf \
  --diff /workspace/NEMU/build/riscv64-nemu-interpreter-so \
  -I 200000
```

限制最大周期数：

```bash
./build/emu \
  -i build/workloads/rv64ui-simple.elf \
  --diff /workspace/NEMU/build/riscv64-nemu-interpreter-so \
  -I 200000 \
  -C 500000
```

说明：

- `-I`：最大指令数
- `-C`：最大周期数；默认不限

## CLI

启动 CLI 的底层命令等价于：

```bash
./build/emu -i build/workloads/rv64ui-simple.elf --diff /workspace/NEMU/build/riscv64-nemu-interpreter-so -I 200000 --cli
```

CLI 常用命令：

- `n <N>`：运行 `N` 个周期
- `nd <N>`：运行 `N` 个周期后导出波形并启动 `surver`
- `c`：持续运行直到仿真结束
- `d`：导出波形并启动 `surver`
- `t`：切换波形记录
- `status`：显示当前状态
- `q` / `quit` / `exit`：退出

说明：

- `make cli` / `make run-cli` 使用 trace 版 `emu` 时，CLI 默认启动波形记录
- 这样会从仿真起点开始录波，包括 reset
- 未显式指定 `--wave-path` 时，会自动生成一个默认波形文件路径
- 如果需要 `d/nd/t`，请使用 `make emu-cli ...` 或显式用 `EMU_TRACE=fst` 重编

## 一体式波形调试

推荐命令：

```bash
./scripts/run-difftest-wave.sh simple
```

该脚本会：

1. 编译 workload
2. 生成反汇编
3. 用 `EMU_TRACE=fst` 重编 `emu`
4. 运行 difftest
5. 导出 `fst` 波形
6. 在前台启动 `surver`

常用变量：

```bash
NO_SERVER=1 ./scripts/run-difftest-wave.sh simple
SURVER_PORT=4010 ./scripts/run-difftest-wave.sh simple
SURVER_TOKEN=12345678 ./scripts/run-difftest-wave.sh simple
WAVE_END=5000 ./scripts/run-difftest-wave.sh simple
EMU_MAX_INSTR=500000 ./scripts/run-difftest-wave.sh simple
EMU_MAX_CYCLES=500000 ./scripts/run-difftest-wave.sh simple
NEMU_REF_SO=/workspace/NEMU/build/riscv64-nemu-interpreter-so ./scripts/run-difftest-wave.sh simple
```

输出位置：

- ELF：`build/workloads/<isa>-<test>.elf`
- 反汇编：`build/disasm/<isa>-<test>.txt`
- 波形：`wave/<isa>-<test>.fst`

## 可用测试

默认面向 `rv64ui`。当前常用测试包括：

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

枚举方式：

```bash
cd third_party/riscv-tests/isa/rv64ui
printf '%s\n' *.S | sed 's/\\.S$//' | sort
```
