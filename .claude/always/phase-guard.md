# Phase Guard: forum-category-and-thumbs

**当前阶段**: approved-for-build | **工作流**: tweak

## ✅ 允许操作
- 选择执行模式（默认 SDD 或用户明确 override）
- 运行 ssf execution plan <change-dir> ... 生成 execution plan
- 运行 ssf execution show <change-dir> --json 核对 current、revision、mode、waves[].eligible 和 receipts
- 准备执行环境

## ⛔ 禁止操作
- full/hotfix 没有 current execution plan 时不得开始实现或转换到 executing；tweak 免除此 plan/receipt gate
- 修改 execution-contract.md（需回退到 bridging）
- 修改 proposal.md, specs/, design.md, tasks.md

## 🔔 决策点
- DP-4: full/hotfix 默认 SDD；Inline/Batch Inline 仅限用户明确 override。仅当 execution show 报告 current: true 后才可转换到 executing；tweak 免除此 plan/receipt gate