#!/usr/bin/env bash
#
# Sets up a working DFSP simulator against a Mojaloop hub (e.g. ml-core-test-harness):
#
#   1. checks that Keycloak, the simulator and the hub are reachable
#   2. imports the Keycloak realm if it's missing
#   3. provisions the hub for the currency (hub accounts, settlement model, optional ALS oracle)
#   4. creates each DFSP in the simulator and onboards it in central-ledger
#      (participant, NET_DEBIT_CAP, 11 callback endpoints, activation, funds-in)
#   5. creates one demo customer per DFSP (account, deposit, MSISDN registered in the ALS)
#   6. checks discovery between the DFSPs and, optionally, sends a test transfer
#
# Running it again is safe: resources that already exist are skipped.
#
# Usage:   ./scripts/setup-simulator.sh
# Config:  the environment variables below, e.g.
#          CURRENCY=USD SIMULATOR_PUBLIC_URL=http://172.17.0.1:8090 ./scripts/setup-simulator.sh
#
# Needs: bash, curl, jq, uuidgen. The simulator app must already be running (./mvnw quarkus:dev).

set -uo pipefail

# --- configuration ---------------------------------------------------------------------------

SIM_URL="${SIM_URL:-http://localhost:8090}"
# the app reads SIMULATOR_PUBLIC_URL from .env; use the same value here unless it is set explicitly
if [[ -z ${SIMULATOR_PUBLIC_URL:-} && -f "$(dirname "$0")/../.env" ]]; then
  SIMULATOR_PUBLIC_URL=$(sed -n 's/^SIMULATOR_PUBLIC_URL=//p' "$(dirname "$0")/../.env" | tail -1)
fi

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-dfsp}"
KEYCLOAK_ADMIN_USER="${KEYCLOAK_ADMIN_USER:-admin}"
KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
CLIENT_ID="${CLIENT_ID:-dfsp-simulator}"
SIM_ADMIN_USER="${SIM_ADMIN_USER:-sim-admin}"
SIM_ADMIN_PASSWORD="${SIM_ADMIN_PASSWORD:-admin}"

# hub services; must match the HUB_*_URL settings of the simulator app
HUB_ADMIN_URL="${HUB_ADMIN_URL:-http://localhost:3001}"          # central-ledger admin API
HUB_ALS_ADMIN_URL="${HUB_ALS_ADMIN_URL:-http://localhost:4001}"  # account-lookup-service admin API
HUB_NAME="${HUB_NAME:-Hub}"                                      # central-ledger hub participant

# set to "false" when the hub is already provisioned (e.g. by the test harness setup collections)
PROVISION_HUB="${PROVISION_HUB:-true}"
# MSISDN oracle endpoint as seen by the ALS; leave empty to skip oracle registration
ORACLE_URL="${ORACLE_URL:-}"

CURRENCY="${CURRENCY:-CVE}"
NET_DEBIT_CAP="${NET_DEBIT_CAP:-1000000}"
INITIAL_FUNDS="${INITIAL_FUNDS:-1000000}"

# DFSPs to host, as "fspId:keycloakUser:password:customerName:msisdn:deposit" entries separated by ';'.
# The first one pays the second in the checks. Each Keycloak user needs fsp_id = fspId.
DFSP_SPECS="${DFSPS:-cvbank:cvbank-user:cvbank:Maria Silva:2389910000:5000;cvwallet:cvwallet-user:cvwallet:Joao Lopes:2389920000:0}"
IFS=';' read -r -a DFSPS <<<"$DFSP_SPECS"

# send a test transfer from the first DFSP's customer to the second's
TEST_TRANSFER="${TEST_TRANSFER:-true}"
TEST_AMOUNT="${TEST_AMOUNT:-100}"

# --- helpers ---------------------------------------------------------------------------------

BOLD=$'\e[1m'; GREEN=$'\e[32m'; YELLOW=$'\e[33m'; RED=$'\e[31m'; RESET=$'\e[0m'
WARNINGS=0

step() { printf '\n%s== %s%s\n' "$BOLD" "$*" "$RESET"; }
ok()   { printf '  %s✓%s %s\n' "$GREEN" "$RESET" "$*"; }
skip() { printf '  %s•%s %s\n' "$YELLOW" "$RESET" "$*"; }
warn() { printf '  %s!%s %s\n' "$YELLOW" "$RESET" "$*"; WARNINGS=$((WARNINGS + 1)); }
die()  { printf '  %s✗ %s%s\n' "$RED" "$*" "$RESET" >&2; exit 1; }

RESPONSE_FILE=$(mktemp)
trap 'rm -f "$RESPONSE_FILE"' EXIT

# http METHOD URL [BODY] [TOKEN] -> sets STATUS and BODY
http() {
  local method=$1 url=$2 data=${3:-} token=${4:-}
  local args=(-s -o "$RESPONSE_FILE" -w '%{http_code}' -X "$method" "$url"
    -H 'Content-Type: application/json' -H 'Accept: application/json')
  [[ -n $token ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n $data ]] && args+=(-d "$data")
  STATUS=$(curl "${args[@]}" 2>/dev/null) || STATUS=000
  BODY=$(cat "$RESPONSE_FILE" 2>/dev/null || true)
  : >"$RESPONSE_FILE"
}

is_2xx() { [[ $STATUS == 2* ]]; }

# central-ledger answers 400 with "already exists"-style messages for resources that exist,
# and 500 "Participant Limit or Initial Position already set" for initialPositionAndLimits
already_exists() {
  { [[ $STATUS == 400 || $STATUS == 409 ]] && grep -qiE 'already|exist|duplicate' <<<"$BODY"; } ||
    { [[ $STATUS == 500 ]] && grep -qi 'already set' <<<"$BODY"; }
}

# hub call that tolerates "already exists"; $1 is a label
hub_call() {
  local label=$1; shift
  http "$@"
  if is_2xx; then ok "$label"
  elif already_exists; then skip "$label (already exists)"
  else warn "$label failed: HTTP $STATUS $BODY"; return 1
  fi
}

token() { # token USER PASSWORD -> access token on stdout
  curl -s "$KEYCLOAK_URL/realms/$KEYCLOAK_REALM/protocol/openid-connect/token" \
    -d grant_type=password -d client_id="$CLIENT_ID" -d username="$1" -d password="$2" | jq -r '.access_token // empty'
}

for tool in curl jq uuidgen; do command -v "$tool" >/dev/null || die "$tool is required"; done
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REALM_FILE="$SCRIPT_DIR/../src/main/resources/keycloak/dfsp-realm.json"

# --- 1. reachability -------------------------------------------------------------------------

step "1. Checking services"
http GET "$KEYCLOAK_URL/realms/master";  [[ $STATUS == 200 ]] && ok "Keycloak at $KEYCLOAK_URL" || die "Keycloak not reachable at $KEYCLOAK_URL"
http GET "$SIM_URL/q/openapi";           [[ $STATUS == 200 ]] && ok "simulator at $SIM_URL" || die "simulator not reachable at $SIM_URL (start it with ./mvnw quarkus:dev)"
http GET "$HUB_ADMIN_URL/health";        is_2xx && ok "central-ledger at $HUB_ADMIN_URL" || die "central-ledger not reachable at $HUB_ADMIN_URL (HTTP $STATUS)"

# --- 2. Keycloak realm -----------------------------------------------------------------------

step "2. Keycloak realm '$KEYCLOAK_REALM'"
http GET "$KEYCLOAK_URL/realms/$KEYCLOAK_REALM"
if [[ $STATUS == 200 ]]; then
  skip "realm already exists"
else
  KC_ADMIN=$(curl -s "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" -d grant_type=password \
    -d client_id=admin-cli -d username="$KEYCLOAK_ADMIN_USER" -d password="$KEYCLOAK_ADMIN_PASSWORD" | jq -r '.access_token // empty')
  [[ -n $KC_ADMIN ]] || die "cannot log in to Keycloak as $KEYCLOAK_ADMIN_USER (set KEYCLOAK_ADMIN_USER/KEYCLOAK_ADMIN_PASSWORD)"
  [[ -f $REALM_FILE ]] || die "realm file not found: $REALM_FILE"
  http POST "$KEYCLOAK_URL/admin/realms" "$(jq --arg r "$KEYCLOAK_REALM" '.realm = $r' "$REALM_FILE")" "$KC_ADMIN"
  is_2xx && ok "realm imported from $REALM_FILE" || die "realm import failed: HTTP $STATUS $BODY"
fi
ADMIN=$(token "$SIM_ADMIN_USER" "$SIM_ADMIN_PASSWORD")
[[ -n $ADMIN ]] && ok "token for $SIM_ADMIN_USER" || die "cannot get a token for $SIM_ADMIN_USER"

# --- 3. hub provisioning ---------------------------------------------------------------------

step "3. Hub provisioning for $CURRENCY"
if [[ $PROVISION_HUB != true ]]; then
  skip "PROVISION_HUB=$PROVISION_HUB, assuming the hub is already provisioned"
else
  hub_call "hub account HUB_MULTILATERAL_SETTLEMENT/$CURRENCY" POST "$HUB_ADMIN_URL/participants/$HUB_NAME/accounts" \
    "{\"type\":\"HUB_MULTILATERAL_SETTLEMENT\",\"currency\":\"$CURRENCY\"}"
  hub_call "hub account HUB_RECONCILIATION/$CURRENCY" POST "$HUB_ADMIN_URL/participants/$HUB_NAME/accounts" \
    "{\"type\":\"HUB_RECONCILIATION\",\"currency\":\"$CURRENCY\"}"
  hub_call "settlement model DEFERREDNET$CURRENCY" POST "$HUB_ADMIN_URL/settlementModels" "$(jq -n --arg c "$CURRENCY" '{
      name: ("DEFERREDNET" + $c), settlementGranularity: "NET", settlementInterchange: "MULTILATERAL",
      settlementDelay: "DEFERRED", requireLiquidityCheck: true, ledgerAccountType: "POSITION",
      settlementAccountType: "SETTLEMENT", autoPositionReset: true, currency: $c }')" \
    || skip "a default settlement model may already cover $CURRENCY"
  if [[ -n $ORACLE_URL ]]; then
    hub_call "ALS oracle MSISDN/$CURRENCY -> $ORACLE_URL" POST "$HUB_ALS_ADMIN_URL/oracles" "$(jq -n --arg u "$ORACLE_URL" --arg c "$CURRENCY" '{
        oracleIdType: "MSISDN", endpoint: { value: $u, endpointType: "URL" }, currency: $c, isDefault: true }')"
  else
    skip "ORACLE_URL not set, assuming the ALS already has an MSISDN oracle"
  fi
fi

# --- 4. DFSPs: simulator + hub onboarding ----------------------------------------------------

step "4. DFSPs"
for spec in "${DFSPS[@]}"; do
  IFS=: read -r FSP _ _ _ _ _ <<<"$spec"
  echo "  ${BOLD}$FSP${RESET}"

  # in the simulator: opens the DFSP's POSITION/SETTLEMENT ledger accounts
  http POST "$SIM_URL/api/admin/dfsps" "{\"fspId\":\"$FSP\",\"displayName\":\"$FSP\",\"currency\":\"$CURRENCY\",
    \"netDebitCap\":$NET_DEBIT_CAP,\"initialPosition\":0,\"initialFunds\":$INITIAL_FUNDS}" "$ADMIN"
  if is_2xx; then ok "created in the simulator"
  elif [[ $STATUS == 409 ]]; then skip "already in the simulator"
  else die "creating $FSP in the simulator failed: HTTP $STATUS $BODY"
  fi
  http GET "$SIM_URL/api/admin/dfsps/$FSP" "" "$ADMIN"
  # SIMULATOR_PUBLIC_URL overrides the URL stored when the DFSP was created, so endpoints can be re-pointed
  if [[ -n ${SIMULATOR_PUBLIC_URL:-} ]]; then BASE="$SIMULATOR_PUBLIC_URL/fspiop/$FSP"
  else BASE=$(jq -r .callbackBaseUrl <<<"$BODY")
  fi
  DFSP_CURRENCY=$(jq -r .currency <<<"$BODY")
  [[ $DFSP_CURRENCY == "$CURRENCY" ]] || warn "$FSP exists in the simulator with currency $DFSP_CURRENCY, not $CURRENCY"

  # in the hub (central-ledger admin API)
  hub_call "participant" POST "$HUB_ADMIN_URL/participants" "{\"name\":\"$FSP\",\"currency\":\"$CURRENCY\"}"
  hub_call "NET_DEBIT_CAP $NET_DEBIT_CAP and initial position" POST "$HUB_ADMIN_URL/participants/$FSP/initialPositionAndLimits" \
    "{\"currency\":\"$CURRENCY\",\"limit\":{\"type\":\"NET_DEBIT_CAP\",\"value\":$NET_DEBIT_CAP},\"initialPosition\":0}"

  ENDPOINTS=(
    "FSPIOP_CALLBACK_URL_PARTIES_GET|$BASE/parties/{{partyIdType}}/{{partyIdentifier}}"
    "FSPIOP_CALLBACK_URL_PARTIES_PUT|$BASE/parties/{{partyIdType}}/{{partyIdentifier}}"
    "FSPIOP_CALLBACK_URL_PARTIES_PUT_ERROR|$BASE/parties/{{partyIdType}}/{{partyIdentifier}}/error"
    "FSPIOP_CALLBACK_URL_PARTICIPANT_PUT|$BASE/participants/{{partyIdType}}/{{partyIdentifier}}"
    "FSPIOP_CALLBACK_URL_PARTICIPANT_PUT_ERROR|$BASE/participants/{{partyIdType}}/{{partyIdentifier}}/error"
    "FSPIOP_CALLBACK_URL_PARTICIPANT_BATCH_PUT|$BASE/participants/{{requestId}}"
    "FSPIOP_CALLBACK_URL_PARTICIPANT_BATCH_PUT_ERROR|$BASE/participants/{{requestId}}/error"
    "FSPIOP_CALLBACK_URL_QUOTES|$BASE"
    "FSPIOP_CALLBACK_URL_TRANSFER_POST|$BASE/transfers"
    "FSPIOP_CALLBACK_URL_TRANSFER_PUT|$BASE/transfers/{{transferId}}"
    "FSPIOP_CALLBACK_URL_TRANSFER_ERROR|$BASE/transfers/{{transferId}}/error"
  )
  failed=0
  for e in "${ENDPOINTS[@]}"; do
    http POST "$HUB_ADMIN_URL/participants/$FSP/endpoints" "$(jq -n --arg t "${e%%|*}" --arg v "${e#*|}" '{type: $t, value: $v}')"
    is_2xx || { warn "endpoint ${e%%|*} failed: HTTP $STATUS $BODY"; failed=1; }
  done
  [[ $failed == 0 ]] && ok "${#ENDPOINTS[@]} callback endpoints -> $BASE"

  hub_call "activated" PUT "$HUB_ADMIN_URL/participants/$FSP" '{"isActive":true}'

  # funds-in only once: skip when the hub settlement account already holds funds
  http GET "$HUB_ADMIN_URL/participants/$FSP/accounts"
  SETTLEMENT_ID=$(jq -r --arg c "$CURRENCY" '.[]? | select(.ledgerAccountType == "SETTLEMENT" and .currency == $c) | .id' <<<"$BODY" | head -1)
  SETTLEMENT_VALUE=$(jq -r --arg c "$CURRENCY" '.[]? | select(.ledgerAccountType == "SETTLEMENT" and .currency == $c) | .value // 0' <<<"$BODY" | head -1)
  if [[ -z $SETTLEMENT_ID ]]; then
    warn "no $CURRENCY SETTLEMENT account for $FSP in the hub, cannot record funds"
  elif [[ ${SETTLEMENT_VALUE#-} != 0 && -n ${SETTLEMENT_VALUE#-} ]]; then
    skip "settlement account already funded ($SETTLEMENT_VALUE)"
  else
    hub_call "funds in $INITIAL_FUNDS $CURRENCY" POST "$HUB_ADMIN_URL/participants/$FSP/accounts/$SETTLEMENT_ID" "$(jq -n \
      --arg id "$(uuidgen)" --arg a "$INITIAL_FUNDS" --arg c "$CURRENCY" '{
        transferId: $id, externalReference: "dfsp-simulator-setup", action: "recordFundsIn",
        reason: "initial funds", amount: { amount: $a, currency: $c } }')"
  fi
done

# --- 5. demo customers -----------------------------------------------------------------------

step "5. Demo customers"
for spec in "${DFSPS[@]}"; do
  IFS=: read -r FSP USER PASS NAME MSISDN DEPOSIT <<<"$spec"
  T=$(token "$USER" "$PASS")
  [[ -n $T ]] || { warn "cannot get a token for $USER, skipping $FSP customers"; continue; }

  http GET "$SIM_URL/api/parties" "" "$T"
  PARTY=$(jq -r --arg m "$MSISDN" 'first(.[] | select(.idValue == $m) | .id) // empty' <<<"$BODY" 2>/dev/null)
  if [[ -n $PARTY ]]; then
    # the party exists in the simulator; register it in the ALS again in case the hub was reset
    http PUT "$SIM_URL/api/parties/$PARTY/registration" "" "$T"
    if is_2xx; then skip "$FSP: MSISDN $MSISDN already exists, re-registered in the ALS"
    else warn "$FSP: re-registering MSISDN $MSISDN failed: HTTP $STATUS $BODY"
    fi
    continue
  fi
  http POST "$SIM_URL/api/customers" "$(jq -n --arg n "$NAME" --arg m "$MSISDN" '{name: $n, contact: {phone: $m}}')" "$T"
  is_2xx || die "$FSP: creating customer failed: HTTP $STATUS $BODY"
  CUSTOMER=$(jq .id <<<"$BODY")
  http POST "$SIM_URL/api/accounts" "{\"customerId\":$CUSTOMER}" "$T"
  is_2xx || die "$FSP: opening account failed: HTTP $STATUS $BODY"
  ACCOUNT=$(jq .id <<<"$BODY")
  if [[ $DEPOSIT != 0 ]]; then
    http POST "$SIM_URL/api/accounts/$ACCOUNT/deposits" "{\"amount\":$DEPOSIT}" "$T"
    is_2xx || die "$FSP: deposit failed: HTTP $STATUS $BODY"
  fi
  # registers the MSISDN in the hub's ALS
  http POST "$SIM_URL/api/parties" "{\"idType\":\"MSISDN\",\"idValue\":\"$MSISDN\",\"accountId\":$ACCOUNT}" "$T"
  if is_2xx; then ok "$FSP: $NAME, account $ACCOUNT, deposit $DEPOSIT, MSISDN $MSISDN registered in the ALS"
  else warn "$FSP: registering MSISDN $MSISDN failed: HTTP $STATUS $BODY"
  fi
done

# --- 6. checks -------------------------------------------------------------------------------

step "6. Checks"
IFS=: read -r PAYER_FSP PAYER_USER PAYER_PASS _ PAYER_MSISDN _ <<<"${DFSPS[0]}"
IFS=: read -r PAYEE_FSP _ _ _ PAYEE_MSISDN _ <<<"${DFSPS[1]}"
PAYER=$(token "$PAYER_USER" "$PAYER_PASS")

http GET "$SIM_URL/api/parties/lookup/MSISDN/$PAYEE_MSISDN" "" "$PAYER"
if is_2xx && [[ $(jq -r .fspId <<<"$BODY") == "$PAYEE_FSP" ]]; then
  ok "discovery: $PAYER_FSP found MSISDN $PAYEE_MSISDN at $PAYEE_FSP ($(jq -r .name <<<"$BODY"))"
else
  warn "discovery of $PAYEE_MSISDN from $PAYER_FSP failed: HTTP $STATUS $BODY"
  warn "if it timed out (504), the hub cannot reach the simulator's callback URL; see HUB_SETUP.md"
  TEST_TRANSFER=false
fi

if [[ $TEST_TRANSFER == true ]]; then
  http POST "$SIM_URL/api/transfers" "{\"from\":{\"idType\":\"MSISDN\",\"idValue\":\"$PAYER_MSISDN\"},
    \"to\":{\"idType\":\"MSISDN\",\"idValue\":\"$PAYEE_MSISDN\"},\"amount\":$TEST_AMOUNT,\"currency\":\"$CURRENCY\",
    \"note\":\"setup check\",\"autoAccept\":true}" "$PAYER"
  if ! is_2xx; then
    warn "test transfer rejected: HTTP $STATUS $BODY"
  else
    TRANSFER_ID=$(jq -r .transferId <<<"$BODY")
    STATE=""
    for _ in $(seq 1 30); do
      http GET "$SIM_URL/api/transfers/$TRANSFER_ID" "" "$PAYER"
      STATE=$(jq -r .state <<<"$BODY")
      [[ $STATE == COMMITTED || $STATE == ABORTED || $STATE == EXPIRED ]] && break
      sleep 1
    done
    if [[ $STATE == COMMITTED ]]; then
      ok "test transfer $TRANSFER_ID: $TEST_AMOUNT $CURRENCY $PAYER_FSP -> $PAYEE_FSP COMMITTED"
    else
      warn "test transfer $TRANSFER_ID ended in $STATE: $(jq -r '"\(.errorCode // "") \(.errorDescription // "")"' <<<"$BODY")"
    fi
  fi
fi

# --- summary ---------------------------------------------------------------------------------

step "Done"
if [[ $WARNINGS == 0 ]]; then
  ok "simulator is ready"
else
  warn "finished with $WARNINGS warning(s); see above"
fi
cat <<EOF

  Log in (password grant, no client secret):
    curl -s $KEYCLOAK_URL/realms/$KEYCLOAK_REALM/protocol/openid-connect/token \\
      -d grant_type=password -d client_id=$CLIENT_ID -d username=$PAYER_USER -d password=$PAYER_PASS | jq -r .access_token
  API docs: $SIM_URL/q/swagger-ui
EOF
[[ $WARNINGS == 0 ]]
