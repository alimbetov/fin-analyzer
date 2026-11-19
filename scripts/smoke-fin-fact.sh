#!/usr/bin/env bash
# FIN FACT local/CI smoke for Java 21 + PostgreSQL 16 Testcontainers.
# Does not require React, public API, Keycloak, Redis, worker or financial S2.
# Synthetic test data lives only in disposable Testcontainers databases.
set -Eeuo pipefail

mode="quick"
case "${1:---quick}" in
  --quick) mode="quick" ;;
  --forms) mode="forms" ;;
  --extended) mode="extended" ;;
  --unit) mode="unit" ;;
  -h|--help)
    cat <<'HELP'
FIN smoke (macOS/Linux; run from any directory)
  bash scripts/smoke-fin-fact.sh            # 7 essential PostgreSQL cases
  bash scripts/smoke-fin-fact.sh --quick    # same
  bash scripts/smoke-fin-fact.sh --forms    # 169 PG tests: all 166 forms individually + 3 integrity tests
  bash scripts/smoke-fin-fact.sh --extended # 193 PG tests incl. 10 FACT-1.B cases + 018->021 upgrade
  bash scripts/smoke-fin-fact.sh --unit     # 32 Java unit tests incl. FACT-1.A1; no Docker

Requires JDK 21 and Maven. --quick/--extended need Docker Desktop/Engine.
Testcontainers manages a disposable PG16; no local database or full FIN stack.
The synthetic F02 qualification is NOT financial S2 or production authorization.
HELP
    exit 0 ;;
  *)
    echo "[FAIL] Unknown option: $1. Use --help." >&2
    exit 2 ;;
esac

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
pom="$root/form-library/pom.xml"
[[ -f "$pom" ]] || { echo "[FAIL] Not a FIN checkout: $pom" >&2; exit 2; }

for cmd in java mvn; do
  command -v "$cmd" >/dev/null 2>&1 || {
    echo "[FAIL] $cmd not found in PATH" >&2
    exit 2
  }
done

java_info="$(java -version 2>&1 | head -n 1)"
maven_info="$(mvn -version 2>&1)"
echo "[INFO] $java_info"
echo "[INFO] $(printf '%s\n' "$maven_info" | head -n 1)"
if ! printf '%s\n' "$maven_info" | grep -Eq 'Java version: 21([.]|[, ]|$)'; then
  echo "[FAIL] Maven must run under Java 21. Check JAVA_HOME and mvn -version." >&2
  exit 2
fi

if [[ "$mode" != "unit" ]]; then
  command -v docker >/dev/null 2>&1 || {
    echo "[FAIL] Docker CLI unavailable. Start Docker Desktop or use --unit." >&2
    exit 2
  }
  if ! docker info >/dev/null 2>&1; then
    echo "[FAIL] Docker Engine unavailable. Start Docker Desktop or use --unit." >&2
    exit 2
  fi
  echo "[PASS] Docker Engine reachable: temporary PostgreSQL 16 via Testcontainers"
fi

# Never touch Compose volumes or issue DROP/TRUNCATE against any real FIN DB.
case "$mode" in
  unit)
    selector="FormContentFingerprintTest,FormFactCandidateExtractorTest,FormFactSourceReaderTest,F02CandidateMappingTest"
    expected=32
    options=()
    ;;
  quick)
    selector="Ingest2PostgresqlIntegrityTest#f02ExactHistoricalRevisionIsVerifiedNotLatest,FactR0PostgresqlPartitionTest#partitionedSchemaIsPresentWithCompositePrimaryKeyAndNoDefault+octoberAndNovemberRowsRouteToSeparatePartitionsAndCannotChange+planAndSourceCannotCrossScopeOrAlterVerifiedHistoricalRevision,Fact1F02TestOnlyQualificationTest#f02ExactVerifiedRevisionCreatesThreeFactsAndOneOutbox+atomicTxRollsBackAllThreeFactsAndTrustedEventOnFault,Fact1BUatCommitterPostgresqlTest#qf11OneAtomicFactSetAndThreeExactObservationsPlusOutbox"
    expected=7
    options=("-Dfin.postgres.uat.enabled=true")
    ;;
  forms)
    selector="Ingest2PostgresqlIntegrityTest"
    expected=169
    options=("-Dfin.postgres.uat.enabled=true")
    ;;
  extended)
    selector="Ingest2PostgresqlIntegrityTest,FactR0PostgresqlPartitionTest,Fact1F02TestOnlyQualificationTest,Fact1BUatCommitterPostgresqlTest,FactR0PostgresqlUpgradeTest"
    expected=193
    options=("-Dfin.postgres.uat.enabled=true")
    ;;
esac

echo "[RUN ] FIN FACT $mode smoke: $expected selected tests"
echo "[INFO] No React/Keycloak/Redis/HTTP/worker/financial S2 configuration required"
log="$(mktemp "${TMPDIR:-/tmp}/fin-fact-smoke.XXXXXX")"
cleanup() { rm -f "$log"; }
trap cleanup EXIT
if ! mvn -B -ntp -f "$pom" clean test "-Dtest=$selector" "${options[@]}" \
    -DfailIfNoTests=true 2>&1 | tee "$log"; then
  echo "[FAIL] FIN FACT smoke: Maven test execution failed. Review log above." >&2
  exit 1
fi

# Exit 0 is not enough if an opt-in Testcontainers group silently SKIPPED.
summary="$(grep -E '\[INFO\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$log" | tail -n 1 || true)"
if [[ ! "$summary" =~ Tests[[:space:]]run:[[:space:]]([0-9]+),[[:space:]]Failures:[[:space:]]([0-9]+),[[:space:]]Errors:[[:space:]]([0-9]+),[[:space:]]Skipped:[[:space:]]([0-9]+) ]]; then
  echo "[FAIL] Could not verify the Maven test summary." >&2
  exit 1
fi
actual="${BASH_REMATCH[1]}"
failures="${BASH_REMATCH[2]}"
errors="${BASH_REMATCH[3]}"
skipped="${BASH_REMATCH[4]}"
if [[ "$actual" -ne "$expected" || "$failures" -ne 0 ||
      "$errors" -ne 0 || "$skipped" -ne 0 ]]; then
  echo "[FAIL] Expected=$expected actual=$actual failures=$failures errors=$errors skipped=$skipped" >&2
  exit 1
fi
echo "[PASS] FIN FACT $mode smoke: $actual/$expected, 0 failures/errors/skipped"
echo "[INFO] Storage integrity and synthetic F02 TX-Q only; no approved financial S2."
