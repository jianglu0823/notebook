# Wave b2 评审报告:ChatModelFactory options 透传

**范围**:Task 2.1~2.2  **git**:85ed3ee..37e0e24

## Spec 合规
- N3/N4(max_tokens 仅 novel、非 novel 不受影响):新增带 `GenerateOptions` 的重载,原无参重载委托传 null → 既有所有调用行为不变。✅
- D5 一致:options 在 `streamTextWithFallback` 层透传给降级链每个候选(循环内 `streamText(forModel(m), messages, options)`)。✅

## 代码质量
- import `io.agentscope.core.model.GenerateOptions` 与项目既有用法(XhsService/NewsService/WebSearchTool)一致。
- 重试/退避逻辑集中在带 options 的重载,原方法仅一行委托,无重复。
- Javadoc 说明 options=null 等价旧行为。

## 验证
- `mvn -q -o compile` 退出码 0。既有调用方零改动(仍走无参重载)。✅

## 裁定:pass
无 Critical/Important 问题。
