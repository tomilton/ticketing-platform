#!/bin/bash
# Prints a development token signed with HS256 and JWT_LOCAL_SECRET, valid for one hour, for the API
# started with docker compose (JWT_MODE=LOCAL_HS256 and the local profile). Usage:
#   deployment/local/token.sh <customer-uuid> [admin]
# The values come from deployment/.env. Only for local development: deployments verify tokens of the
# identity provider with its JWKS.
set -euo pipefail

readonly ENV_FILE="$(dirname "$0")/../.env"
readonly LIFETIME_SECONDS=3600

if [[ $# -lt 1 || $# -gt 2 || ( $# -eq 2 && "$2" != "admin" ) ]]; then
  echo "Usage: $0 <customer-uuid> [admin]" >&2
  exit 1
fi

env_value() {
  grep -E "^$1=" "${ENV_FILE}" | tail -n 1 | cut -d '=' -f 2-
}

base64url() {
  openssl base64 -A | tr '+/' '-_' | tr -d '='
}

subject="$1"
secret="$(env_value JWT_LOCAL_SECRET)"
issuer="$(env_value JWT_ISSUER)"
audience="$(env_value JWT_AUDIENCE)"
roles_claim="$(env_value JWT_ROLES_CLAIM)"
admin_role="$(env_value JWT_ADMIN_ROLE)"
now="$(date +%s)"

roles=""
if [[ $# -eq 2 ]]; then
  roles=",\"${roles_claim}\":[\"${admin_role}\"]"
fi

header="$(printf '%s' '{"alg":"HS256","typ":"JWT"}' | base64url)"
payload="$(printf '{"sub":"%s","iss":"%s","aud":"%s","iat":%s,"exp":%s%s}' "${subject}" "${issuer}" \
  "${audience}" "${now}" "$((now + LIFETIME_SECONDS))" "${roles}" | base64url)"
signature="$(printf '%s' "${header}.${payload}" | openssl dgst -sha256 -hmac "${secret}" -binary | base64url)"

echo "${header}.${payload}.${signature}"
