#!/usr/bin/env bash
# Verify Maven, Java 21 and Docker daemon before FIN Testcontainers acceptance.
set -euo pipefail
cd "$(dirname "$0")/.."
fail() { echo "ERROR: $*" >&2; exit 1; }
command -v java >/dev/null 2>&1 || fail "Java is not installed"
command -v mvn >/dev/null 2>&1 || fail "Maven is not installed"
command -v docker >/dev/null 2>&1 || fail "Docker CLI is not installed"
JAVA_MAJOR="$(java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
[[ "$JAVA_MAJOR" == "21" ]] || fail "FIN requires Java 21 (actual: ${JAVA_MAJOR:-unknown})"
mvn -version
docker --version
docker info >/dev/null 2>&1 || fail "Docker daemon is unavailable. Start Docker Desktop or Colima."
docker ps >/dev/null 2>&1 || fail "Cannot access Docker daemon"
printf '\nRunning FIN backend Maven verify with PostgreSQL Testcontainers...\n'
mvn -B -ntp -f backend/pom.xml verify
printf '\nFIN backend verify succeeded for local checkout SHA: '
git rev-parse HEAD
printf 'This does NOT prove GitHub CI / full frontend acceptance; verify all jobs on the same SHA.\n'
