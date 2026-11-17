# FIN-FORM-LIB-R8.C2 — Report Editing Statuses (local-only MVP)

Branch: `feat/fin-form-library-standalone`; scope: `fin_intake`, not `fin_lib` catalog moderation and not FIN HTAP S2.

## Status lifecycle

`DRAFT` (editable) -> `READY_FOR_REVIEW` (editing locked) -> `DRAFT` (reopened for changes).

These are **draft editing workflow markers**, not real approval or independent moderation. `APPROVED` / `REJECTED` / `FINANCIAL_QUALIFIED` are deliberately unavailable until a trusted identity, reviewer role and S2 proof exist. The submitted `actorLabel` is **untrusted input** in this localhost-only service.

An instance begins as `DRAFT` with `statusVersion=0`. Every transition increments the version, carries the exact current `revisionId`, and is recorded in the append-only `fin_intake.lib_form_status_event`. No new data revision or financial value is created by a status transition.

## API

| Method | Endpoint | Result |
|---|---|---|
| GET | `/api/v1/drafts/{instanceId}/status?organizationId={uuid}` | Status, editability, current revision and version |
| POST | `/api/v1/drafts/{instanceId}/status-transitions` | Atomic transition |
| GET | `/api/v1/drafts/{instanceId}/status/events?organizationId={uuid}` | Chronological transition history |

POST example:

```json
{
  "organizationId": "uuid",
  "expectedRevision": 1,
  "expectedStatusVersion": 0,
  "targetStatus": "READY_FOR_REVIEW",
  "actorLabel": "local-user",
  "reason": "Finished editing"
}
```

A transition requires both matching expected data revision and matching status version. Concurrent changes are serialized with a pessimistic lock on `lib_form_instance`, which is shared with the `FormDraftService.save` update path. Editing `READY_FOR_REVIEW` reports returns HTTP 409, and explicit reopening returns them to `DRAFT`. Existing idempotent requests replay their original receipts without changing report data.

## Persistence

Liquibase migration `008-lib-intake-report-status.sql`:
- Adds `workflow_status` and `status_version` to `fin_intake.lib_form_instance`.
- Adds append-only `fin_intake.lib_form_status_event` with a unique version per instance, foreign keys to instance and immutable revision, reason, actor label, timestamp.
- Applies DB CHECKs for permitted statuses and directed transitions.

No new user value table and no changes to financial SHA inputs. Existing `lib_form_revision` rows remain immutable and readable.

## Scope limitations

1. Current implementation is wired into **`FormDraftService`**. The parallel `ReportGridService` contract still needs unification before using one status lifecycle for all report types.
2. There is no Spring Security, verified actor identity, reviewer permissions or real workflow delivery. Do not expose transition API publicly.
3. `READY_FOR_REVIEW` means only that the editor marked the locally saved revision for review; required fields are validated on `FormDraftService.save` but financial S2 qualification is independent.
4. Historical data revision reads expose the *current* instance workflow status as extra metadata; events provide the timeline. They do not prove a prior approval status.

## Test scope

`FormWorkflowIntegrationTest`: draft creation, promotion to ready, editing lock, reopening, new data revision and original immutable SHA, event history; stale data/status versions, untrusted attempted approval, actor validation and cross-organization 404.

Full suite: `mvn --batch-mode -f form-library/pom.xml clean verify`.
