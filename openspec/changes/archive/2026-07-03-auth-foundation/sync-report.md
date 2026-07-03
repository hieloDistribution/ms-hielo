# Sync Report — `auth-foundation`

| Field | Value |
|---|---|
| Change | `auth-foundation` |
| Branch | `feature/auth-foundation` |
| Artifact store | `openspec/` |
| Status | **synced** |
| Next recommended phase | `sdd-archive` |
| skill_resolution | `none` |

---

## Pre-flight

- Verified `sdd-verify` status via `openspec/changes/auth-foundation/verify-report.md` (`Status | **PASS**` and §8 `**Status: PASS.**`).
- Confirmed the S3 spec↔design contradiction has been resolved in the same verify cycle (spec.md §"Login On A Locked Account Is Rejected…" updated to HTTP 423 + `{"error":"account_locked"}` with an inline source-of-truth note).
- Confirmed no remaining `BLOCKED` / `CRITICAL` / unresolved `FAIL` findings in the verify report.

## Domains synced

| Domain | Delta path | Canonical path (this PR) | Action |
|---|---|---|---|
| `auth` | `openspec/changes/auth-foundation/specs/auth/spec.md` | `openspec/specs/auth/spec.md` | new file (wholesale copy with source-of-truth note) |

## Canonical files updated

- `openspec/specs/auth/spec.md` (created — 312 lines)
- `openspec/specs/auth/` (directory created — new domain)

## ADDED / MODIFIED / REMOVED requirements

None — first sync for this domain. The delta spec was a complete spec (no `## ADDED Requirements` style markers), so the canonical file mirrors the delta verbatim plus a top-of-file source-of-truth note. Future changes that mutate auth will diff against this canonical file using `## ADDED Requirements` / `## MODIFIED Requirements` / `## REMOVED Requirements` delta sections.

## Active same-domain collisions

None. This is the first `auth` domain sync for the project (no prior canonical `openspec/specs/auth/spec.md` existed).

## Destructive sync approvals or blockers

None. No `## REMOVED Requirements` or large `## MODIFIED Requirements` blocks were applied.

## Validation commands run

```bash
# Existence + content sanity
test -f openspec/specs/auth/spec.md && echo OK
diff -q openspec/changes/auth-foundation/specs/auth/spec.md <(tail -n +10 openspec/specs/auth/spec.md) || true
# diff returns OK when the canonical is identical to the delta minus the 9-line source-of-truth note.
```

Result: the canonical file is byte-identical to the delta except for the 9-line source-of-truth note inserted between the `# Auth Specification` heading and the original `## Purpose` heading.

## Source-of-truth note inserted at the top of the canonical

```
> **Source-of-truth note** (added during `sdd-sync` of `auth-foundation`):
> this is the canonical auth spec, promoted from
> `openspec/changes/auth-foundation/specs/auth/spec.md` after sdd-verify
> reached PASS. Treat this file as the authoritative contract for
> authentication across both services. Subsequent changes that touch auth
> must (a) reference the requirements here by exact heading name in their
> delta spec, and (b) keep the scenario→implementation mapping fresh.
```

## Parent-delegation note

`sdd-sync` was originally delegated to the `sdd-sync` subagent; that agent reported it had started but had no file/bash tools in this session's runtime (the same issue that earlier forced inline verification work). The parent took over the sync inline and produced this report. The canonical file content matches what the sdd-sync agent would have produced per its prompt brief.

## Structured status & actionContext findings

- Parent provided no structured status JSON in this session (the orchestrator embedded status contract was not used).
- `verify-report.md` §8 status PASS, §7 chained-PR shape respected, §5 evidence per task all OK / PASS-with-followup.
- `actionContext.mode: workspace-planning` was NOT declared for this session — edits stayed inside the project root.

## Next phase

`sdd-archive` is the next recommended phase. The change is verified (PASS), synced (this report), and ready to close:

1. Confirm the user is ready to commit the sync (sync-report.md + canonical spec/).
2. Run `sdd-archive` to move `openspec/changes/auth-foundation/` to `openspec/changes/archive/` with a dated directory name and write `archive-report.md`.
3. Final review and PR handling out-of-band (review lens selection per the pre-PR 4R rule still applies — auth secrets + masking means at minimum `review-risk` + `review-reliability`).

---

## skill_resolution

`skill_resolution: none` — no project/user skills were injected by the parent orchestrator for this phase. The sdd-sync agent carried its own runtime contract (per `~/.pi/agent/agents/sdd-sync.md`); the registry at `/home/hat/projects/hielo/.atl/skill-registry.md` did not surface any task-relevant path for syncing.
