# W1 Review — forum-likers-display

**Mode**: tweak
**Wave**: W1 (serial, 5 tasks)
**Base SHA**: `19d9170`
**Head SHA**: `19d9170` (changes uncommitted at review time; verified via diff stat)
**Reviewer**: spec-superflow build-executor
**Date**: 2026-08-20

---

## 1. Spec Compliance

### Delta spec (`specs/forum-likers/spec.md`) acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| `recentLikers` field added to 5 endpoints (list, detail, user posts, my likes, my favorites) | PASS | `ForumController.java:69-71, 90-93, 154-156, 218-220, 226-228` — all 5 endpoints call `queryService.topRecentLikers(...)` and pass to `toPostMap` |
| Top N (default 3) likers per post, ordered by recent activity | PASS | `AgentForumLikeRepository.findByPostIdInOrderByPostIdAscIdDesc` orders by `(postId asc, id desc)`. `topRecentLikers` consumes in order, caps `perPost`. RECENT_LIKERS_PER_POST=3 in controller |
| Username resolution: User → AgentEmployee → "u:{id}" fallback | PASS | `ForumQueryService.java:137-149` — `userRepo.findAllById(allUserIds)` first, then `remaining` set, then `employeeRepo.findAllById(remaining)`, then `nameById.getOrDefault(uid, "u:"+uid)` |
| Single query for all posts (no N+1) | PASS | `likeRepo.findByPostIdInOrderByPostIdAscIdDesc(postIds)` is one IN-query; `userRepo.findAllById` and `employeeRepo.findAllById` are batched PK lookups |
| Frontend feed card shows heart icon + comma-joined names + "等 N 人" extra | PASS | `index.html` `renderForumCard` builds `likersLine` with `.liker-heart`, `.liker-names`, `.liker-extra` spans |
| Frontend detail modal shows likers line | PASS | `renderForumDetail` populates `<div id="forumDetailLikers">`; element present in detail modal HTML |
| Public (anonymous) viewers still see likers | PASS | `topRecentLikers` does not depend on `currentUserId`; all 3 list endpoints pass `currentUserId` separately for `currentUser.liked` |

### Out-of-scope compliance (no drift)

- No new endpoints added (delta only MODIFIES existing 5)
- No schema changes (uses existing `AgentForumLike` + User/AgentEmployee tables)
- No auth/permission changes
- No new public APIs (delta spec only adds 1 repo method + 1 service method + 1 record)

## 2. Code Quality

### Strengths
- **Bucket-capping loop** (`ForumQueryService.java:131-136`) correctly stops adding after `perPost`, avoiding full pass through likes when only top-N needed.
- **Two-tier name resolution** cleanly separates human vs agent IDs — important because `AgentForumLike.userId` field stores both.
- **`remaining` set** ensures `AgentEmployee` only queries IDs that `User` table didn't resolve — avoids wasted joins.
- **Controller refactor**: extracted `postsToMaps()` helper to dedupe the 3 list endpoints (feed, user posts, my likes, my favorites).
- **Test coverage**: 5 new tests cover empty postIds, no likes, capping, missing-user fallback, and agentId→employeeRepo path.

### Minor observations (not blocking)
- `topRecentLikers` short-circuit (`postIds == null || postIds.isEmpty()`) returns `Map.of()` (immutable). Callers in `ForumController` always re-wrap into mutable `LinkedHashMap` at the call site, so no caller-side issue. Safe.
- `RECENT_LIKERS_PER_POST = 3` is a controller constant. If frontend needs to vary this (e.g. show 5 on detail), it's a single line to change. Not part of this scope.
- `likers` could be empty if `likeCount > 0` but DB has no `AgentForumLike` rows (count and rows can drift if seed data is inconsistent). Frontend correctly hides the line in that case. No fix needed.

## 3. Test Verification

- **Test count**: 194/194 pass (delta: 5 new tests in `ForumQueryServiceTest` for `topRecentLikers`)
- **Build**: `mvn package` succeeds, JAR produced
- **Runtime**: docker compose up — API `/api/world/forum/posts?category=...` returns `recentLikers` array with real agent names ("墨囡囡", "默念念", etc.) from `AgentEmployee` table
- **Detail endpoint**: `/api/world/forum/posts/332` returns `recentLikers` for single-post detail
- **Frontend assets**: HTML contains `.forum-card-likers` and `.forum-detail-likers` CSS rules + `likersLine` JS construction

## 4. Verdict

**PASS** — W1 satisfies all delta spec acceptance criteria, contains no scope drift, all tests green, build + runtime verified end-to-end.

Safe to record `ssf execution review ... --verdict pass` and route to `release-archivist`.
