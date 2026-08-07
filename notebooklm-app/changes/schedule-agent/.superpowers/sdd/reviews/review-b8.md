# B8 Review: A2A Agent 注册 + 心跳 + Nacos 降级

**Wave**: B8-registry
**Branch**: schedule-agent-build
**Base**: 27de264
**Head**: 88f02dd
**Reviewer**: build-executor (self-review)
**Verdict**: pass

## 范围核实

执行契约 B8 要求产出:
- [x] `AgentCardEntity` JPA (uk `agent_name+version`, capabilities/skills JSON)
- [x] `AgentCardRepository` findByAgentNameAndVersion / findByIsActiveTrue / deactivateOthers / deactivateAllByName
- [x] `AgentRegistry` 接口 register / heartbeat / discover / deregister / type / withFallback
- [x] `InMemoryAgentRegistry` ConcurrentHashMap 实现,默认 dev
- [x] `NacosAgentRegistry` Nacos HTTP API + 失败返回空集/抛 RegistryException
- [x] `AgentCardFactory` 6 skill + @Value 注入 name/version/url
- [x] `AgentRegistrationTask` @PostConstruct 启动注册 + 30s @Scheduled 心跳 + 3 次失败 re-register
- [x] `AgentRegistryController` GET /a2a/v1/agent-card + /a2a/v1/agent-card/discover?skill=
- [x] @ConditionalOnProperty 互斥(type=in-memory|nacos,默认 in-memory)

## Spec 覆盖核对

| Spec | Requirement | 锁定的实现 |
|------|-------------|-----------|
| c2 R 卡片 | name/version/url/capabilities/skills 必填 | `AgentCardFactory.build()` 6 skill 完整 |
| c2 R 心跳 | 启动时注册 + 定期心跳 | `AgentRegistrationTask.register()` + `@Scheduled(30s)` |
| c2 R 发现 | 按 skill 查 + latencyMs 升序 + 限制 10 | `InMemoryAgentRegistry.discover()` `sorted+limit(10)` |
| c2 R 降级 | 外部不可用 fallback in-memory | `AgentRegistrationTask.register()` catch 路径 |
| c2 R 类型 | registry.type() 暴露 | `InMemoryAgentRegistry.type()=in-memory`,`NacosAgentRegistry.type()=nacos` |
| c2 R 启动注册 | @PostConstruct 启动即注册 | `AgentRegistrationTask.register()` |
| c2 R 重连 | 连续失败重试 | 3 次失败 → `register()` 再调 |
| c2 R 注销 | @PreDestroy 关停 | `AgentRegistrationTask.deregister()` |
| c2 R 端点 | /a2a/v1/agent-card | `AgentRegistryController.get()` |
| c2 R discover | /a2a/v1/agent-card/discover?skill= | `AgentRegistryController.discover(skill)` |
| c2 R 数据 | 持久化 AgentCardEntity | `schedule_agent_card` 表 + JSON 字段 |

## 测试证据

```
mvn -o -pl schedule-agent-server -am test
Tests run: 207, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

B8 详细分布(30 个新增 + 177 个 B7 既有 = 207):
- AgentCardFactoryTest: 3
  - buildReturnsAllSixSkills / ownReturnsDefaultAgent / gettersExposeFields
- InMemoryAgentRegistryTest: 9
  - registerAndDiscoverReturnsSameCard / discoverFiltersBySkillId / discoverReturnsEmptyForUnknownSkill /
    discoverIsCaseInsensitiveOnSkill / heartbeatReturnsTrueForRegistered / heartbeatReturnsFalseForUnregistered /
    deregisterRemovesCard / registerIsIdempotentForSameKey / typeReturnsInMemory
- NacosAgentRegistryTest: 7
  - registerPostsToNacosInstanceApi / registerThrowsRegistryExceptionOnFailure /
    heartbeatReturnsTrueOn2xx / heartbeatReturnsFalseOnFailure / discoverReturnsEmptyOnFailure /
    discoverParsesHostsAndFiltersBySkill / typeReturnsNacos
- AgentRegistrationTaskTest: 6
  - registerCallsInMemoryWhenNacosMissing / registerDoesNotAuditWhenPrimarySucceeds /
    registerFallsBackAndAuditsWhenPrimaryThrows / heartbeatOnSuccessResetsFailures /
    heartbeatOnFailureTriggersReregisterAfterThree / deregisterCallsActive
- AgentRegistryControllerTest: 5
  - getReturnsOwnCard / discoverDelegatesToActiveRegistry / discoverReturnsEmptyWhenActiveIsNull /
    discoverRejectsBlankSkill / discoverRejectsNullSkill

## 关键工程决策

1. **互斥用 type 而不是 url**:`@ConditionalOnProperty(name="schedule-agent.registry.type", havingValue=...)` 比 URL 后缀匹配更稳定。Nacos url 配置独立成 `schedule-agent.registry.url`。
2. **InMemoryAgentRegistry 注入与否通过 type 决定**:Spring 默认在 type=in-memory 时注入,type=nacos 时 InMemory 走 @ConditionalOnProperty(matchIfMissing=true) 同样生效。互斥由两个 @ConditionalOnProperty 保证。
3. **AgentRegistry.withFallback() 不再 new InMemoryAgentRegistry**:默认方法只注册自身,fallback 由 AgentRegistrationTask(了解 Spring 容器)处理。`new InMemoryAgentRegistry(null)` 在接口里跑不通(InMemoryAgentRegistry 无构造器)。
4. **NacosAgentRegistry 用 RestTemplate**:Spring Boot starter-web 自带,无需新增依赖。`POST /nacos/v1/ns/instance` form 提交。
5. **discover 解析 list 响应容错**:`Map<String, Object>` 解析,metadata.name/version 缺失时返回 null(被 filter 过滤),不会 NPE。
6. **心跳失败不抛错**:心跳失败返 false,主流程不抛。3 次连续失败才触发 re-register,避免抖动。
7. **审计 actor="system"**:AgentRegistrationTask 自身无用户上下文,统一前缀便于区分。
8. **baseUrl 默认 localhost:8081**:与 server.port 默认对齐,生产可通过 `schedule-agent.agent.url` 覆盖。
9. **AgentCardEntity 表 schedule_agent_card**:与 c2 spec 锁定,uk 通过 MySQL DDL 唯一索引保证(`agent_name` + `version`),JPA 层用 `findByAgentNameAndVersion`。
10. **is_active 字段预留**:虽然当前 InMemoryAgentRegistry 不读 is_active,Entity 已建字段,后续 Nacos 模式可由 scheduler 标记。

## 已知后续

- Nacos 鉴权:当前 BasicAuth 头通过 env 注入,生产建议 OAuth2/JWT 流程。spec 未锁定 v1 范围。
- AgentCard metadata 透传:Nacos 注册时 metadata 字段塞 name/version/skills(逗号分隔字符串),读取时只取 name/version,skills 留给调用方根据 skill.id 再次过滤。
- is_active 调度:未实现定期清理不活跃 agent 任务,Entity 已建字段后续 wave 补。
- B10 集成测试覆盖:启动后 GET /a2a/v1/agent-card 返回 6 skill 完整 + discover 自身。
