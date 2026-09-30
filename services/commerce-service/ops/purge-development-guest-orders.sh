#!/usr/bin/env bash
set -euo pipefail
# PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD are consumed by psql without logging credentials.
if [[ "${1:-}" != "local" && "${1:-}" != "dev" ]] || [[ "${2:-}" != "DELETE-DEVELOPMENT-GUEST-ORDERS" ]]; then
  echo 'Usage: purge-development-guest-orders.sh local|dev DELETE-DEVELOPMENT-GUEST-ORDERS' >&2
  exit 2
fi
: "${PGHOST:?Set the explicit development database host}"
: "${PGDATABASE:?Set the explicit development database name}"
: "${PGUSER:?Set the explicit development database user}"
: "${COMMERCE_STOPPED:?Set COMMERCE_STOPPED=yes after stopping all Commerce instances and schedulers}"
[[ "$COMMERCE_STOPPED" == yes ]] || exit 2
[[ "${CONFIRM_DATABASE:-}" == "$PGDATABASE@$PGHOST:${PGPORT:-5432}" ]] || {
  echo 'CONFIRM_DATABASE must exactly equal PGDATABASE@PGHOST:PGPORT for the approved development target.' >&2
  exit 2
}
psql -X --set=ON_ERROR_STOP=1 --file="$(dirname "$0")/purge-development-guest-orders.sql"
