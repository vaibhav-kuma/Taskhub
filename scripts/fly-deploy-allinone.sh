#!/usr/bin/env bash
# Deploy TaskHub to an EXISTING Fly app when the org cannot create new apps/clusters
# (app-scoped token, or no billing on the org). Runs PostgreSQL 16 inside the same
# Machine, on a dedicated volume, reachable only on loopback.
#
#   FLY_APP=my-app FLY_VOLUME=pgdata ./scripts/fly-deploy-allinone.sh
#
# Differences from scripts/fly-deploy.sh:
#   - no `fly apps create`, no `fly postgres create`  (both need org-level permissions)
#   - database lives on the app's own volume, backed up into snapshots
# Prerequisite: fly.toml must use backend/Dockerfile.fly and mount FLY_VOLUME at /data.
set -euo pipefail

command -v flyctl >/dev/null || { echo "Install flyctl first"; exit 1; }
command -v jq >/dev/null || { echo "jq is required"; exit 1; }

FLY_APP="${FLY_APP:?set FLY_APP to the existing app name}"
FLY_VOLUME="${FLY_VOLUME:-pgdata}"
ADMIN_EMAIL="${TASKHUB_BOOTSTRAP_ADMIN_EMAIL:-${ADMIN_EMAIL:-}}"
ADMIN_PASSWORD="${TASKHUB_BOOTSTRAP_ADMIN_PASSWORD:-${ADMIN_PASSWORD:-}}"
DB_PASSWORD="${TASKHUB_DB_PASSWORD:-$(openssl rand -base64 24 | tr -d '/+=')}"

echo "==> App: $FLY_APP (volume: $FLY_VOLUME)"
if ! flyctl volumes list -a "$FLY_APP" | grep -q "$FLY_VOLUME"; then
  flyctl volumes create "$FLY_VOLUME" -a "$FLY_APP" --region "${REGION:-iad}" --size "${VOLUME_GB:-1}" --yes
fi

SECRETS=(
  "TASKHUB_JWT_SECRET=$(openssl rand -base64 48)"
  "TASKHUB_DB_PASSWORD=$DB_PASSWORD"
  "POSTGRES_PASSWORD=$DB_PASSWORD"
  "TASKHUB_DB_USER=taskhub"
  "TASKHUB_DB_POOL_SIZE=10"
  "TASKHUB_HEALTH_DETAILS=never"
  "TASKHUB_CORS_ORIGINS=${TASKHUB_CORS_ORIGINS:-https://$FLY_APP.fly.dev}"
)
if [[ -n "$ADMIN_EMAIL" && -n "$ADMIN_PASSWORD" ]]; then
  SECRETS+=("TASKHUB_BOOTSTRAP_ADMIN_EMAIL=$ADMIN_EMAIL" "TASKHUB_BOOTSTRAP_ADMIN_PASSWORD=$ADMIN_PASSWORD")
fi

echo "==> Staging secrets"
flyctl secrets set -a "$FLY_APP" --stage "${SECRETS[@]}"

# A stale URL secret would override the [env] value in fly.toml and point the API at a
# database that no longer exists — unset it explicitly.
flyctl secrets unset -a "$FLY_APP" TASKHUB_DB_URL >/dev/null 2>&1 || true

echo "==> Deploying (build uses backend/Dockerfile.fly per fly.toml)"
flyctl deploy -a "$FLY_APP" --remote-only --wait-timeout 600

URL="https://$FLY_APP.fly.dev"
echo "==> Health check $URL"
for _ in $(seq 1 30); do
  if curl -fsS "$URL/actuator/health" | grep -q '"status":"UP"'; then
    echo "API is UP at $URL"
    echo "Swagger: $URL/swagger-ui.html"
    exit 0
  fi
  sleep 10
done
echo "Health check failed — inspect: flyctl logs -a $FLY_APP"
exit 1
