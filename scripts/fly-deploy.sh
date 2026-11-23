#!/usr/bin/env bash
# One-shot Fly.io deployment. Requires flyctl and an authenticated session (fly auth login).
#
#   FLY_APP=taskhub-api TASKHUB_CORS_ORIGINS=https://app.example.com ./scripts/fly-deploy.sh
#
# Creates the app if needed, provisions PostgreSQL, wires JDBC secrets, deploys and
# smoke-tests the public URL.
set -euo pipefail

command -v flyctl >/dev/null || { echo "Install flyctl: https://fly.io/docs/hands-on/install-flyctl/"; exit 1; }
command -v jq >/dev/null || { echo "jq is required"; exit 1; }

FLY_APP="${FLY_APP:-taskhub-api}"
REGION="${REGION:-iad}"
DB_APP="${DB_APP:-taskhub-db}"
CORS="${TASKHUB_CORS_ORIGINS:-https://localhost}"

echo "==> App: $FLY_APP (region $REGION)"
if ! flyctl apps list | awk '{print $1}' | grep -qx "$FLY_APP"; then
  flyctl apps create "$FLY_APP"
fi

echo "==> PostgreSQL cluster: $DB_APP"
if ! flyctl postgres list 2>/dev/null | grep -q "$DB_APP"; then
  flyctl postgres create --name "$DB_APP" --region "$REGION" --initial-cluster-size 1 \
    --vm-size shared-cpu-1x --volume-size 10
fi
flyctl postgres attach "$DB_APP" --app "$FLY_APP" || true

echo "==> Reading database credentials"
DB_JSON=$(flyctl postgres config show --app "$DB_APP" --json)
DB_HOST=$(jq -r '.host' <<<"$DB_JSON")
DB_PORT=$(jq -r '.port' <<<"$DB_JSON")
DB_USER=$(jq -r '.user' <<<"$DB_JSON")
DB_PASS=$(jq -r '.password' <<<"$DB_JSON")
DB_NAME=$(jq -r '.dbname // "postgres"' <<<"$DB_JSON")

echo "==> Setting secrets"
flyctl secrets set --app "$FLY_APP" --stage \
  SPRING_PROFILES_ACTIVE=prod \
  TASKHUB_JWT_SECRET="$(openssl rand -base64 48)" \
  TASKHUB_DB_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}?sslmode=require" \
  TASKHUB_DB_USER="$DB_USER" \
  TASKHUB_DB_PASSWORD="$DB_PASS" \
  TASKHUB_CORS_ORIGINS="$CORS" \
  TASKHUB_HEALTH_DETAILS=never

echo "==> Deploying"
flyctl deploy --app "$FLY_APP" --remote-only

PUBLIC_URL="https://${FLY_APP}.fly.dev"
echo "==> Health check $PUBLIC_URL/actuator/health"
for i in $(seq 1 20); do
  if curl -fsS "$PUBLIC_URL/actuator/health" | grep -q '"status":"UP"'; then
    echo "API is UP at $PUBLIC_URL"
    echo "OpenAPI: $PUBLIC_URL/swagger-ui.html"
    echo "Next: create the first admin account (see docs/DEPLOYMENT.md#first-admin)."
    exit 0
  fi
  sleep 10
done

echo "Health check did not pass; inspect with: flyctl logs --app $FLY_APP"
exit 1
