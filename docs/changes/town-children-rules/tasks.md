# Tasks: town-children-rules

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `notebooklm-app/src/main/java/io/llmnote/world/config/WorldChildrenProperties.java` | CREATE | `@ConfigurationProperties("world.children")` POJO holding `avatarPool` + `maxPerCouple` with defaults; exposes `pickAvatar(name)` deterministic helper |
| `notebooklm-app/src/main/java/io/llmnote/world/AgentEmployeeRepository.java` | MODIFY | Add `List<AgentEmployee> findByParentIdsIsNotNull()` for migration scan |
| `notebooklm-app/src/main/java/io/llmnote/world/AgentEmployeeService.java` | MODIFY | `birth()` replaces hardcoded `"👶"` with `props.pickAvatar(name)`; constructor gains `WorldChildrenProperties` |
| `notebooklm-app/src/main/java/io/llmnote/world/WorldSimEngine.java` | MODIFY | `childbirths()` counts existing children per couple before the 8% roll; skip if count >= `props.maxPerCouple`; constructor gains `WorldChildrenProperties` |
| `notebooklm-app/src/main/java/io/llmnote/world/MigrateChildDataRunner.java` | CREATE | `CommandLineRunner`: re-roll avatar for every child, clear `pregnantSince`/`dueDate` for over-cap couples, log summary, per-row try/catch |
| `notebooklm-app/src/main/resources/application.yml` | MODIFY | Add commented `world.children:` section showing the keys + defaults |
| `notebooklm-app/src/test/java/io/llmnote/world/config/WorldChildrenPropertiesTest.java` | CREATE | Verifies defaults, custom config wins, `pickAvatar` is in-pool and deterministic |
| `notebooklm-app/src/test/java/io/llmnote/world/WorldSimEngineChildbirthThrottleTest.java` | CREATE | Verifies couple at cap does not conceive; couple below cap does; cap=1 takes effect |
| `notebooklm-app/src/test/java/io/llmnote/world/MigrateChildDataRunnerTest.java` | CREATE | Verifies empty DB / mixed / idempotency / partial-failure scenarios |

## Interfaces

Consumes / produces across batches (types exact, no `Object`):

- `WorldChildrenProperties.getAvatarPool(): List<String>` — read by `AgentEmployeeService.birth()` and `MigrateChildDataRunner`.
- `WorldChildrenProperties.getMaxPerCouple(): int` — read by `WorldSimEngine.childbirths()` and `MigrateChildDataRunner`.
- `WorldChildrenProperties.pickAvatar(String childName): String` — returns `pool.get(Math.floorMod(childName.hashCode(), pool.size()))`. Returns `"👶"` if pool is empty (R-1 empty-pool scenario).
- `AgentEmployeeRepository.findByParentIdsIsNotNull(): List<AgentEmployee>` — new repo method; returns rows where `parentIds IS NOT NULL AND parentIds != ''`.
- `AgentEmployeeService.birth(String name, String avatar, String persona, String homePlace, Long parentAId, Long parentBId, LocalDate birthDate): AgentEmployee` — signature unchanged; the `avatar` parameter is now ignored in favor of `props.pickAvatar(name)`. Callers (currently only `WorldSimEngine.childbirths()`) keep their call sites.
- `WorldSimEngine.childbirths(List<AgentEmployee> active, LocalDate date, List<Map<String, Object>> news, List<Map<String, Object>> highlights): int` — signature unchanged; new internal precheck before the 8% roll.
- `MigrateChildDataRunner.run(): void` — standard `CommandLineRunner` entry; returns nothing, logs to SLF4J.

## Per-Task Breakdown

### Batch 1: Foundation (POJO + repo + config) — depends on nothing

#### T1. Add `WorldChildrenProperties` with defaults and `pickAvatar`

**Files**: `notebooklm-app/src/main/java/io/llmnote/world/config/WorldChildrenProperties.java` (CREATE), `notebooklm-app/src/test/java/io/llmnote/world/config/WorldChildrenPropertiesTest.java` (CREATE)

**TDD phases**:
1. RED: write `WorldChildrenPropertiesTest` with 3 cases:
   - `defaults_appliedWhenNoConfig`: instantiate via no-arg ctor, assert pool is the 9-emoji list, maxPerCouple == 3.
   - `customConfigWins`: set fields directly (or via `@TestPropertySource`-style, but for unit we set fields), assert they stick.
   - `pickAvatar_isInPoolAndDeterministic`: with default pool, call `pickAvatar("墨小宝")` 10 times → all equal; result is in pool; for 5 distinct names across default pool we see ≥ 2 distinct returns.
2. Run `./mvnw -pl notebooklm-app test -Dtest=WorldChildrenPropertiesTest` → fail (class missing).
3. GREEN: create `WorldChildrenProperties` with `@ConfigurationProperties("world.children")`, `@Data` (Lombok), `private List<String> avatarPool = List.of("👶","👧","👦","🧒","🧒🏻","🧒🏼","🧒🏽","🧒🏾","🧒🏿")`, `private int maxPerCouple = 3;`, and `public String pickAvatar(String name) { if (avatarPool == null || avatarPool.isEmpty()) return "👶"; return avatarPool.get(Math.floorMod(name == null ? 0 : name.hashCode(), avatarPool.size())); }`.
4. Run tests → green.
5. REFACTOR: none needed.

**Interfaces**:
- Consumes: nothing (defaults set in field initializers).
- Produces: `getAvatarPool()`, `getMaxPerCouple()`, `pickAvatar(String)`.

**Depends on**: nothing.

#### T2. Add `findByParentIdsIsNotNull()` to `AgentEmployeeRepository`

**Files**: `notebooklm-app/src/main/java/io/llmnote/world/AgentEmployeeRepository.java` (MODIFY)

**TDD phases**:
1. RED: not needed for a Spring Data derived query — verify with manual integration in T8 instead. Skip standalone test.
2. Skip.
3. GREEN: add `List<AgentEmployee> findByParentIdsIsNotNull();` (Spring Data derives: `WHERE parent_ids IS NOT NULL`). Also add a comment noting that the existing `findByParentIdsContaining` is left for callers that need partner-id-based lookup.
4. Skip.
5. REFACTOR: none.

**Interfaces**:
- Consumes: nothing.
- Produces: `findByParentIdsIsNotNull(): List<AgentEmployee>`.

**Depends on**: nothing.

#### T3. Add `world.children` keys to `application.yml`

**Files**: `notebooklm-app/src/main/resources/application.yml` (MODIFY)

**TDD phases**:
1. RED: not applicable (config, not code).
2. Skip.
3. GREEN: append a new top-level section with the two keys commented out and an example, plus a one-line comment explaining the defaults match `WorldChildrenProperties`. Example:
   ```yaml
   world:
     children:
       # avatar-pool: ["👶","👧","👦","🧒","🧒🏻","🧒🏼","🧒🏽","🧒🏾","🧒🏿"]
       # max-per-couple: 3
   ```
4. Skip.
5. REFACTOR: none.

**Interfaces**: external config only.

**Depends on**: T1 (so the keys reference a class that exists).

### Batch 2: Apply rules to new births — depends on Batch 1

#### T4. Wire `WorldChildrenProperties` into `AgentEmployeeService.birth()`

**Files**: `notebooklm-app/src/main/java/io/llmnote/world/AgentEmployeeService.java` (MODIFY)

**TDD phases**:
1. RED: in `WorldChildrenPropertiesTest` (already written in T1) add a 4th case `pickAvatar_sameAsBirthMethodCall`: build an `AgentEmployeeService` with a mock repo + `props.pickAvatar("墨小宝")`, call the helper, assert the result equals the value that `birth()` would have stored. (We test the helper directly; the `birth()` integration is verified via T6.)
2. Skip — covered by T1's test.
3. GREEN: change `birth()` line 278 from `e.setAvatar(safe(avatar, "👶"))` to `e.setAvatar(props.pickAvatar(name))`. Add `private final WorldChildrenProperties props;` to the class. Update the constructor (the class already has Lombok `@RequiredArgsConstructor` style — add the new field, Lombok regenerates the ctor). `safe(avatar, "👶")` is no longer needed; remove.
4. Skip.
5. REFACTOR: drop the now-unused `safe(avatar, "👶")` import if any.

**Interfaces**:
- Consumes: `props.pickAvatar(name)`.
- Produces: `birth()` persists `avatar = pickAvatar(name)`.

**Depends on**: T1.

#### T5. Add couple cap check in `WorldSimEngine.childbirths()`

**Files**: `notebooklm-app/src/main/java/io/llmnote/world/WorldSimEngine.java` (MODIFY), `notebooklm-app/src/test/java/io/llmnote/world/WorldSimEngineChildbirthThrottleTest.java` (CREATE)

**TDD phases**:
1. RED: write `WorldSimEngineChildbirthThrottleTest` with 3 cases:
   - `coupleAtCap_doesNotConceive`: mock `employeeRepo` to return 3 children with `parent_ids = "10,11"`, invoke the throttle helper (or `dailySettlement` if easier to test through), assert `pregnantSince` / `dueDate` stay null and no `pregnancy` news added.
   - `coupleBelowCap_conceivesAsBefore`: 0 existing children, fertile ages, mocked random returns 5 (≤ 8), assert `pregnantSince` is set.
   - `capOne_takesEffect`: maxPerCouple=1, 1 child exists, 0% conceive even with random=1.

   Note: `WorldSimEngine.dailySettlement()` orchestrates many sub-engines; we'll test the cap check by either (a) extracting a private `shouldSkipPregnancy(a, b, byId)` helper or (b) running the full `childbirths()` with the rest stubbed. (a) is cleaner; use it.

2. Run tests → fail (helper missing).
3. GREEN: extract `private boolean coupleAtCap(AgentEmployee a, AgentEmployee b, Map<Long, AgentEmployee> byId)`. Count children via `employeeRepo.findByParentIdsContaining(String.valueOf(a.getId()))`, filter in Java for rows whose `parentIds` split by `,` contains both `a.getId()` and `b.getId()` (handle empty strings), return `count >= props.getMaxPerCouple()`. In `childbirths()`, before the `rnd(100) >= 8` line, call this and `continue` if true. Add `WorldChildrenProperties props` to constructor.
4. Run tests → green.
5. REFACTOR: extract the splitting/intersection into a tiny `static Set<String> splitParents(String parentIds)` helper for testability.

**Interfaces**:
- Consumes: `props.getMaxPerCouple()`, `employeeRepo.findByParentIdsContaining(Long)`.
- Produces: `childbirths()` no longer creates pregnancies for capped couples.

**Depends on**: T1, T2.

#### T6. Verify integration of T4 + T5 with one end-to-end test

**Files**: `notebooklm-app/src/test/java/io/llmnote/world/WorldSimEngineChildbirthThrottleTest.java` (MODIFY — add 1 case)

**TDD phases**:
1. RED: add `e2e_birthUsesPoolAndCapApplies`: invoke `WorldSimEngine` (or its `childbirths` driver) end-to-end with the `WorldChildrenProperties` injected; assert the born child's `avatar` is from the configured pool AND a follow-up daily settlement with the same couple does not conceive a 4th child.
2. Run → fail.
3. GREEN: nothing to add — implementation already in T4 + T5. Re-run.
4. Green.
5. REFACTOR: none.

**Interfaces**: verifies cross-module behavior.

**Depends on**: T4, T5.

### Batch 3: Historical migration — depends on Batch 2

#### T7. Create `MigrateChildDataRunner` skeleton

**Files**: `notebooklm-app/src/main/java/io/llmnote/world/MigrateChildDataRunner.java` (CREATE)

**TDD phases**:
1. RED: write `MigrateChildDataRunnerTest.emptyDb_doesNothing` (in the test file we'll add to in T8). Mock repo to return `List.of()` for `findByParentIdsIsNotNull()`. Construct runner with default `WorldChildrenProperties`. Call `run()`. Assert: 0 `save()` calls, INFO log line with both counts = 0. (We'll use a simple `@Slf4j`-with-arg constructor for testability; verify by capturing the log via a `ListAppender` or just by mocking the repo and asserting no save.)
2. Run → fail.
3. GREEN: create `MigrateChildDataRunner` with `@Component`, `@RequiredArgsConstructor`, `@Slf4j`. Fields: `AgentEmployeeRepository repo`, `WorldChildrenProperties props`. `run()`: load list, log INFO `childrenReavatar=0 couplesCleared=0`, return.
4. Run → green.
5. REFACTOR: none.

**Interfaces**:
- Consumes: `repo.findByParentIdsIsNotNull()`, `props.getAvatarPool()`, `props.getMaxPerCouple()`.
- Produces: a single INFO log per run; no DB writes yet.

**Depends on**: T1, T2.

#### T8. Implement avatar re-roll + couple clearing + per-row try/catch

**Files**: `notebooklm-app/src/main/java/io/llmnote/world/MigrateChildDataRunner.java` (MODIFY), `notebooklm-app/src/test/java/io/llmnote/world/MigrateChildDataRunnerTest.java` (MODIFY — add 3 cases)

**TDD phases**:
1. RED: extend `MigrateChildDataRunnerTest` with:
   - `mixedDb_reavatarsChildrenAndClearsOverCap`: mock repo `findByParentIdsIsNotNull()` to return 10 children (5 for couple 10,11; 5 for couple 12,13) plus adults (return only the children from this method). Mock partner lookup to return 2 parents each with `pregnantSince` set. Assert: 10 `save()` calls (children re-avatar'd), 4 more `save()` calls (2 parents × 2 couples), `pregnantSince` cleared. Verify the `avatar` set on each saved child is in the default pool.
   - `idempotent_secondRunMakesNoChanges`: run the runner twice in the same test. After the first run, capture the saved avatar values. Mock the repo to return the children with those avatar values on the second call. Assert no new saves on the second run.
   - `partialFailure_doesNotCrashStartup`: make `repo.save()` throw on the 3rd child. Assert `run()` completes, logs a WARN line (verified by counting log calls or via ListAppender), and continues to save the remaining 9.

2. Run → fail.
3. GREEN: in `run()`:
   - Get `List<AgentEmployee> children = repo.findByParentIdsIsNotNull();`.
   - `int reavatar = 0;` then for each child: try { `child.setAvatar(props.pickAvatar(child.getName())); repo.save(child); reavatar++; } catch (Exception e) { log.warn("migrate child {} failed: {}", child.getId(), e.getClass().getSimpleName()); }.
   - Group children by `parentIds`. For each `parentIds` group with size > `props.getMaxPerCouple()`: parse ids via `splitParents(parentIds)` helper, load each parent via `repo.findById()`, clear `pregnantSince`/`dueDate` if non-null, save. `int cleared = 0;` increment per couple.
   - Log INFO `migrate children: reavatar={N} couplesCleared={M}`.
4. Run → green.
5. REFACTOR: extract `splitParents(String parentIds): Set<Long>` static helper, shared with `WorldSimEngine` (move from T5's private static if useful; or duplicate — the helper is 4 lines and duplication is fine here for module-locality).

**Interfaces**:
- Consumes: all from T7.
- Produces: persists re-avatar'd children + cleared parents + 1 INFO + 0+ WARN logs.

**Depends on**: T5, T7.

#### T9. Document the migration in a code comment in `MigrateChildDataRunner`

**Files**: `notebooklm-app/src/main/java/io/llmnote/world/MigrateChildDataRunner.java` (MODIFY)

**TDD phases**:
1. RED: not applicable.
2. Skip.
3. GREEN: add a Javadoc above the class explaining: runs once at startup, idempotent, per-row failure isolated, why CommandLineRunner (after EMF ready, before HTTP listener).
4. Skip.
5. REFACTOR: none.

**Depends on**: T8.

## Dependency Graph

```
T1 ──┬──> T3
     └──> T4 ──┐
T2 ──┬──> T5 ─┴──> T6
     └──> T7 ──> T8 ──> T9
```

Implementation order: T1, T2 (parallel), T3, T4, T5, T6, T7, T8, T9.

## Requirement → Task Mapping

| Spec | T1 | T2 | T3 | T4 | T5 | T6 | T7 | T8 | T9 |
|------|----|----|----|----|----|----|----|----|----|
| R-1 (avatar from pool) | ✓ |   | ✓ | ✓ |   | ✓ |   |   |   |
| R-2 (couple cap)       | ✓ | ✓ |   |   | ✓ | ✓ |   |   |   |
| R-3 (config binding)   | ✓ |   | ✓ |   |   |   |   |   |   |
| R-4 (migration)        |   | ✓ |   |   |   |   | ✓ | ✓ | ✓ |

## Task Status (post-W3)

- [x] T1 — WorldChildrenProperties POJO + pickAvatar (W1 commit `ee72e84`)
- [x] T2 — `findByParentIdsIsNotNull()` repo method (W1 commit `ee72e84`)
- [x] T3 — `application.yml` world.children keys (W1 commit `ee72e84`)
- [x] T4 — `AgentEmployeeService.birth()` uses pickAvatar (W2 commit `8f6f579`)
- [x] T5 — `WorldSimEngine.coupleAtCap()` precheck (W2 commit `8f6f579`)
- [x] T6 — integration test for T4 + T5 (W2 commit `8f6f579`)
- [x] T7 — `MigrateChildDataRunner` skeleton (W3 commit `88b8f9b`)
- [x] T8 — full runner implementation + 6 tests (W3 commit `88b8f9b`)
- [x] T9 — runner class Javadoc (W3 commit `88b8f9b`)
