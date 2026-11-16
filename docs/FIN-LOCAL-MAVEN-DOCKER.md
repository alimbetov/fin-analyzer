# FIN — Maven 3 / Java 21 / Docker Testcontainers Toolchain

**Date:** 2026-11-16  
**Branch:** `feat/fin-r0-r1a`  
**Status:** `INSTALLER_SCRIPTS_COMMITTED / HOST_INSTALLATION_NOT_EXECUTED / CI_HOLD`

## Context

FIN backend uses Java 21 and PostgreSQL Testcontainers. Real `mvn verify` requires both Maven and an operational Docker daemon, not just a Docker client. GitHub Actions `.github/workflows/ci.yml` provisions Java 21 + Maven through a hosted Ubuntu runner and Docker is available on that runner; this is separate from local MacBook/VM setup.

The restricted conversation container has Java 21 but lacks Maven, Docker CLI, and `/var/run/docker.sock`. Attempt to refresh Debian apt package index timed out, and the Maven repository hostname could not be resolved. **No Maven or Docker installation in that container has been confirmed.** We must not claim Testcontainers PASS or GitHub CI GREEN.

## MacBook setup (recommended for local FIN development)

Requires installed Homebrew and network access. From the repository root on macOS:

```bash
git switch feat/fin-r0-r1a
bash scripts/fin-setup-macos.sh colima
bash scripts/fin-verify-toolchain.sh
```

The script installs Maven and a compatible Docker client, starts Colima as a Docker-compatible engine (4 CPUs, 6 GiB RAM, 40 GiB disk), checks the live daemon, then prints the backend verification command.

If you prefer Docker Desktop:

```bash
bash scripts/fin-setup-macos.sh desktop
# Open Docker Desktop and complete its startup before verifying
bash scripts/fin-verify-toolchain.sh
```

**Do not run Colima and Docker Desktop simultaneously for the same acceptance environment without explicitly selecting a Docker context**. Check with `docker context ls`, `docker info`.

Verify Java 21 is selected (`java -version`). If Homebrew did not configure Java 21, install `openjdk@21` and configure `JAVA_HOME` according to the Homebrew instructions.

## Linux VM / dedicated Docker host

Requires Debian/Ubuntu, privileged shell, working DNS/repositories and systemd:

```bash
sudo bash scripts/fin-setup-linux.sh
bash scripts/fin-verify-toolchain.sh
```

Normal users need permission to connect to the Docker daemon; Docker group access is effectively root-equivalent. Use a trusted developer machine or a CI runner and follow the organization's access policy.

## Required acceptance

```bash
java -version
mvn -version
docker --version
docker info
docker ps
mvn -B -ntp -f backend/pom.xml verify
```

`docker info` and `docker ps` must both succeed; Maven's existence alone does not prove Testcontainers can start PostgreSQL.

Keep all Maven Surefire/Failsafe XML reports. Record Java/Maven/Docker/PostgreSQL image versions, command exit status, start/end times and exact `git rev-parse HEAD`. For CI sign-off verify `verify`, `backend-verification`, `desktop-macos`, `acceptance-gate` green on **one** commit SHA and inspect the artifacts. CI cannot be marked GREEN based solely on the existence of these scripts.

**Production:** keep `FIN_EXECUTION_ENABLED=false` until full acceptance and independent S2 review. Do not enable new 60-method adapters merely because toolchains are installed.
