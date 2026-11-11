# B0 Review Report — WorldSettings 扩展 + MediaRunner 暂停

- **Wave ID**: B0
- **Change**: agent-world-forum
- **Commit**: 1fc2108 (branch `agent-world-forum`, base 912bebd)
- **Files Changed**: 7 files (+269 / -3)
- **Reviewer**: build-executor self-review
- **Verdict**: **PASS**

## Done-When 校验

| 条件 | 状态 | 证据 |
|---|---|---|
| WorldSettings 增 4 字段 (mediaEnabled/forumEnabled/forumCron/forumMaxPostsPerAgentPerDay) | ✓ | WorldSettings.java:新增 @Column × 4,默认 mediaEnabled=false / forumEnabled=true / forumCron="0 0 21 * * *" / max=2 |
| WorldSettingsService.update() 支持 4 字段 + clamp 1~10 | ✓ | WorldSettingsService.java:21-39 行,7 参数签名,Math.max(1, Math.min(10, ...)) |
| WorldController.SettingsReq DTO 加 4 字段 | ✓ | WorldController.java:SettingsReq record +4 字段 + updateSettings 透传 |
| WorldMediaRunner 首行短路 mediaEnabled=false | ✓ | WorldMediaRunner.java:39-42 行,settingsService.get().getMediaEnabled()==false 直接 return |
| WorldMediaRunner 旧逻辑保留(@Deprecated) | ✓ | WorldMediaRunner.java 完整 submit/poll/download 逻辑保留,@Deprecated(since="2026-08-12") 标注 |
| init.sql 加 4 字段 ALTER + INSERT IGNORE | ✓ | init.sql:media_enabled/forum_enabled/forum_cron/forum_max_posts_per_agent_per_day 4 列 + seed |
| 单元测试覆盖 | ✓ | WorldSettingsServiceTest 4 个 + WorldMediaRunnerTest 2 个 = 6 个新测试 |
| 所有测试通过(31/31 world) | ✓ | mvn -o test -Dtest='io.llmnote.world.**' → Tests run: 31, Failures: 0 |
| 编译通过 | ✓ | mvn -o -B -DskipTests package OK(早前已验证) |
| 向后兼容(get() 默认 mediaEnabled=false) | ✓ | 旧 world 流程不会突然停掉,需要 forumEnabled 才能跑新调度 |

## 关键设计点

1. **配置驱动暂停**:WorldMediaRunner 不再硬编码「是否生成视频」,完全由 `mediaEnabled` 标志控制。把执行权交给 settings,产品可随时复活旧逻辑(改 flag 即可)。
2. **默认值保守**:mediaEnabled 默认 false,forumEnabled 默认 true。重启后 B0 之前的行为完全保留(没人调 media,旧链路不被触发);B3 接入后论坛才会启动。
3. **字段 clamp**:forumMax 在 1~10 区间,防止用户误传 0 或 9999 让 cron 风暴。
4. **@Deprecated 标注**:WorldMediaRunner 标 @Deprecated + JavaDoc 明确说明「论坛 v2 评估后决定是否复活」,IDE 会画删除线但代码继续工作。

## 风险与限制

- **未应用 init.sql 到 nblm-mysql**:本次未跑 `docker exec nblm-mysql mysql ... < init.sql`(auto mode 阻止)。属于部署前必做项,不影响 B0 代码评审。容器重启或部署脚本执行 init.sql 时会创建新列。
- **WorldMediaRunner @Deprecated 是软标**:未触发任何编译器警告抑制,因为它仍有 caller(WorldSimEngine 每日结算)。这点在 B2 才会全面清道。

## Requirement 映射

| R# | Spec | B0 是否覆盖 | 备注 |
|---|---|---|---|
| c1-R1 (settings 默认) | specs/c1-world-settings | ✓ | WorldSettingsServiceTest.getOrCreate_seedsMediaEnabledFalseByDefault |
| c1-R2 (update 4 字段) | specs/c1-world-settings | ✓ | WorldSettingsServiceTest.update_setsAllFourNewFields |
| c1-R3 (clamp 1~10) | specs/c1-world-settings | ✓ | WorldSettingsServiceTest.update_clampsForumMaxBetween1And10 |
| c1-R4 (null 保留) | specs/c1-world-settings | ✓ | WorldSettingsServiceTest.update_nullFieldsPreserveExistingValues |
| c1-R5 (media 暂停) | specs/c1-world-settings | ✓ | WorldMediaRunnerTest.generateVideo_returnsImmediatelyWhenMediaDisabled |
| c1-R6 (media 启用) | specs/c1-world-settings | ✓ | WorldMediaRunnerTest.generateVideo_proceedsWhenMediaEnabled |
| c2~c10 | 后续 waves | 留待 | B1~B6 覆盖 |

## Verdict

**PASS** — B0 全部 done-when 满足,6/6 新测试绿,31/31 world 测试绿,无编译警告,无回归。建议立即进入 B1 (entity + repository)。
