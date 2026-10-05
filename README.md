# dfsp_adapter — Mojaloop DFSP simulator

A Quarkus service that hosts several simulated DFSPs behind one deployment and connects them to a
real Mojaloop hub over **FSPIOP v1.1**. Each DFSP keeps its own **inner double-entry ledger**. A
Keycloak user operates one DFSP (the `fsp_id` claim) and sends P2P transfers as the **payer**. The
app answers the hub as the **payee** for every hosted DFSP. Settlement stays with the hub; the
simulator **reconciles** hub positions and settlements against its ledger.

Supported flows: discovery (`/parties`), quoting (`/quotes`), transfers (`/transfers`), settlement
reconciliation.

## How it fits together

```
 Keycloak user (fsp_id=cvbank)                        Mojaloop hub
        │  /api/**  (bearer token)                  ┌─────────────────────────┐
        ▼                                           │ ALS · quoting ·         │
 ┌──────────────────────────────┐   FSPIOP v1.1     │ ml-api-adapter ·        │
 │ dfsp_adapter                 │ ────────────────▶ │ central-ledger ·        │
 │  payer flow (cvbank)         │                   │ central-settlement      │
 │  payee flow (cvwallet, ...)  │ ◀──────────────── │                         │
 │  inner ledger · reconciliation│ /fspiop/{fspId}  └─────────────────────────┘
 └──────────────────────────────┘
```

Every hosted DFSP is registered in the hub with the callback base `{public-base-url}/fspiop/{fspId}`.
The path therefore says which DFSP a hub request is for, and the hub sees separate participants.

## Running locally

Requirements: Java 21, Docker, PostgreSQL 16, Keycloak 26, and a Mojaloop hub. For example,
[ml-core-test-harness](https://github.com/mojaloop/ml-core-test-harness) provides ALS, quoting,
ml-api-adapter, central-ledger and central-settlement.

1. **Database:** create a `dfsp` database. Put `DB_PASSWORD=...` in `.env`; it's git-ignored and
   dev mode reads it. Flyway migrates the schema at startup.
2. **Keycloak:** import `src/main/resources/keycloak/dfsp-realm.json`, either from the admin
   console (*Create realm → Browse*) or by starting Keycloak with `--import-realm` and that file in
   `/opt/keycloak/data/import`. It creates:
   - the realm `dfsp`;
   - the public client `dfsp-simulator`, with direct-access grants for curl;
   - the `fsp_id` claim mapper;
   - the roles `admin` and `dfsp-operator`;
   - the users `sim-admin/admin`, `cvbank-user/cvbank` and `cvwallet-user/cvwallet`.
3. **Hub:** set the hub URLs if they differ from the ml-core-test-harness defaults (see below).
   Set `SIMULATOR_PUBLIC_URL` to the address the hub's containers use to reach this app. The
   default is `http://host.docker.internal:8090`, which doesn't resolve on Linux; use the hub
   network's gateway IP instead (e.g. `http://172.24.0.1:8090`) and allow port 8090 through the
   host firewall. ml-core-test-harness doesn't publish quoting-service, ml-api-adapter or
   central-settlement to localhost either. [HUB_SETUP.md](HUB_SETUP.md#2-networking) covers both.
4. **Start the app:** `./mvnw quarkus:dev`. It listens on **8090**, because local Keycloak uses
   8080. Swagger UI is at <http://localhost:8090/q/swagger-ui>.

| Env var | Default |
| --- | --- |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/dfsp` / `postgres` / – |
| `KEYCLOAK_URL` / `KEYCLOAK_REALM` | `http://localhost:8080` / `dfsp` |
| `SIMULATOR_PUBLIC_URL` | `http://host.docker.internal:8090` |
| `HUB_ALS_URL` | `http://localhost:4002` |
| `HUB_QUOTES_URL` | `http://localhost:3002` |
| `HUB_TRANSFERS_URL` | `http://localhost:3000` |
| `HUB_ADMIN_URL` (central-ledger) | `http://localhost:3001` |
| `HUB_SETTLEMENT_URL` (central-settlement) | `http://localhost:3007/v2` |
| `ILP_SECRET` | dev value; set a real one outside dev |

Other settings (`application.properties`, prefix `simulator.`):
- `callback-timeout` (30s) and `transfer-expiry` (60s).
- `payee.transfer-state`: `COMMITTED` credits the payee straight away when it fulfils. `RESERVED`
  waits for the hub's `PATCH` notification.
- `payee.fee-percent` (0).
- `settlement.reconcile-every` (5m, or `off`).

## Walkthrough: cvbank pays a cvwallet customer

[P2P_WALKTHROUGH.md](P2P_WALKTHROUGH.md) goes through the same flow one call at a time, with real
responses, the manual accept steps, the transfer states and what the hub does in each phase.

`./scripts/setup-simulator.sh` automates all of this: the realm, hub provisioning and onboarding,
demo customers, then discovery and a test transfer. See [HUB_SETUP.md](HUB_SETUP.md). The manual
steps are:

```shell
tok() { curl -s -d grant_type=password -d client_id=dfsp-simulator -d username=$1 -d password=$2 \
  http://localhost:8080/realms/dfsp/protocol/openid-connect/token | jq -r .access_token; }
ADMIN=$(tok sim-admin admin); BANK=$(tok cvbank-user cvbank); WALLET=$(tok cvwallet-user cvwallet)
API=http://localhost:8090/api; J='Content-Type: application/json'

# 1. admin: create both DFSPs and onboard them in the hub (participant, NDC, endpoints, funds-in)
for f in cvbank cvwallet; do
  curl -s -H "Authorization: Bearer $ADMIN" -H "$J" $API/admin/dfsps \
    -d "{\"fspId\":\"$f\",\"currency\":\"CVE\",\"netDebitCap\":100000,\"initialFunds\":100000}"
  curl -s -X POST -H "Authorization: Bearer $ADMIN" $API/admin/dfsps/$f/onboard
done

# 2. each DFSP: customer -> account -> deposit -> party (registers the MSISDN in the ALS)
C=$(curl -s -H "Authorization: Bearer $BANK" -H "$J" $API/customers -d '{"name":"Maria Silva"}' | jq .id)
A=$(curl -s -H "Authorization: Bearer $BANK" -H "$J" $API/accounts -d "{\"customerId\":$C}" | jq .id)
curl -s -H "Authorization: Bearer $BANK" -H "$J" $API/accounts/$A/deposits -d '{"amount":500}'
curl -s -H "Authorization: Bearer $BANK" -H "$J" $API/parties -d "{\"idType\":\"MSISDN\",\"idValue\":\"2389910000\",\"accountId\":$A}"
# ...same with $WALLET for the payee, e.g. MSISDN 2389920000

# 3. cvbank sends 100 CVE (autoAccept runs discovery -> quote -> transfer without stopping)
curl -s -H "Authorization: Bearer $BANK" -H "$J" $API/transfers -d '{
  "from":{"idType":"MSISDN","idValue":"2389910000"},
  "to":{"idType":"MSISDN","idValue":"2389920000"},
  "amount":100,"currency":"CVE","autoAccept":true}'
curl -s -H "Authorization: Bearer $BANK" $API/transfers/<transferId>     # -> COMMITTED
curl -s -H "Authorization: Bearer $WALLET" $API/transfers/incoming       # payee view
```

Without `autoAccept`, the transfer stops at `WAITING_FOR_PARTY_ACCEPTANCE` and then at
`WAITING_FOR_QUOTE_ACCEPTANCE`. Continue it with `PUT /api/transfers/{id}/accept-party` and then
`/accept-quote`, sending `{"accept":true|false}`.

## API overview

| | |
| --- | --- |
| `GET/POST /api/admin/dfsps`, `GET/PUT /api/admin/dfsps/{fspId}`, `POST .../{fspId}/onboard` | hosted DFSPs (role `admin`) |
| `GET /api/dfsp` | the logged-in DFSP with its position and settlement accounts |
| `/api/customers`, `/api/accounts` (`POST {id}/deposits`, `GET {id}/statement`) | customers and ledger accounts |
| `/api/parties`, `PUT /api/parties/{id}/registration`, `GET /api/parties/lookup/{type}/{id}` | ALS-registered identifiers, re-registration after a hub reset, synchronous discovery |
| `/api/transfers`, `GET /api/transfers/incoming` | outbound P2P transfers, received transfers |
| `GET /api/settlements`, `POST /api/settlements/reconcile` (admin) | reconciliation results |
| `/fspiop/{fspId}/...` | FSPIOP endpoints the hub calls (not Keycloak-protected) |

`test quarkus hexa.postman_collection.json` has a request for every `/api` endpoint, grouped by
resource. See [AUTH.md](AUTH.md#postman--bruno) for how to use it.

## Ledger model

Account types:
- **CUSTOMER:** a customer's wallet.
- **POSITION:** the DFSP's net debit towards the hub. A positive balance means the DFSP owes the
  hub.
- **SETTLEMENT:** the DFSP's own funds.

How each operation is booked:
- **Deposit:** `CUSTOMER +x / SETTLEMENT −x`.
- **Payer:** reserves `CUSTOMER −x / POSITION +x` (checked against funds and the net debit cap),
  then commits on the hub's fulfil callback.
- **Payee:** books `POSITION −x / CUSTOMER +(x − fee) / SETTLEMENT +fee`.
- **SETTLED hub settlement:** `POSITION −net / SETTLEMENT +net`.

Every entry sums to zero, and an entry's reference makes it idempotent.

## Tests

```shell
./mvnw test                     # needs Docker: Dev Services starts PostgreSQL
./mvnw test -Dtest=P2pFlowTest
```

`src/test/java/cv/sitech/testhub/FakeHubResource.java` is an in-process hub. It acts as the ALS
oracle, routes quotes and transfers, validates fulfilments, and stubs the admin and settlement
reads. With it, the tests run the full payer→payee flow between two hosted DFSPs.

## Not covered yet

- JWS signing and validation, and mTLS towards the hub. `/fspiop/**` is meant to be protected at
  network level.
- An OAuth client for a hub behind an API gateway. Only a static `simulator.hub.bearer-token` is
  supported.
- Hub-side settlement actions (closing windows, creating settlements). The hub runs them; the
  simulator only reconciles.
- FSPIOP 2.0 / ISO 20022, and transaction requests (payee-initiated flows).
