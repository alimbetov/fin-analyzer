# FIN R17.P — Persisted user style preferences

Branch: `feat/fin-form-library-standalone`. Status: **CODE COMMITTED / TEST EXECUTION NOT VERIFIED**.

## Scope and invariants

This feature stores a **user's preference for viewing a report form**, independently from financial content. The three UI themes are `CLASSIC`, `EXCEL` and `DASHBOARD`. These are visual rendering presets, NOT the legacy R8 proposal's `GRID_METRIC_ROWS`, `GRID_PERIOD_ROWS` and `SECTION_FORM` layout/transposition codes; those remain independent. No renderer, React component or catalog presentation profile is implemented by this work.

- New `016-lib-form-style-preferences.sql` creates `fin_ui`, three styles, a user-level default, and a per-user-per-form override.
- No new `fin_intake` table, no FormRevision updates and no impact on `contentSha256`, `formVersionId` or financial qualification.
- Resolution priority: `USER_FORM` -> `USER_DEFAULT` -> `SYSTEM_DEFAULT` (`CLASSIC`).
- `DELETE` removes an override; `GET` returns the fallback with a `source` indicator.
- The style persists across requests; persistence across restarts requires a durable database. Local H2 in-memory resets on restart.
- User and form identities are separate from reporting period and report revision: same user's preferred layout applies to all report instances of that form.
- Report-specific override is **not implemented**; add only when a user story requires a shared report to have a distinct presentation preference.

## REST endpoints (local only)

| Method | URL | Behavior |
| --- | --- | --- |
| GET | `/api/v1/form-preferences/default?userId=<UUID>` | User default, otherwise CLASSIC |
| PUT | `/api/v1/form-preferences/default` | Store user default |
| GET | `/api/v1/form-preferences/F02?userId=<UUID>` | Resolve form style + source |
| PUT | `/api/v1/form-preferences/F02/style` | Upsert per-form override |
| DELETE | `/api/v1/form-preferences/F02/style?userId=<UUID>` | Remove override |

PUT JSON payload:

```json
{"userId":"00000000-0000-0000-0000-000000000001","styleCode":"EXCEL"}
```

Response example:

```json
{"formCode":"F02","userId":"00000000-0000-0000-0000-000000000001","styleCode":"EXCEL","source":"USER_FORM"}
```

Invalid style: HTTP 422. Unknown form: HTTP 404. Supported style codes are case-insensitive on input, canonical uppercase on output.

## Usage

```bash
mvn -f form-library/pom.xml -Dtest=FormStylePreferenceIntegrationTest test
mvn -f form-library/pom.xml clean verify
```

Optional durable database configuration (subject to full Liquibase PostgreSQL 001-016 qualification):

```bash
export FIN_FORM_DB_URL='jdbc:postgresql://localhost:5432/finform'
export FIN_FORM_DB_DRIVER='org.postgresql.Driver'
export FIN_FORM_DB_USER='fin'
export FIN_FORM_DB_PASSWORD='<from-secret-store>'
```

**Deployment HOLD:** Full PostgreSQL migration and integration tests have not been executed in the current implementation session. Never report a GREEN build until a real Maven and PostgreSQL CI run proves it.

**Security restriction:** The project has no authentication/authorization. `userId` is an untrusted caller-provided local development identity. Do not expose these endpoints publicly. Production must resolve user identity from a trusted principal, not request parameters/body.

## Testing

`FormStylePreferenceIntegrationTest`: fallback, PUT/GET, override precedence, DELETE fallback, two-user isolation, bad code/unknown form, and unchanged `fin_intake` revision counts. Changes are deliberately UI-only, and do not create report revisions.

## Client integration

On form open, call `GET /api/v1/form-preferences/{formCode}` to obtain `styleCode`; select a renderer locally. On change, call `PUT /api/v1/form-preferences/{formCode}/style`. Do **not** insert `styleCode` into the financial draft save request or its content hash.
