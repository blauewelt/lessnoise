#!/usr/bin/env bash
# Automatic deployment, pull-based (server/README.md → «Automatic deployment»).
#
# Run by the systemd timer `stadtlaerm-deploy.timer` every 5 minutes, as root. It fetches the
# configured branch from GitHub; if the branch moved, it checks out the new commit, rebuilds and
# restarts the containers, and checks /healthz. If the new version does not answer, it goes back
# to the previous commit. Nothing is pushed to this server and no key for it is stored anywhere
# else: the server only reads the public repository.
#
#   Config:  /etc/stadtlaerm-deploy.conf   (BRANCH=main)
#   Logs:    journalctl -u stadtlaerm-deploy
#   By hand: sudo systemctl start stadtlaerm-deploy
set -euo pipefail

# The script lives in the repository it updates. Run from a private copy so that a checkout
# cannot change the file while bash is still reading it.
# Paths can be overridden for the tests (server/tests/test_deploy_script.py).
RUN_DIR=${RUN_DIR:-/run}
CONF=${CONF:-/etc/stadtlaerm-deploy.conf}
STATE=${STATE:-/var/lib/stadtlaerm-deploy}

if [ -z "${STADTLAERM_DEPLOY_COPY:-}" ]; then
  copy=$(mktemp "$RUN_DIR/stadtlaerm-deploy.XXXXXX")
  cp "$0" "$copy"
  STADTLAERM_DEPLOY_COPY=$copy exec bash "$copy" "$@"
fi
trap 'rm -f "$STADTLAERM_DEPLOY_COPY"' EXIT

REPO=${REPO:-/opt/stadtlaerm}
BRANCH=main
HEALTH_URL=${HEALTH_URL:-https://api.stadtlaerm.ch/healthz}
[ -r "$CONF" ] && . "$CONF"
[[ "$BRANCH" =~ ^[A-Za-z0-9._/-]+$ ]] || { echo "invalid BRANCH '$BRANCH'" >&2; exit 1; }

exec 9>"$RUN_DIR/stadtlaerm-deploy.lock"
flock -n 9 || { echo "another deployment is running"; exit 0; }

cd "$REPO"
git fetch --quiet origin "+refs/heads/$BRANCH:refs/remotes/origin/$BRANCH"
new=$(git rev-parse "origin/$BRANCH")
cur=$(git rev-parse HEAD)
mkdir -p "$STATE"
running=$(cat "$STATE/running" 2>/dev/null || true)
[ "$new" = "$cur" ] && [ "$running" = "$cur" ] && exit 0
# A commit that failed its health check is not tried again; the next push is.
[ "$new" = "$(cat "$STATE/failed" 2>/dev/null || true)" ] && exit 0

if ! git cat-file -e "$new:server/compose.yaml" 2>/dev/null; then
  echo "origin/$BRANCH (${new:0:7}) has no server/compose.yaml; not deploying it" >&2
  exit 1
fi

HOST=$(echo "$HEALTH_URL" | sed -E 's#^https://([^/:]+).*#\1#')

healthy() {  # $1 = commit that must be answering
  local i want=${1:0:12}
  for i in $(seq 1 "${HEALTH_TRIES:-30}"); do
    # Through Caddy on this machine, so the certificate and the proxy are checked too, and the
    # answer must name the commit just started (not a container that is still the old one).
    curl -fsS -m 5 --resolve "$HOST:443:127.0.0.1" "$HEALTH_URL" 2>/dev/null \
      | grep -q "\"commit\":\"$want\"" && return 0
    sleep "${HEALTH_WAIT_S:-2}"
  done
  return 1
}

up() {  # $1 = commit to run, $2 = commit that ran before
  git checkout --quiet -B "$BRANCH" "$1"
  (cd server && GIT_COMMIT=$(git rev-parse --short=12 HEAD) docker compose up -d --build --remove-orphans --quiet-pull)
  # Caddy reads its config from a bind-mounted file; compose does not notice when it changes.
  if ! git diff --quiet "$2" "$1" -- server/Caddyfile; then
    (cd server && docker compose restart caddy)
  fi
}

echo "deploying $BRANCH ${cur:0:7} -> ${new:0:7}"
up "$new" "$cur"
if healthy "$new"; then
  echo "$new" > "$STATE/running"
  rm -f "$STATE/failed"
  echo "deployed ${new:0:7}: $(git log -1 --format=%s "$new")"
  docker image prune -f >/dev/null 2>&1 || true
  exit 0
fi

echo "new version ${new:0:7} is not healthy; going back to ${cur:0:7}" >&2
echo "$new" > "$STATE/failed"
up "$cur" "$new"
healthy "$cur" && echo "rolled back to ${cur:0:7}" >&2 || echo "ROLLBACK ALSO UNHEALTHY" >&2
exit 1
