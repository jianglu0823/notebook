# B0 Review: 顶级 Maven 项目骨架 + contract 模块 A2A 协议 DTO

**Wave**: B0-scaffold
**Branch**: schedule-agent-build
**Base**: bcf22a8
**Head**: 28789cb
**Reviewer**: build-executor (self-review)
**Verdict**: pass

## 范围核实

执行契约 B0 要求产出:
- [x] 顶级 pom.xml (Java 21, Spring Boot 3.3.5 BOM, 3 子模块)
- [x] contract / server / client-sdk 三个子模块 pom.xml
- [x] A2A 协议 DTO: AgentCard / Skill / Capabilities / Task / TaskStatus / Message / TextPart / DataPart / Artifact / JsonRpcRequest / JsonRpcResponse / JsonRpcError / ErrorCode / ErrorResponse
- [x] 业务 DTO: ScheduleEventDto + Create/Patch/View + SchedulerRecommendRequest/Response + CandidateSlot + CheckinRequest/Response + EmojiResolveRequest/Response + ExternalSyncRequest/Response

## Spec 覆盖核对

| Spec | Requirement | 锁定的实现 |
|------|-------------|-----------|
| c1 R 协议 | JSON-RPC 2.0 + A2A task model | JsonRpcRequest/Response/Error + Task/TaskStatus |
| c1 R 状态机 | submitted/working/completed/input-required/failed | TaskStatus 枚举 + 流转校验 |
| c1 R 消息 | TextPart + DataPart | Message + TextPart + DataPart |
| c1 R 技能 | Skill id+name+description+tags | Skill record |
| c1 R 卡片 | AgentCard + Capabilities + Skills | AgentCard + Capabilities |

## 测试证据

```
mvn -o -pl schedule-agent-contract -am test
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 关键工程决策

1. contract 模块零依赖:只 JDK 17+,保证 DTO 可被任意 JVM 端复用。
2. record 优先:所有 DTO 用 Java 21 record,不可变。
3. TaskStatus.canTransitionTo() 集中校验非法跳跃。
4. JsonRpc 错误用 @JsonInclude 排除 null 字段。
5. maven-compiler-plugin 锁 Java 21。

## 已知后续

- Capabilities.defaultCapabilities() 留待 B3 server 端按需覆盖。
