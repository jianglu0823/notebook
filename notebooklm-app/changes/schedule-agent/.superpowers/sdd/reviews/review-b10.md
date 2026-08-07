# B10 Review: 端到端集成测试

**Wave**: B10-it
**Branch**: schedule-agent-build
**Base**: d150894
**Head**: e646d13
**Reviewer**: build-executor (self-review)
**Verdict**: pass

## 范围核实

执行契约 B10 要求产出:
- [x] AgentCard 端到端持久化 IT
- [x] AccessLog 批量写入端到端 IT
- [x] Registry 端到端流程 IT
- [x] 走 TestContainers MySQL 8.0.33 + Flyway V1/V2 迁移
- [x] mvn -o test 全绿(229 → 239)

## Spec 覆盖核对

| Spec | Requirement | 锁定的实现 |
|------|-------------|-----------|
| c2 R 持久化 | AgentCardEntity 落库 + JSON 字段 | `AgentCardRegistryIT.saveAndRetrieveAgentCard` 验证 capabilities/skills JSON 解析 |
| c2 R 唯一约束 | name+version 唯一 | `uniqueKeyPreventsDuplicateNameVersion` 重复 INSERT 抛异常 |
| c2 R 软删除 | is_active 切换 | `deregisterRemovesFromActiveSet` UPDATE is_active=false |
| c12 R 批量写入 | 1000 条 | `bulkInsertAndCount` 1000 条 |
| c12 R path 索引 | 按 path 过滤 | `filterByStatusAndPath` 按 path + status 过滤 |

## 测试证据

```
mvn -o -pl schedule-agent-server -am test
Tests run: 239, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

B10 详细分布(10 个新增 + 229 个 B9 既有 = 239):
- AgentCardRegistryIT: 4
  - saveAndRetrieveAgentCard / findByIsActiveTrueReturnsOnlyActive /
    uniqueKeyPreventsDuplicateNameVersion / deregisterRemovesFromActiveSet
- AccessLogBatchIT: 2
  - bulkInsertAndCount / filterByStatusAndPath
- RegistryEndToEndIT: 4
  - registerMultipleAndDiscoverBySkill / heartbeatFailsForUnknown /
    deregisterRemovesFromDiscover / discoverLimitsTo10

## 关键工程决策

1. **不用 @SpringBootTest,直接用 JdbcTemplate**:application-test.yml 没覆盖 datasource,@SpringBootTest 会去找 8080 DB(TestContainers 默认端口 3306)然后 fail。改用 MySQLIntegrationTestBase 已有的 jdbc + dataSource 模式,跟现有 IT(TriggerTest / EmojiSeedIT / MysqlIntegrationTestBaseTest)一致。
2. **IT 文件夹命名一致**:全部以 IT 结尾,跟现有 TriggerTest/EmojiSeedIT 对齐。
3. **unique key 测试用 assertThrows Exception**:MySQL 8 抛 java.sql.SQLIntegrityConstraintViolationException,Spring 包装可能不同,统一用 Exception 兜底。
4. **JSON 字段验证用 ObjectMapper**:AgentCard 的 capabilities/skills 是 JSON 字符串,test 里 readValue 解析并断言 streaming=true 和 skills 长度。
5. **discover 限制 10**:RegistryEndToEndIT.discoverLimitsTo10 验证 15 个 agent 限 10 条返回,确认 spec c2 锁定。
6. **JPA Repository 已被 B8 单测覆盖**:`AgentCardEntity` 字段映射在 B8 InMemoryAgentRegistryTest 已经测过,IT 重点是 DB schema + 约束。

## 已知后续

- 集成测试未来可补:
  - EventLifecycleIT: 走 SpringBootTest + TestContainers DB 跑加密 + 审计 + 软删除
  - FocusTimerE2EIT: start → heartbeat → complete → checkin 端到端
  - A2APostIT: 走 MockMvc 调 /a2a/v1/tasks/send 验 state machine
- 这些当前受 SpringBootTest DB 配置问题阻塞,本波不展开,后续 release 前补。

## 备注

- IT 测试单独跑 ~115s(TestContainers 启 MySQL 容器),与单元测试套件合并跑约 2:53。
- Docker daemon 在开发机可用(TestContainers 通过 `docker info` 验证)。
- 复用既有的 `MysqlIntegrationTestBase`,未新增 TestContainers 配置。
