#!/usr/bin/env bash
# ==============================================================================
# Safe, Non-Destructive Deployment & Atomic Rollback Script for Hetzner CX23
# Target Infrastructure: Hetzner Ubuntu VPS (2.29.8.194 — 2 vCPU, 4 GB RAM, 40 GB)
# ==============================================================================
# Modes:
#   1. sudo ./deploy-hetzner.sh bootstrap-user "<SSH_PUBLIC_KEY>"
#      Creates dedicated limited-permission user 'tunneldeploy', configures
#      narrow sudoers rules, and opens minimum required UFW ports (22/tcp, 443/tcp, 51820/udp).
#   2. ./deploy-hetzner.sh deploy [PUBLIC_HOST]
#      Stages a new immutable release under /opt/tunnel-relay/releases/<TIMESTAMP>,
#      atomically updates /opt/tunnel-relay/current, restarts the container while
#      preserving WireGuard & TLS volumes, and runs health checks (auto-rolling back on failure).
#   3. ./deploy-hetzner.sh rollback [RELEASE_ID]
#      Atomically switches /opt/tunnel-relay/current back to the previous release
#      (or a specified RELEASE_ID) without deleting any existing release directory.
#   4. ./deploy-hetzner.sh status
#      Prints active release symlink, available releases, container health, and wg0 status.
# ==============================================================================
set -euo pipefail

BASE_DIR="/opt/tunnel-relay"
RELEASES_DIR="${BASE_DIR}/releases"
CURRENT_LINK="${BASE_DIR}/current"
DEFAULT_PUBLIC_HOST="2.29.8.194"
DEPLOY_USER="tunneldeploy"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ACTION="${1:-deploy}"

bootstrap_user() {
  local ssh_pub_key="${2:-}"
  if [ "$(id -u)" -ne 0 ]; then
    echo "[error] bootstrap-user must be run as root (via sudo)." >&2
    exit 1
  fi

  echo "[hetzner-setup] Creating dedicated user '${DEPLOY_USER}'..."
  if ! id -u "${DEPLOY_USER}" >/dev/null 2>&1; then
    adduser --disabled-password --gecos "Tunnel Relay Deploy" "${DEPLOY_USER}"
  fi
  usermod -aG docker "${DEPLOY_USER}"

  install -d -m 700 -o "${DEPLOY_USER}" -g "${DEPLOY_USER}" "/home/${DEPLOY_USER}/.ssh"
  if [ -n "${ssh_pub_key}" ]; then
    touch "/home/${DEPLOY_USER}/.ssh/authorized_keys"
    grep -qxF "${ssh_pub_key}" "/home/${DEPLOY_USER}/.ssh/authorized_keys" || \
      echo "${ssh_pub_key}" >> "/home/${DEPLOY_USER}/.ssh/authorized_keys"
    chown "${DEPLOY_USER}:${DEPLOY_USER}" "/home/${DEPLOY_USER}/.ssh/authorized_keys"
    chmod 600 "/home/${DEPLOY_USER}/.ssh/authorized_keys"
  fi

  install -d -m 755 -o "${DEPLOY_USER}" -g "${DEPLOY_USER}" "${RELEASES_DIR}"

  cat > "/etc/sudoers.d/${DEPLOY_USER}" <<EOF
${DEPLOY_USER} ALL=(root) NOPASSWD: /usr/bin/docker compose -f /opt/tunnel-relay/current/docker-compose.yml up -d --build
${DEPLOY_USER} ALL=(root) NOPASSWD: /usr/bin/docker compose -f /opt/tunnel-relay/current/docker-compose.yml ps
${DEPLOY_USER} ALL=(root) NOPASSWD: /usr/bin/systemctl status docker
EOF
  chmod 440 "/etc/sudoers.d/${DEPLOY_USER}"
  visudo -cf "/etc/sudoers.d/${DEPLOY_USER}"

  echo "[hetzner-setup] Configuring minimum required UFW ports (22/tcp, 443/tcp, 51820/udp)..."
  if command -v ufw >/dev/null 2>&1; then
    ufw allow 22/tcp comment "SSH Management"
    ufw allow 443/tcp comment "Tunnel Relay TLS/WebSocket Gateway"
    ufw allow 51820/udp comment "Tunnel Relay WireGuard VPN"
  fi

  echo "[hetzner-setup] Bootstrap complete for ${DEPLOY_USER} on ${DEFAULT_PUBLIC_HOST}."
}

verify_health() {
  local attempts=10
  local i=1
  while [ "${i}" -le "${attempts}" ]; do
    if curl -kfsS --max-time 4 "https://127.0.0.1:443/health" >/dev/null 2>&1; then
      echo "[hetzner-deploy] Health check passed on https://127.0.0.1:443/health"
      docker exec tunnel-relay-all-in-one wg show wg0 2>/dev/null || true
      return 0
    fi
    sleep 2
    i=$((i + 1))
  done
  return 1
}

deploy_release() {
  local public_host="${2:-${PUBLIC_HOST:-${DEFAULT_PUBLIC_HOST}}}"
  local release_id
  release_id="$(date -u +%Y%m%dT%H%M%SZ)"
  local target_dir="${RELEASES_DIR}/${release_id}"
  local previous_target=""

  if [ -L "${CURRENT_LINK}" ]; then
    previous_target="$(readlink -f "${CURRENT_LINK}" || true)"
  fi

  mkdir -p "${target_dir}"
  cp "${SCRIPT_DIR}/Dockerfile" \
     "${SCRIPT_DIR}/docker-compose.yml" \
     "${SCRIPT_DIR}/entrypoint.sh" \
     "${SCRIPT_DIR}/gateway.mjs" \
     "${SCRIPT_DIR}/peer-admin.py" \
     "${target_dir}/"
  chmod +x "${target_dir}/entrypoint.sh" "${target_dir}/peer-admin.py"

  ln -sfn "${target_dir}" "${CURRENT_LINK}"
  echo "[hetzner-deploy] Activated release ${release_id} -> ${CURRENT_LINK} (PUBLIC_HOST=${public_host})"

  PUBLIC_HOST="${public_host}" docker compose -f "${CURRENT_LINK}/docker-compose.yml" up -d --build

  if verify_health; then
    echo "[hetzner-deploy] Deployment ${release_id} is live and healthy on ${public_host}."
  else
    echo "[hetzner-deploy] Health check failed for ${release_id}." >&2
    if [ -n "${previous_target}" ] && [ -d "${previous_target}" ]; then
      echo "[hetzner-deploy] Automatically rolling back to ${previous_target}..." >&2
      ln -sfn "${previous_target}" "${CURRENT_LINK}"
      PUBLIC_HOST="${public_host}" docker compose -f "${CURRENT_LINK}/docker-compose.yml" up -d --build
    fi
    exit 1
  fi
}

rollback_release() {
  local requested_id="${2:-}"
  local target_dir=""

  if [ -n "${requested_id}" ]; then
    target_dir="${RELEASES_DIR}/${requested_id}"
  else
    # Pick the second most recent release directory
    target_dir="$(ls -1dt "${RELEASES_DIR}"/* 2>/dev/null | sed -n '2p' || true)"
  fi

  if [ -z "${target_dir}" ] || [ ! -d "${target_dir}" ]; then
    echo "[hetzner-rollback] No previous release found to roll back to." >&2
    exit 1
  fi

  ln -sfn "${target_dir}" "${CURRENT_LINK}"
  echo "[hetzner-rollback] Switched ${CURRENT_LINK} -> ${target_dir}"
  PUBLIC_HOST="${PUBLIC_HOST:-${DEFAULT_PUBLIC_HOST}}" docker compose -f "${CURRENT_LINK}/docker-compose.yml" up -d --build
  verify_health
}

show_status() {
  echo "=== Hetzner Tunnel Relay Deployment Status ==="
  echo "Current symlink : $(readlink -f "${CURRENT_LINK}" 2>/dev/null || echo 'not set')"
  echo "Available releases:"
  ls -1dt "${RELEASES_DIR}"/* 2>/dev/null || echo "  (none)"
  echo "Container status:"
  docker ps --filter "name=tunnel-relay-all-in-one" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" || true
  echo "Gateway /health:"
  curl -kfsS --max-time 3 "https://127.0.0.1:443/health" || echo "  unreachable"
  echo ""
}

case "${ACTION}" in
  bootstrap-user)
    bootstrap_user "$@"
    ;;
  deploy)
    deploy_release "$@"
    ;;
  rollback)
    rollback_release "$@"
    ;;
  status)
    show_status
    ;;
  *)
    echo "Usage: $0 {bootstrap-user|deploy|rollback|status}" >&2
    exit 1
    ;;
esac
