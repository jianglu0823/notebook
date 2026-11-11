# Spec: children-rules

This delta spec captures behavior changes around agent childbirths: avatar diversity, per-couple birth throttle, and a one-shot historical migration.

## MODIFIED Requirements

### Requirement: Newborn child avatar MUST come from a configurable emoji pool

The system MUST pick a child's `avatar` field from `world.children.avatar-pool` (an ordered list of emoji strings) at the moment of birth, instead of using a single hardcoded value. The selection MUST be deterministic given (pool, child name) so that re-runs and tests reproduce. The pool MUST default to a non-empty list when `application.yml` does not provide one.

#### Scenario: Birth with default pool

- **WHEN** `AgentEmployeeService.birth(...)` is called and `application.yml` has no `world.children.avatar-pool` key
- **THEN** the persisted `AgentEmployee.avatar` is one of the 9 default child emoji (currently `["👶", "👧", "👦", "🧒", "🧒🏻", "🧒🏼", "🧒🏽", "🧒🏾", "🧒🏿"]`)
- **AND** is NOT a constant across multiple births (a test with N≥5 births observes ≥2 distinct values with the default pool)

#### Scenario: Birth with configured custom pool

- **WHEN** `application.yml` sets `world.children.avatar-pool: ["🐣", "🐤"]`
- **THEN** the persisted `avatar` is either `🐣` or `🐤`
- **AND** never a value outside the configured pool

#### Scenario: Empty pool falls back

- **WHEN** `world.children.avatar-pool` is configured as an empty list
- **THEN** the persisted `avatar` is the literal `👶` (preserves pre-change behavior; the system MUST NOT throw)

#### Scenario: Same parents, multiple children produce varied avatars

- **WHEN** the same couple has 3 children born at different times with the default pool
- **THEN** the 3 children's `avatar` values are not all identical (at least 2 distinct values across the 3)

### Requirement: Couples SHALL stop conceiving after reaching the configured per-couple child cap

`WorldSimEngine.dailySettlement().childbirths()` MUST count existing children for the couple (using `parent_ids` containing both `a.id` and `b.id`) before allowing a new pregnancy on any given day. If the count meets or exceeds `world.children.max-per-couple` (default 3), the system MUST skip the pregnancy roll for that couple and MUST NOT update `pregnantSince` / `dueDate`. The 8% daily pregnancy probability, the 30-day gestation, and the post-birth field-clearing behavior MUST remain unchanged.

#### Scenario: Couple at cap does not conceive

- **GIVEN** a couple `a, b` with 3 existing children (`parent_ids` rows for both `a` and `b` exist in DB)
- **AND** `world.children.max-per-couple` is 3
- **WHEN** `dailySettlement()` runs on a day where neither is currently pregnant
- **THEN** `a.pregnantSince` and `a.dueDate` (or the spouse's) remain null after the call
- **AND** no news item with type `pregnancy` is added for this couple on that day

#### Scenario: Couple below cap conceives as before

- **GIVEN** a couple `a, b` with 0 existing children and both within fertile age (18-45)
- **AND** `world.children.max-per-couple` is 3
- **WHEN** `dailySettlement()` runs and the random roll is ≤ 8
- **THEN** one of the pair gets `pregnantSince = today` and `dueDate = today + 30 days`
- **AND** a `pregnancy` news item is added

#### Scenario: Single parent counts toward cap

- **GIVEN** a resident `a` with 3 children whose `parent_ids = "a,a"` (single-parent recorded births, or a partner with id equal to `a` for any reason)
- **WHEN** `dailySettlement()` runs on a non-pregnant day for `a`
- **THEN** no new pregnancy is recorded for `a` (cap applies regardless of partner id equality)

#### Scenario: Cap is configurable at startup

- **GIVEN** `application.yml` sets `world.children.max-per-couple: 1`
- **WHEN** a couple has 1 existing child
- **AND** `dailySettlement()` runs on a non-pregnant day
- **THEN** no new pregnancy is recorded for that couple

### Requirement: Newborn avatar selection and throttle MUST be driven by externalized configuration

The system MUST load `world.children.avatar-pool` and `world.children.max-per-couple` from `application.yml` (via `@ConfigurationProperties` or equivalent Spring binding) at startup. Both MUST be readable from the service layer that uses them. Missing keys MUST fall back to documented defaults (pool = the 9 default child emoji; cap = 3). A runtime change to the YAML MUST require an app restart (no hot reload).

#### Scenario: Defaults applied when keys absent

- **WHEN** the app starts with no `world.children.*` keys in `application.yml`
- **THEN** the avatar pool is the 9 default emoji and the per-couple cap is 3

#### Scenario: Custom config wins

- **WHEN** `application.yml` sets `world.children.avatar-pool: ["🐣"]` and `world.children.max-per-couple: 5`
- **THEN** newborns receive `🐣` only
- **AND** couples may conceive up to and including 5 children

## ADDED Requirements

### Requirement: Historical child data MUST be migrated on app startup

A `CommandLineRunner` (named `MigrateChildDataRunner`) MUST run once after the application context is ready. It MUST:
1. Find every `AgentEmployee` whose `parentIds` is non-null and non-blank.
2. Reassign each such record's `avatar` to a value from the configured pool using the same deterministic selection as `birth()`.
3. For every couple that, after step 1's identity set is known, has `> max-per-couple` children with `parentIds` containing both partners' ids, clear `pregnantSince` and `dueDate` on both partners (if set).
4. Be idempotent: re-running it MUST NOT change the `avatar` or `pregnantSince` / `dueDate` values it just set in a previous run, and MUST NOT introduce duplicates in the pool distribution beyond what a single run would produce.
5. Log a single INFO line summarizing: number of children re-avatar'd, number of over-cap couples cleared.

The runner MUST NOT fail app startup if a single row's update fails; the failure MUST be logged at WARN with the row id and the runner MUST continue to the next row.

#### Scenario: Empty DB migration

- **GIVEN** the `agent_employee` table has no rows with non-null `parentIds`
- **WHEN** the app starts
- **THEN** the runner logs an INFO line stating zero children re-avatar'd and zero couples cleared
- **AND** no UPDATE statements are issued against `agent_employee`

#### Scenario: Mixed children and non-children migration

- **GIVEN** the table has 5 rows with `parent_ids = "10,11"`, 5 rows with `parent_ids = "12,13"`, and 5 adult rows with `parent_ids IS NULL`
- **WHEN** the app starts with default pool and cap=3
- **THEN** the 10 child rows' `avatar` are all from the default pool and not all identical (at least 2 distinct values across 10)
- **AND** the 5 adult rows' `avatar` are unchanged
- **AND** couples `10,11` and `12,13` have 5 children each, which is > 3, so their partners' `pregnantSince` / `dueDate` are cleared if they were non-null
- **AND** an INFO line is logged with `childrenReavatarCount=10` and `couplesClearedCount=2`

#### Scenario: Idempotency

- **GIVEN** a previous successful run already set all child `avatar` values to deterministic picks
- **WHEN** the app starts again
- **THEN** no child `avatar` value changes (the deterministic pick matches the previous run)
- **AND** no couple's `pregnantSince` / `dueDate` values change (they were already null, or they were non-null and over cap and the runner clears them again to null)

#### Scenario: Partial failure does not crash startup

- **GIVEN** the runner is processing row N and the DB throws a constraint violation
- **WHEN** the app is starting up
- **THEN** the app startup completes successfully
- **AND** a WARN log line records the row id and the exception class
- **AND** the runner proceeds to row N+1

## Non-Goals

- This delta does NOT introduce a `sex` / `gender` field on `AgentEmployee`; avatar selection is pool-random.
- This delta does NOT change the `uniqueChildName()` mechanism.
- This delta does NOT alter the existing 8% daily pregnancy probability or the 30-day gestation.
- This delta does NOT add a public API or admin endpoint to invoke the migration manually.
