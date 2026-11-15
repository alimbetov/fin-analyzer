# FIN Backend Economic Domain Kernel — first executable slice

Status: IMPLEMENTATION CANDIDATE. Do not mark READY until successful CI, PostgreSQL integration tests,
independent financial review and manual qualification.

## Scope
Java 21 / Spring Boot 3.5.7 / Spring Data JPA / PostgreSQL 16 / Flyway.
Domain primitives: typed decimal states, immutable fact coordinates and fact sets, typed formula DAG,
period flow/stock semantics, isolated scenario assumptions.
A form compatibility matrix lists 44 financial/credit, 59 business-input, and 63 sector forms (166).
The matrix is DESIGN-ONLY and NOT executable support for 166 forms.

## Run
Requires JDK 21, Maven 3.9+, Docker (for Testcontainers), PostgreSQL 16 for application startup.

    mvn -f backend/pom.xml verify

Local application (with FIN_DB_URL/FIN_DB_USER/FIN_DB_PASSWORD set):

    mvn -f backend/pom.xml spring-boot:run

## Non-goals
No frontend changes, no bank approval, no public API, no completed 166-form implementation,
no production identity/security boundary, no server integration with existing React project.
No automatic approval of PDF/Excel contents.

## Next gates
1. Qualify source and row-level form mapping; align financial metric codes and method versions.
2. Make approval orchestration and FactSet persistence authoritative; add optimistic concurrency and outbox.
3. Add Financial Statement + Debt Facility + Sales/Cash transaction typed aggregates.
4. Ensure company/period/currency/basis selection before invoking CalculationDag.
5. Add cross-runtime decimal golden fixtures (existing React and sector pilot are separate baselines).
