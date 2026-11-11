# Design: town-children-rules

## Context

`AgentEmployeeService.birth()` (line 278) and `WorldSimEngine.dailySettlement().childbirths()` (line 576) currently hardcode `avatar = "👶"` for every newborn. `childbirths()` rolls 8% pregnancy per day per fertile married couple (lines 603-606) with a 30-day gestation, no upper bound on the number of children per couple. After enough simulated days the resident roster skews heavily toward identically-avatar'd children. The `world.children.*` configuration namespace does not exist yet.

A startup-time `CommandLineRunner` is the natural place to backfill historical data, because:
- It runs after the JPA `EntityManagerFactory` is ready (so we can `UPDATE` rows).
- It runs before the HTTP listener starts accepting traffic (no race with `dailySettlement()`).
- It's idempotent: re-running is safe, so a failed first attempt self-heals on next deploy.

## Goals

- Each new child gets a varied `avatar` from a configurable pool.
- Couples stop conceiving once they reach a configured cap; below the cap behavior is unchanged.
- Both knobs are externalized in `application.yml` with documented defaults.
- Existing children are migrated once at startup; the migration is idempotent and resilient to per-row failures.
- No schema changes.

## Decisions

### D-1. Avatar pool: externalized via `@ConfigurationProperties` POJO with hardcoded defaults

**Choice**: Add a `WorldChildrenProperties` class (under `io.llmnote.world.config`) annotated with `@ConfigurationProperties(prefix = "world.children")`. Two fields: `List<String> avatarPool` (default 9 child emoji) and `int maxPerCouple` (default 3). Loaded by `@EnableConfigurationProperties` on a `@Configuration` class or on the consuming service.

**Rationale**: Type-safe binding of a list is cleaner than `@Value("${world.children.avatar-pool}")` with string splitting. Defaults in code let a fresh deploy work with no `application.yml` keys present. Externalization matches existing patterns in this codebase (`NotebookLmProperties` is the precedent).

**Alternatives considered**:
- *Hardcoded only*: simplest, but user can't tune without redeploy. Rejected — DP-0 explicitly asked for a "rule" the user can change.
- *Database table*: overkill for 9 strings. Rejected.
- *Read YAML inside the service via `ResourceLoader`*: works, but bypasses Spring's binding story. Rejected for consistency.

### D-2. Avatar selection: deterministic, hash-derived from child name

**Choice**: Implement a `pickAvatar(String childName, List<String> pool)` function. Take `Math.floorMod(childName.hashCode(), pool.size())` to index into the pool. Call it from both `birth()` and the migration runner.

**Rationale**: A single function used in two places means the migration is naturally idempotent — re-applying the hash produces the same index, so already-migrated children keep their avatar. Pure `ThreadLocalRandom` would be non-reproducible and would shift avatars on every restart, breaking the spec's "idempotent" requirement.

**Alternatives considered**:
- *Pure `ThreadLocalRandom`*: simplest, but non-deterministic. Rejected — fails R-4 idempotency.
- *Hash + salt (e.g., `childName + parentId`)*: more uniform distribution. Rejected — the pool is only 9 entries; `name.hashCode()` is already enough to spread across 9 for the ~10-name word list used in `uniqueChildName()`.

### D-3. Couple identification: in-app intersection of `parent_ids` rows

**Choice**: For a couple `(a, b)`, count children via `employeeRepo.findByParentIdsContaining(String.valueOf(a.getId()))`, then in Java stream-filter for rows whose `parentIds` also contains `String.valueOf(b.getId())` (split on `,` and check membership). The count drives the cap check.

**Rationale**: The `parent_ids` column is a comma-separated `String` with no relational integrity. There's no `father_id` / `mother_id` separate column, and adding one would be a schema change (out of scope). Doing the second filter in Java keeps the SQL simple (`LIKE '%id%'` is already the existing query) and the per-call cost is small: a couple's children are typically < 5 rows in practice.

**Alternatives considered**:
- *Add `father_id` / `mother_id` columns*: cleaner model, but requires schema migration and re-derivation of values from existing `parent_ids`. Rejected for scope.
- *Use `spouseId` only, count children where one of the two `parent_ids` is the spouse*: misses children of a former spouse (the simulator allows re-marriage in theory). Rejected as imprecise.
- *Cache child counts in memory*: faster but stale on cross-pod deployments. Rejected.

### D-4. Migration runner: `CommandLineRunner`, two-pass algorithm

**Choice**: Implement `MigrateChildDataRunner implements CommandLineRunner`. Algorithm:
1. `List<AgentEmployee> children = repo.findByParentIdsContainingNotNull()` — use a custom repo method `findByParentIdsIsNotNull()`.
2. For each child: `child.setAvatar(pickAvatar(child.getName(), props.getAvatarPool()))`, `repo.save(child)`, catch and log per-row exceptions.
3. After step 2, build a `Map<String, Long>` of `parent_ids` → child count for rows where count > `maxPerCouple`. For each such `parent_ids`, find both parent `AgentEmployee` records, clear `pregnantSince` and `dueDate` if set, save.
4. Log single INFO line: `childrenReavatar={N} couplesCleared={M}`.

**Rationale**: Two passes let us apply the avatar (row-level) before deciding which couples to clear (aggregation). Step 3 needs the child counts after step 2, but the counts don't change between passes — only `avatar` does — so we can group by `parent_ids` directly off `children`.

**Alternatives considered**:
- *One big transaction wrapping all UPDATEs*: simplest rollback, but the row count could be hundreds in a long-running town; one lock could stall `dailySettlement()`. Rejected.
- *One transaction per row*: safest rollback, but slow. Rejected — the spec calls for "log + continue" on failure, which is per-row try/catch.
- *SQL `UPDATE ... CASE` for avatar (compute hash in SQL)*: portable SQLite/H2 won't support this well; JPA `save()` is portable. Rejected for cross-DB compat.

### D-5. Properties loading: bind on `AgentEmployeeService` and `WorldSimEngine` separately

**Choice**: Inject `WorldChildrenProperties` into both `AgentEmployeeService` (for the `birth()` method's avatar pick) and `WorldSimEngine` (for the cap check in `childbirths()`). Use Spring's constructor injection; add the field to existing constructors or use Lombok's `@RequiredArgsConstructor`.

**Rationale**: The two services have different lifecycles — `WorldSimEngine` runs in a scheduled thread, `AgentEmployeeService` is request-scoped from controllers. Constructor injection is consistent with the rest of the codebase (see `ForumController`).

**Alternatives considered**:
- *Static `final` from `@Value`*: no DI, but loses testability. Rejected.
- *A single shared `ChildrenRulesService` consumed by both*: would help if the rule application were complex, but here it's one method call. Overengineered. Rejected.

### D-6. Test surface: mock the properties POJO; no Spring context needed for unit tests

**Choice**: Unit tests for `WorldSimEngine.childbirths()` pass a `WorldChildrenProperties(avatarPool=DEFAULT_POOL, maxPerCouple=3)` directly. Migration tests for `MigrateChildDataRunner` use a mocked `AgentEmployeeRepository` (Mockito) — verify the right number of `save()` calls and the right avatar distribution.

**Rationale**: The unit-test infrastructure already mocks JPA repositories across the world/ module (see `ResidentInteractionJobTest`, `AuthorReplyListenerTest`). The migration runner doesn't need an in-memory H2 — repo mocks are sufficient for "given N children, when runner runs, then M saves happened".

**Alternatives considered**:
- *Full `@SpringBootTest` with H2*: tests real SQL but slow. Rejected for unit-level coverage.
- *Testcontainers MySQL*: most realistic, but overkill for the 4 scenarios. Rejected for the change's scale.

## Risks And Trade-Offs

- **Hash collisions on small pools**: 9-entry pool with `name.hashCode()` may map 2 common names to the same emoji. Scenario R-1 only requires "≥2 distinct values across N≥5 births", which 9 entries easily clear. Trade-off accepted: true uniformity would need a salt, but uniformity is not the goal.
- **Migration blocks startup**: a town with 10,000 children would issue 10,000 `save()` calls before HTTP starts. Mitigation: use `repo.saveAll()` in batches of 100 to bound per-round-trip latency. If startup becomes a problem, a future change can move the migration to a separate admin endpoint — explicitly out of scope here.
- **Race between runner and `dailySettlement()`**: the runner runs in the main thread before Spring Boot binds the HTTP port. `WorldSimEngine`'s scheduler is started by `@Scheduled` which activates on context refresh — context refresh completes before `CommandLineRunner.run()` returns. No race.
- **Couple ID parsing in `parentIds`**: the column may contain `"a,b"` or `"a,"` (single parent trailing comma per `birth()`). The intersection check should split on `,` and filter empty strings. Documented in code comment to prevent future "off-by-one" regressions.
- **Backward compatibility of `application.yml`**: existing deployments have no `world.children.*` keys. Defaults in `WorldChildrenProperties` cover this. No migration of `application.yml` is required.
- **Default pool content**: 9 emoji including skin-tone modifiers (`🧒🏻` etc.) is a stylistic choice. A user wanting a different default must edit the POJO or override via YAML. Out of scope to expose a "preset" enum.
