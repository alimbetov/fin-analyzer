#!/usr/bin/env bash
# FIN local toolchain bootstrap — macOS, user-run only.
set -euo pipefail
if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "ERROR: This installer targets macOS. For Linux, see docs/FIN-LOCAL-MAVEN-DOCKER.md" >&2
  exit 2
fi
if ! command -v brew >/dev/null 2>&1; then
  echo "Homebrew is required: https://brew.sh" >&2
  exit 2
fi
ENGINE="${1:-colima}"
if [[ "$ENGINE" != "colima" && "$ENGINE" != "desktop" ]]; then
  echo "Usage: bash scripts/fin-setup-macos.sh [colima|desktop]" >&2
  exit 2
fi
brew install maven
if [[ "$ENGINE" == "colima" ]]; then
  brew install colima docker
  if ! colima status >/dev/null 2>&1; then
    colima start --cpu 4 --memory 6 --disk 40
  fi
  echo "Docker engine: Colima"
else
  brew install --cask docker
  echo "Open Docker Desktop, finish its first-run setup, and wait until the engine is ready."
fi
mvn -version
docker --version
if docker info >/dev/null 2>&1; then
  echo "Docker daemon: READY"
else
  echo "Docker daemon: NOT READY; start Docker Desktop or Colima before Testcontainers." >&2
  exit 3
fi
echo "FIN toolchain is ready. Execute: bash scripts/fin-verify-toolchain.sh"
