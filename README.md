# WiredRV

## 快速开始

默认环境约定：

- `NOOP_HOME` 默认是仓库根目录
- `NEMU_HOME` 默认是 `/workspace/NEMU`
- RISC-V 工具链默认是 `/public/riscv-tools/rv64ima/bin/riscv64-unknown-elf-*`

先准备参考模型：

```bash
make -C /workspace/NEMU riscv64-nutshell-ref_defconfig
make -C /workspace/NEMU -j2
```

## 常用命令

只编译 Scala/Chisel：

```bash
mill 'wiredrv[6.6.0].compile'
```

只生成 Verilog：

```bash
make sim-verilog
```

编译 `emu`：

```bash
make emu WITH_CHISELDB=0 WITH_CONSTANTIN=0 REF=Nemu
```

运行单个 riscv-tests 用例：

```bash
make riscv-test-workload ISA_SUITE=rv64ui TEST=simple
./build/emu -i build/workloads/rv64ui-simple.elf --diff /workspace/NEMU/build/riscv64-nemu-interpreter-so -I 200000
```

每次生成测试时，也会自动把 ELF 反汇编到固定位置：

```bash
build/disasm/rv64ui-simple.txt
```

如果只想单独更新反汇编：

```bash
make build/disasm/rv64ui-simple.txt
```

## 一体式调试命令

推荐直接用：

```bash
./scripts/run-difftest-wave.sh simple
```

这个命令会按顺序完成：

1. 编译 `rv64ui-simple` workload
   同时生成 `build/disasm/rv64ui-simple.txt`
2. 用 `EMU_TRACE=fst` 重新编译 `emu`
3. 运行 difftest
4. 导出波形到 `wave/rv64ui-simple.fst`
5. 在前台启动 `surver` 提供波形服务

也可以直接走 Make 封装：

```bash
make wave-riscv-test TEST=simple
```

脚本只要求一个位置参数：

- `simple`
- `jal`
- `addi`
- `beq`
- 也支持完整名，比如 `rv64ui-simple`

## 可用测试

默认脚本面向 `rv64ui`，当前仓库可直接使用这些测试名：

```text
add addi addiw addw and andi auipc
beq bge bgeu blt bltu bne
fence_i jal jalr
lb lbu ld ld_st lh lhu lui lw lwu
ma_data
or ori
sb sd sh simple sll slli slliw sllw slt slti sltiu sltu
sra srai sraiw sraw srl srli srliw srlw
st_ld sub subw sw
xor xori
```

也可以自动枚举：

```bash
cd third_party/riscv-tests/isa/rv64ui
printf '%s\n' *.S | sed 's/\\.S$//' | sort
```

## 波形输出

脚本默认输出：

- 波形文件：`wave/<isa-suite>-<test>.fst`
- 反汇编文件：`build/disasm/<isa-suite>-<test>.txt`

默认会执行等价命令：

```bash
surver wave/rv64ui-simple.fst --port 4000 --token 00000000
```

启动后会打印：

- 波形文件路径
- 反汇编文件路径
- 访问地址 `http://127.0.0.1:4000`
- token `00000000`
- 然后前台停在 `surver` 进程上，`Ctrl-C` 即可关闭

## 常用环境变量

这些都不是必选参数，只是覆盖默认值：

```bash
NO_SERVER=1 ./scripts/run-difftest-wave.sh simple
SURVER_PORT=4010 ./scripts/run-difftest-wave.sh simple
SURVER_TOKEN=12345678 ./scripts/run-difftest-wave.sh simple
WAVE_END=5000 ./scripts/run-difftest-wave.sh simple
EMU_MAX_INSTR=500000 ./scripts/run-difftest-wave.sh simple
REF_SO=/workspace/NEMU/build/riscv64-nemu-interpreter-so ./scripts/run-difftest-wave.sh simple
```

含义：

- `NO_SERVER=1`：只出波形，不启动 `surver`
- `SURVER_PORT`：波形服务端口
- `SURVER_TOKEN`：波形服务 token
- `WAVE_END`：波形导出截止 cycle
- `EMU_MAX_INSTR`：仿真最大指令数
- `REF_SO`：difftest 参考模型 `.so` 路径

## 当前调试建议

目前最稳的回归顺序：

```bash
./scripts/run-difftest-wave.sh simple
./scripts/run-difftest-wave.sh jal
./scripts/run-difftest-wave.sh addi
./scripts/run-difftest-wave.sh beq
./scripts/run-difftest-wave.sh jalr
./scripts/run-difftest-wave.sh auipc
```

如果只想要波形，不想起服务：

```bash
NO_SERVER=1 ./scripts/run-difftest-wave.sh jal
```
