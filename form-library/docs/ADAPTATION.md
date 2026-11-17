# Standalone adaptation of FIN-FORM-LIB-R1

The full original specification is copied verbatim to this directory. The implementation is an **isolated prototype**:
- Java 21, Spring Boot 3.5.7, Spring Data JPA, Liquibase, H2 memory, no Spring Security.
- This is a separate Maven application under `form-library/`; **do not run** the legacy backend and this prototype on the same database/schema.
- All 12 physical tables use `lib_` with `fin_lib` and `fin_intake` schemas.
- 166 code identities are seeded exactly from `form-compatibility.tsv` (122/12/32 capture states). Additional candidate field definitions from original TSV files are **unqualified**, even when typed.
- Version hashes are structural/candidate digests for development only; they do not constitute financial approval.
- No `fin_fact`, FactSet publication, HTAP execution, S2 review, production tenant security, trusted attachments, or React implementation in this standalone prototype.
- H2 in-memory data are **lost on application stop**. For long-term storage migrate to PostgreSQL with separately reviewed Liquibase changesets.
- The original specification assumes Flyway and PostgreSQL and a separately authorized React stage; this isolated spike deliberately substitutes Liquibase/H2 per project request, without changing the normative production specification.
