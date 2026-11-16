#!/usr/bin/env bash
# FIN host bootstrap for Debian / Ubuntu Linux with systemd.
# Run on your own Linux host/VM; not intended for restricted ChatGPT containers.
set -euo pipefail
[[ "$(uname -s)" == "Linux" ]] || { echo "Linux required" >&2; exit 2; }
command -v apt-get >/dev/null 2>&1 || { echo "apt-get required (Debian/Ubuntu)" >&2; exit 2; }
[[ "$EUID" -eq 0 ]] || { echo "Run as root: sudo bash scripts/fin-setup-linux.sh" >&2; exit 2; }
apt-get update
apt-get install -y maven docker.io
command -v systemctl >/dev/null 2>&1 || {
  echo "Docker installed but systemd unavailable; a Docker-capable host/VM is required." >&2
  exit 3
}
systemctl enable --now docker
mvn -version
docker --version
docker info >/dev/null 2>&1 || {
  echo "Docker daemon could not start. Check: journalctl -u docker" >&2
  exit 3
}
echo "Maven and Docker installed; run scripts/fin-verify-toolchain.sh as a permitted Docker user."
