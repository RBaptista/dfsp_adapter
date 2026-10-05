# Registering the simulated DFSPs in the Mojaloop hub

The hub only routes discovery, quotes and transfers between participants it knows. So every DFSP
this app hosts (e.g. `cvbank`, `cvwallet`) must exist as a **participant in the hub**. Each one is
registered as its own participant, and all of its callbacks point at this app:

```
{SIMULATOR_PUBLIC_URL}/fspiop/{fspId}/...
```

Most of the work is done by the app's onboarding endpoint (step 3). The hub itself must be
provisioned first (step 1) and must be able to reach the app (step 2).

> **Shortcut:** `./scripts/setup-simulator.sh` runs every step below, and is safe to re-run. It
> imports the Keycloak realm if it's missing, provisions the hub for the currency, onboards
> `cvbank` and `cvwallet`, creates demo customers, and finishes with a discovery check and a test
> transfer. Settings are environment variables documented at the top of the script, e.g.
> `CURRENCY=USD SIMULATOR_PUBLIC_URL=http://172.24.0.1:8090 PROVISION_HUB=false ./scripts/setup-simulator.sh`.
> When `SIMULATOR_PUBLIC_URL` isn't set, the script reads it from `.env`, so it registers the same
> callback URL the app uses.

Tokens for the `/api/**` calls below: see [AUTH.md](AUTH.md). `$ADMIN` is a `sim-admin` token and
`$BANK` is a `cvbank-user` token.

---

## 1. Hub prerequisites (outside this app)

The hub must be set up before any DFSP can be onboarded. ml-core-test-harness normally does this
with its hub-setup collections. Check that:

- [ ] The hub accounts `HUB_MULTILATERAL_SETTLEMENT` and `HUB_RECONCILIATION` exist **for your
  currency**.
- [ ] A settlement model covers that currency (e.g. `DEFERREDNET`).
- [ ] An ALS oracle is registered for the `MSISDN` party type. Without it, registering parties
  fails.
- [ ] The hub supports **CVE**. If it is only provisioned for USD, either add CVE hub accounts and
  a settlement model, or create your DFSPs in USD to start with.

## 2. Networking

**The hub must reach the app.** Every callback goes to `SIMULATOR_PUBLIC_URL`, which defaults to
`http://host.docker.internal:8090`.

On Linux (tested with ml-core-test-harness), three things are needed:

1. **An address the containers can resolve.** `host.docker.internal` doesn't resolve inside
   containers by default. Either use the gateway IP of the hub's Docker network:
   ```shell
   docker network inspect mojaloop-net -f '{{(index .IPAM.Config 0).Gateway}}'   # e.g. 172.24.0.1
   ```
   and put `SIMULATOR_PUBLIC_URL=http://172.24.0.1:8090` in `.env`, or add this to the hub services
   in the harness's docker-compose:
   ```yaml
   extra_hosts:
     - "host.docker.internal:host-gateway"
   ```
2. **The app listening on all interfaces.** Dev mode binds to localhost by default;
   `%dev.quarkus.http.host = 0.0.0.0` in `application.properties` takes care of that.
3. **The host firewall letting the Docker network in.** With `ufw`:
   ```shell
   sudo ufw allow from 172.24.0.0/16 to any port 8090 proto tcp
   ```

Check it from inside a hub container. Any HTTP answer, even a 404, means the hub can reach the app;
a timeout means it's still blocked:
```shell
docker exec account-lookup-service wget -qO- -T 5 http://172.24.0.1:8090/q/health
```

The callback URL is stored in the hub when a DFSP is onboarded. After changing
`SIMULATOR_PUBLIC_URL`, run the setup script again (or `POST /api/admin/dfsps/{fspId}/onboard`) to
re-register the endpoints.

**The app must reach the hub.** Override these if the harness uses other ports:

| Env var | Service | Default |
| --- | --- | --- |
| `HUB_ALS_URL` | account-lookup-service | `http://localhost:4002` |
| `HUB_QUOTES_URL` | quoting-service | `http://localhost:3002` |
| `HUB_TRANSFERS_URL` | ml-api-adapter | `http://localhost:3000` |
| `HUB_ADMIN_URL` | central-ledger admin | `http://localhost:3001` |
| `HUB_SETTLEMENT_URL` | central-settlement | `http://localhost:3007/v2` |

ml-core-test-harness publishes only the ALS (4001-4002) and central-ledger (3001) to localhost. The
port mappings of quoting-service, ml-api-adapter and central-settlement are commented out in its
`docker-compose.yml`. Either publish 3002, 3000 and 3007 there, or point the app at the container
IPs in `.env`:

```shell
ip() { docker inspect $1 -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'; }
echo "HUB_QUOTES_URL=http://$(ip quoting-service):3002"
echo "HUB_TRANSFERS_URL=http://$(ip ml-api-adapter):3000"
echo "HUB_SETTLEMENT_URL=http://$(ip central-settlement):3007/v2"
```

Container IPs change when the harness containers are recreated, so publishing the ports is the
lasting fix.

## 3. Create and onboard each DFSP

```shell
API=http://localhost:8090/api

# create it in the simulator: opens its POSITION and SETTLEMENT ledger accounts
curl -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' $API/admin/dfsps \
  -d '{"fspId":"cvbank","displayName":"CV Bank","currency":"CVE","netDebitCap":100000,"initialPosition":0,"initialFunds":100000}'

# register it in the hub
curl -X POST -H "Authorization: Bearer $ADMIN" $API/admin/dfsps/cvbank/onboard   # 204 on success
```

Repeat for every DFSP (e.g. `cvwallet`).

### What `onboard` does

It calls central-ledger (`HUB_ADMIN_URL`) in this order. The code is in
`src/main/java/cv/sitech/adapter/out/hub/CentralLedgerAdminClient.java`.

1. `POST /participants` with `{"name": "cvbank", "currency": "CVE"}`.
2. `POST /participants/cvbank/initialPositionAndLimits` with
   `{"currency": "CVE", "limit": {"type": "NET_DEBIT_CAP", "value": 100000}, "initialPosition": 0}`.
3. `POST /participants/cvbank/endpoints` with `{"type": ..., "value": ...}`, once for each of these
   11 types (`{base}` = `{SIMULATOR_PUBLIC_URL}/fspiop/cvbank`):

   | Type | Value |
   | --- | --- |
   | `FSPIOP_CALLBACK_URL_PARTIES_GET` | `{base}/parties/{{partyIdType}}/{{partyIdentifier}}` |
   | `FSPIOP_CALLBACK_URL_PARTIES_PUT` | `{base}/parties/{{partyIdType}}/{{partyIdentifier}}` |
   | `FSPIOP_CALLBACK_URL_PARTIES_PUT_ERROR` | `{base}/parties/{{partyIdType}}/{{partyIdentifier}}/error` |
   | `FSPIOP_CALLBACK_URL_PARTICIPANT_PUT` | `{base}/participants/{{partyIdType}}/{{partyIdentifier}}` |
   | `FSPIOP_CALLBACK_URL_PARTICIPANT_PUT_ERROR` | `{base}/participants/{{partyIdType}}/{{partyIdentifier}}/error` |
   | `FSPIOP_CALLBACK_URL_PARTICIPANT_BATCH_PUT` | `{base}/participants/{{requestId}}` |
   | `FSPIOP_CALLBACK_URL_PARTICIPANT_BATCH_PUT_ERROR` | `{base}/participants/{{requestId}}/error` |
   | `FSPIOP_CALLBACK_URL_QUOTES` | `{base}` |
   | `FSPIOP_CALLBACK_URL_TRANSFER_POST` | `{base}/transfers` |
   | `FSPIOP_CALLBACK_URL_TRANSFER_PUT` | `{base}/transfers/{{transferId}}` |
   | `FSPIOP_CALLBACK_URL_TRANSFER_ERROR` | `{base}/transfers/{{transferId}}/error` |

4. If `initialFunds` > 0: `GET /participants/cvbank/accounts` to find the CVE `SETTLEMENT`
   account, then `POST /participants/cvbank/accounts/{id}` with `action: recordFundsIn`. The hub
   won't accept transfers from a participant with no liquidity.

Onboarding is **safe to re-run**: central-ledger's "already exists" errors are ignored for the
participant and its limits.

### Doing it without the app

These are the same calls as raw curl, for example to onboard from a script or a Postman/TTK
collection:

```shell
CL=http://localhost:3001; FSP=cvbank; BASE=http://host.docker.internal:8090/fspiop/$FSP; J='Content-Type: application/json'

curl -H "$J" $CL/participants -d "{\"name\":\"$FSP\",\"currency\":\"CVE\"}"
curl -H "$J" $CL/participants/$FSP/initialPositionAndLimits \
  -d '{"currency":"CVE","limit":{"type":"NET_DEBIT_CAP","value":100000},"initialPosition":0}'
curl -H "$J" $CL/participants/$FSP/endpoints \
  -d "{\"type\":\"FSPIOP_CALLBACK_URL_TRANSFER_POST\",\"value\":\"$BASE/transfers\"}"
# ...one call per endpoint type in the table above

SETTLEMENT_ID=$(curl -s $CL/participants/$FSP/accounts | jq -r '.[] | select(.ledgerAccountType=="SETTLEMENT" and .currency=="CVE") | .id')
curl -H "$J" $CL/participants/$FSP/accounts/$SETTLEMENT_ID -d "{\"transferId\":\"$(uuidgen)\",
  \"externalReference\":\"manual\",\"action\":\"recordFundsIn\",\"reason\":\"initial funds\",
  \"amount\":{\"amount\":\"100000\",\"currency\":\"CVE\"}}"
```

The DFSP must still exist in the simulator (`POST /api/admin/dfsps`), because the simulator keeps
its own ledger for it.

## 4. Register the parties

Each customer identifier must be in the ALS so other DFSPs can discover it. Creating a party does
that: it calls `POST {HUB_ALS_URL}/participants/MSISDN/{id}`.

```shell
C=$(curl -s -H "Authorization: Bearer $BANK" -H 'Content-Type: application/json' $API/customers -d '{"name":"Maria Silva"}' | jq .id)
A=$(curl -s -H "Authorization: Bearer $BANK" -H 'Content-Type: application/json' $API/accounts -d "{\"customerId\":$C}" | jq .id)
curl -H "Authorization: Bearer $BANK" -H 'Content-Type: application/json' $API/accounts/$A/deposits -d '{"amount":500}'
curl -H "Authorization: Bearer $BANK" -H 'Content-Type: application/json' $API/parties \
  -d "{\"idType\":\"MSISDN\",\"idValue\":\"2389910000\",\"accountId\":$A}"
```

If the ALS call fails, the request returns 502 and the party isn't saved.

The ALS registration lives in the hub, so resetting the hub (or its oracle database) loses it while
the party stays in the simulator. Discovery then fails with `3204 Party not found`. Register it
again with:

```shell
curl -X PUT -H "Authorization: Bearer $BANK" $API/parties/{partyId}/registration
```

The setup script does this for its demo parties on every run.

## 5. Verify

In the hub (central-ledger):

```shell
curl http://localhost:3001/participants/cvbank             # exists, "isActive": 1
curl http://localhost:3001/participants/cvbank/endpoints   # the 11 callbacks → /fspiop/cvbank
curl http://localhost:3001/participants/cvbank/limits      # NET_DEBIT_CAP
curl http://localhost:3001/participants/cvbank/positions   # current position
```

Through the simulator, discovery as `cvbank-user` of a party registered by `cvwallet`:

```shell
curl -H "Authorization: Bearer $BANK" $API/parties/lookup/MSISDN/2389920000
# -> {"idType":"MSISDN","idValue":"2389920000","fspId":"cvwallet","name":"..."}
```

If this lookup works, the hub routed a request to the payee DFSP's callback URL and the answer came
back to the payer. With that working, run a transfer as described in the README walkthrough.

## Caveats

- Tested against ml-core-test-harness (central-ledger v19.4.4, account-lookup-service v17.9.1,
  ml-api-adapter v16.4.2, quoting-service v17.8.0): onboarding, discovery and a COMMITTED transfer
  work. Settlement reconciliation hasn't been checked against central-settlement yet. The setup
  script also activates each participant explicitly (`PUT /participants/{name}` with
  `{"isActive": true}`).
- The hub must be provisioned for the DFSP's currency before onboarding, otherwise step 1 or 2
  fails.
- Re-running onboarding answers `500 ... Participant Limit or Initial Position already set` for the
  NET_DEBIT_CAP step. The setup script treats that as "already done".

### Troubleshooting discovery

| Symptom | Likely cause |
| --- | --- |
| `3101 Malformed syntax - Invalid date header` | the `Date` header isn't an IMF-fixdate with a two-digit day (fixed in `HubHttp`) |
| 504 `No callback from the hub within 30s` | the hub can't reach `SIMULATOR_PUBLIC_URL` (step 2: address, bind address, firewall), or the endpoints still point at an old URL |
| `3204 ... No proxy found to start inter-scheme discovery flow` | the identifier isn't in the ALS oracle; re-register the party (step 4) |
| `ConnectException` for quotes, transfers or settlements | that hub service isn't published to localhost (step 2) |

The hub side is easiest to follow in `docker logs account-lookup-service`. Requests the simulator
receives from the hub show up in the app log as `/fspiop/...`.
