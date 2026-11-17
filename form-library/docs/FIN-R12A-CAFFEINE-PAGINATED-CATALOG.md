# FIN R12.A — Bounded Caffeine Reference Cache + Card/List Pagination

Branch `feat/fin-form-library-standalone`. No React client added. Backend returns data and icon keys for both views. **Never treat caching or a card icon as a form's payment, visibility, ownership or sharing permission.**

## Controller inventory, cache policy

| Controller | Policy |
|---|---|
| `ReferenceCatalogController` | Cache read-only currencies, periods, metrics, forms and localized form details; pageable forms/metrics (no page key stored) |
| `CatalogUiNavigationController` | Cache 47 category tree and up to 166 localized form card/entry projections; pageable categories, nested-category forms and global catalog |
| `FormUseCaseController` | Cache localized use-case-to-form references, return pageable form list |
| `FormNavigationController` | Composite sector/purpose facet filters NOT cached; uses cached reference forms |
| `FormLibraryController` | Cache only old `GET /forms` and `GET /forms/{code}`; paginate form summaries; `POST /drafts` and `GET /drafts` uncached |
| `CatalogManagementController` | Uncached editor writes and mutable draft/event reads; after successful publish invalidates the public cache AFTER commit |
| `InputTemplatePreviewController` | Uncached version/period/style preview; schema qualification and immutable version pinning still required |
| `PeriodBatchController` | Cheap pure date range computation; uncached |
| `FormWorkflowController` | No caching: instance status is organization-scoped and mutable |
| `ReportGridController` | No caching: finance values/revisions, rules and grants are user/organization scoped or mutable |

### Caffeine settings

- JVM-local cache, whitelist of 12 namespaces; independent cache per namespace (nested composite reference lookups must not cause `Recursive update`).
- `maximumSize(512)` per namespace; `expireAfterWrite(5 minutes)`; `recordStats()`.
- Cache only server-defined finite keys (locale, form code, category code, method package); no arbitrary search, free-text query or pagination pages. Four locales: `ru`, `kk`, `en`, `zh-Hans`, different cache entries.
- Fail closed for unsupported cache namespaces; no tenant report, actor, private favorite, paid entitlement, share token, financial status or user revision cache.
- Successful `CatalogManagementService.publish` triggers transactional **afterCommit** global namespace invalidation; editing a draft does not invalidate published data. On other JVMs TTL bounds staleness but cross-node cache broadcast is not implemented. For production, require Redis/Kafka/Outbox invalidation or catalogue-versioned cache keys plus event sequence, not just 5-minute eventual convergence.
- Catalog translation/definition writes introduced via any OTHER future mutation endpoint must use the same invalidation contract. Do not directly edit `fin_lib` bypassing catalog publication.

## Pagination

All new page endpoints return:

```json
{
  "items": [
    {
      "iconKey": "file-spreadsheet",
      "form": {
        "formCode": "F02",
        "locale": "ru",
        "title": "Отчёт о прибылях и убытках",
        "purpose": "Оценить доходность и финансовый результат",
        "formVersionId": "<pinned-current-uuid>",
        "qualificationState": "CANDIDATE_UNQUALIFIED"
      }
    }
  ],
  "page": 0,
  "size": 24,
  "totalElements": 166,
  "totalPages": 7,
  "hasPrevious": false,
  "hasNext": true,
  "viewMode": "CARDS",
  "sortMode": "CODE"
}
```

- Page is 0-based; default size 24, **max size 50**, size >=1, page >=0 and <=1_000_000; overrun pages return empty.
- Stable whitelist `sort=RELEVANCE|CODE|TITLE`, `viewMode=CARDS|LIST`; no SQL injection into ORDER BY. Sorting with code tie-breaker and deterministic category/use-case sequence.
- Optional case-insensitive `q` on code, localized title/purpose/description (some lists have fewer fields); max 100 chars. Search and sorting never change financial SHA and never create form instances.
- Page slices are drawn from cached **small reference snapshots** (166 forms, 47 categories), not SQL offset pages; migrate to indexed SQL keyset queries when catalog grows by orders of magnitude.
- Icons are **stable Lucide names**, selected by trusted `contentMode` and category type, not HTML or remote image URLs: e.g. `file-spreadsheet`, `table-2`, `file-check-2`, `factory`, `target`, `folder-open`. The client decides whether to render a list or cards with the same form.

## Endpoints (additive; legacy responses remain)

```http
GET /api/v1/catalog/ui/categories/page?locale=ru&page=0&size=24&q=&sort=RELEVANCE&viewMode=CARDS
GET /api/v1/catalog/ui/forms/page?categoryCode=GROUP_FINANCE&locale=ru&page=0&size=24&sort=CODE
GET /api/v1/catalog/ui/categories/GROUP_FINANCE/forms/page?page=0&size=8&viewMode=LIST
GET /api/v1/catalog/use-cases/BANK_FINANCING/forms/page?page=0&size=10
GET /api/v1/catalog/forms/page?locale=kk&page=0&size=25&q=&sort=CODE
GET /api/v1/catalog/metrics/page?locale=en&kind=SOURCE_FIELD&page=0&size=24
GET /api/v1/forms/page?page=0&size=24
```

Users still transition from `/catalog/ui/forms/{code}/entry` to period/style chooser (`/api/v1/templates/{code}/preview`) and subsequently to the Excel renderer. No new fact writer.

## Tests / acceptance

1. 166 code-unique form cards in 7 pages of 25; 47 localized categories in 5 pages of 10; 27 financing use-case forms, 8 financial forms.
2. Search, stable sort, two view modes, icon-key whitelist, pagination edges, invalid page/size/sort/view/locale rejection.
3. Repeated GET increments cache hits, cache TTL expires with deterministic fake ticker, nested cached lookups do not throw.
4. Catalog edit remains invisible until publish; after commit, cached locale-specific entry and category title refreshes.
5. No new `fin_intake` instances or revisions from read-only listing.

**Production blockers:** no tenant IAM, no React implementation, no PostgreSQL negative regression, no multi-node invalidation, no approved per-form period policies, no verified S2 statuses.
