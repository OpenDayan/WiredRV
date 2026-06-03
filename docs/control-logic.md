# WiredRV 控制逻辑梳理

本文记录当前 WiredRV 核心控制面的约定，作为后续修改前的对照表。本轮整理不改变外部接口、总线协议、difftest 输出和 cache/流水线微架构。

## Redirect 和 flush 优先级

`Backend` 向 `Frontend` 输出 3 路 redirect，`Frontend` 再补充 icache replay 和前端预测 redirect。`NPC` 按 Vec 下标从低到高确定优先级：

1. `io.redirects(0)`：W 阶 redirect，包括 dcache miss replay、trap、mret、flushPipe、cached misaligned、uncached/AMO。
2. `io.redirects(1)`：E 阶分支实际结果和预测不一致时的 branch redirect。
3. `io.redirects(2)`：D 阶早期跳转 redirect，包括未预测的 JAL 和 JALR。
4. `frontMissReplay`：icache miss refill 启动后的前端 replay。
5. `frontPredRedirect`：前端最低优先级的 JAL/branch 预测跳转。

`Frontend` 中 `flushRedirects` 包含前 4 类，会清空 fetch response queue；`frontPredRedirect` 也会触发 response queue flush，但优先级最低。`ifencei` 会清空 icache valid 和前端 response queue，并清除 tensor64 的半条指令状态。

## 前后端 valid-ready

`Frontend` 的 F0 只在以下条件同时满足时发起 SRAM 读：

- F2 没有因为后端背压持有有效指令；
- 当前不在 icache refill；
- 本周期没有启动新的 refill；
- response queue 有空间；
- icache tag/data SRAM read port ready；
- 没有 `ifencei`。

`Backend` 的 D 阶通过 `io.inst.ready := !d_stall` 向前端施加背压。F2 使用 `f2Valid/f2Inst` 保持一个已接受但尚未被后端取走的 fetch packet；后端 redirect 到来时，该持有状态允许被 flush 覆盖。

## 后端 D/E/W 控制

D 阶 stall 被拆成四类，便于检查：

- `d_data_hazard_stall`：scoreboard、E/W 相关、LSU base 早转发不满足、JALR 源寄存器未就绪。
- `d_long_latency_stall`：vslot 不足、Tensor 响应占用、Div/Mul 长延迟资源占用。
- `d_lsu_cache_stall`：dcache/refill busy、uncached busy、store queue 或前序 store/AMO 等待。
- `d_ordering_stall`：fence、misaligned、NEMU trap 等需要流水线顺序化的等待。

E 阶负责 ALU/branch 实际结果和 dcache lookup。W 阶是普通提交、trap/mret、LSU 慢路径启动、Tensor issue、MDU 启动和 difftest commit 的主要控制点。

## LSU 慢路径状态机

`BackendLsu` 有 3 个互斥慢路径状态机：

- `r_state`：dcache refill、victim writeback 和 CMO。
- `m_state`：cached misaligned load/store。
- `u_state`：uncached load/store 和 AMO。

W 阶只在三个状态机都 idle 时启动新的慢路径。总线仲裁优先级保持为 `r_state > m_state > u_state`，即 refill/CMO 优先，其次 misaligned，最后 uncached/AMO。新增 assert 用于检查同一周期不会有多个慢路径总线用户重叠。

## 慢提交和 vslot 释放

长延迟写回有三类来源：MDU、Tensor response、LSU slow commit。`vAllocator.release(0)` 用于 E 阶 LSU 快速释放、LSU slow release，以及 flush 后取消释放的 pending buffer；`release(1)` 用于普通长延迟写回或 Tensor response。普通 W 写回和长延迟写回禁止写同一个 GPR，已有 assert 保持这个约束。

LSU slow commit 会覆盖普通 W commit 输出，用于 uncached、misaligned 和部分慢路径访问完成后的 difftest/commit 上报。

## 建议检查命令

```bash
wsl --cd /home/anyifei/code/WiredRV make compile
wsl --cd /home/anyifei/code/WiredRV make verilog
```

可选动态检查：

```bash
wsl --cd /home/anyifei/code/WiredRV make emu-riscv-test NO_DIFF=1 ISA_SUITE=rv64ui TEST=add
wsl --cd /home/anyifei/code/WiredRV make emu-riscv-test NO_DIFF=1 ISA_SUITE=rv64ui TEST=beq
wsl --cd /home/anyifei/code/WiredRV make emu-riscv-test NO_DIFF=1 ISA_SUITE=rv64ui TEST=jal
wsl --cd /home/anyifei/code/WiredRV make emu-riscv-test NO_DIFF=1 ISA_SUITE=rv64ui TEST=jalr
wsl --cd /home/anyifei/code/WiredRV make emu-riscv-test NO_DIFF=1 ISA_SUITE=rv64ui TEST=fence_i
wsl --cd /home/anyifei/code/WiredRV make emu-interrupt-test
```
