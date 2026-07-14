# OpenSpec — SDD Layout for `hielo`

This directory follows the **OpenSpec** convention used by the Gentle AI SDK for
Spec-Driven Development (SDD).

## Layout

```
openspec/
  config.yaml        # Project-wide SDD configuration (stack, phases, test runner)
  README.md          # This file
  changes/
    {change-name}/
      proposal/      # Business proposal produced by sdd-proposal
      specs/         # Delta specs with requirements and scenarios (sdd-spec)
      design.md      # Technical design (sdd-design)
      tasks.md       # Implementation tasks with workload forecast (sdd-tasks)
      apply-progress.md  # TDD cycles (sdd-apply)
      verify-report.md   # Verification results (sdd-verify)
      sync-report.md     # Promotion to canonical specs (sdd-sync)
      archive-report.md  # Closure (sdd-archive)
```

## Current Change

**`auth-foundation`** — first SDD change on this project. Working branch:
`feature/auth-foundation`. Strict TDD is enabled; write the test before the
production code on every cycle.

## Conventions

- **Backend**: Java 25, Spring Boot 3.x, Maven, Package by Layers
  (`controller`, `service`, `repository`, `model`).
- **Client** (`android-spec/`): Kotlin + Jetpack Room + WorkManager. Out of
  scope for backend SDD changes.
- **Test runner**: `mvn test` for unit tests, `mvn verify` for the full
  Spring Boot Test cycle (including integration tests when present).
- **Review budget**: 400 changed lines per change. If implementation exceeds
  this, the auto-forecast strategy recommends chained PRs.

## How to run a phase

Parent (this Pi session) dispatches each phase. Do not invoke sdd-* agents
directly from a shell — they expect a parent-provided status JSON and the
preflight choices below.

## Preflight contract

- `execution_mode`: `interactive` (default for this project).
- `artifact_store`: `openspec/` (Engram is not used because the server was
  unreachable at session start; revisit if memory comes back online).
- `chained_pr_strategy`: `auto-forecast`.
- `review_budget_lines`: 400.

Phase approval is per-step. Saying "continue" or "dale" advances the
immediate next phase only.
