# FIN Form Library — temporary MVC + Thymeleaf editor UAT

**Branch:** `feat/fin-form-library-standalone`  
**Purpose:** manually exercise the **existing** API and see the actual saved financial values. This is a **temporary verification page, not a production UI**.

## Enable locally

By default the page and its read-only helper endpoint are completely disabled:

```bash
mvn -f form-library/pom.xml clean verify
FIN_FORM_UAT_EDITOR_ENABLED=true mvn -f form-library/pom.xml spring-boot:run
```

Open `http://127.0.0.1:8086/uat/form-editor`.

The `fin.uat.editor.enabled` flag gates both `GET /uat/form-editor` and `GET /api/v1/uat-helper/drafts/{id}/version?organizationId=...`. With the default flag `false`, they are not registered. The sole helper reads a pinned `formVersionId` by the report's existing organization-scoped instance, and makes no writes. No new business writer or schema is introduced.

## Manual smoke/UAT workflow

1. Click **Создать тестовый контекст**. The page creates fresh UUIDs for organization/economic entity/business scope/project. Test identity is not authenticated.
2. Click **Загрузить поля** (F02 single-record input). The view reads `GET /api/v1/forms/F02` and renders only MANUAL fields.
3. Enter `netRevenue`, `cogs`, `operatingExpenses` and click **Сохранить новую ревизию**. The page calls `POST /api/v1/drafts` with `Idempotency-Key`; verify revision 1 and returned instance UUID.
4. Modify one figure and save. Verify revision 2, then reopen via `GET /api/v1/drafts/{id}`; check all unchanged fields remain intact. Historical revision 1 remains available via the existing REST API.
5. Switch `CLASSIC`, `EXCEL`, `DASHBOARD`; all three use the same editor values, no extra revision generated.
6. Click **Скачать XLSX**. It calls `GET /api/v1/drafts/{id}/export.xlsx` using the selected style; check workbook sheets `REPORT` and `PARAMETERS`.
7. Edit one permitted text-value cell on `REPORT`, keep `PARAMETERS` unchanged, and choose the file.
8. Click **Создать R19-профиль**: UAT helper resolves the persisted `formVersionId`; page registers that version via `POST /api/v1/import-profiles`.
9. Click **Preview без записи**: `POST /api/v1/import-profiles/{profileId}/preview`. Verify source revision, parsed values and returned preview UUID. No financial revision should be created by preview.
10. Click **Commit в fin_intake**: `POST /api/v1/import-profiles/previews/{previewId}/commit` with the exact previewed file and fresh idempotency key. Verify revision increments and the edited value reopens correctly.
11. Negative checks: reuse old XLSX after revision advances; corrupt number; modify `PARAMETERS` field type; change organization UUID. All must fail without modifying `fin_intake`.
12. Test a fresh start with PostgreSQL only after Liquibase 001–018 has passed; the local H2 configuration is ephemeral on restart.

## Scope and known limits

- **Only F02 / SINGLE_RECORD** on the temporary page. Other forms (including ROWSET) remain testable via existing REST tests. R19 external arbitrary XLSX mapping profiles are not implemented.
- Demo UUIDs and actor strings are supplied by the browser, **not authenticated**. Localhost only, never expose beyond local development.
- Existing financial `FormDraftService` writes full snapshots, not PATCH. The page posts every currently populated manual field.
- The UAT page does not publish a financial S2 approval and does not introduce or update financial contracts.
- The import reads the XLSX in memory (bounded 2 MB); independent ZIP-bomb resource gating is required before exposed ingress.
- The page does not persist the selected visual style; persistent per-user preferences exist via separate `/api/v1/form-preferences` endpoints and are not necessary to this smoke workflow.

## Automated checks

```bash
mvn -f form-library/pom.xml -Dtest=FormEditorUatPageIntegrationTest test
mvn -f form-library/pom.xml -Dtest=FormLibraryIntegrationTest,FormXlsxExchangeIntegrationTest,VersionedImportProfileIntegrationTest test
mvn -f form-library/pom.xml clean verify
```

**GREEN is not proven until Maven + CI output exists on one exact commit SHA.**

## Removal after acceptance

Once verified: remove `FormEditorUatPageController`, `templates/uat/form-editor.html`, `FormEditorUatPageIntegrationTest`, the `fin.uat.editor.enabled` configuration, and the dedicated Thymeleaf dependency (unless another module uses it). No data migrations or financial contract changes are needed for removing this temporary UAT page.
