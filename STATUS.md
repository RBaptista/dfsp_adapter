# DFSP simulator — status

The goal is a Mojaloop **FSPIOP v1.1** DFSP simulator. It hosts several DFSPs in one app and
connects to a real Mojaloop hub. Users log in through Keycloak, and each user belongs to one DFSP
(the `fsp_id` claim). The app has an inner double-entry ledger and runs P2P discovery, quoting,
transfers and settlement reconciliation. It acts as the payer for the logged-in DFSP and as the
payee for any hosted DFSP the hub routes to.

Last updated: 2026-10-01. Test suite: **29 tests passing** (`./mvnw clean test`).

---

## Done

### Foundations
- [x] Fixed the original skeleton:
  - MapStruct's processor wasn't in the build, so no mapper code was generated.
  - Services weren't CDI beans.
  - Nothing implemented the repository ports.
  - `PanacheAdapter` only supported `Long` ids.
  - `DfspEntity` had no getters or setters.
  - Some names didn't match between domain and entity classes.
  - `PartyService` was empty.
  - The template tests were broken.
- [x] Money is `BigDecimal` / `numeric(18,4)` everywhere (V3 migration).
- [x] Profile-based datasource. Dev and prod use env vars, with `DB_PASSWORD` in a git-ignored
  `.env`. Tests use a Dev Services PostgreSQL container.
- [x] The app runs on port 8090, because the local Keycloak uses 8080.
- [x] Config lives in a `SimulatorConfig` mapping (`simulator.*`).
- [x] Errors map to HTTP statuses: 404 not found, 400 bad input, 409 business rule, 502 hub
  unreachable, 504 callback timeout.
- [x] `IdTypeEnum` uses the FSPIOP party id types. `NIF` was removed; use `PERSONAL_ID` instead.

### Keycloak
- [x] `quarkus-oidc` protects `/api/**` with bearer tokens. `/fspiop/**` is left open for the hub.
- [x] `CurrentDfsp` takes the DFSP from the token's `fsp_id` claim. A request for another DFSP's
  data gets a 404.
- [x] The `admin` role is required for DFSP administration and on-demand reconciliation.
- [x] A realm export (`src/main/resources/keycloak/dfsp-realm.json`) with the client, the
  `fsp_id` mapper, the roles and 3 demo users. I verified it on a throwaway Keycloak 26: the token
  carries `fsp_id` and the realm roles.

### DFSP admin and hub onboarding
- [x] DFSP CRUD. Creating a DFSP also opens its POSITION and SETTLEMENT ledger accounts and books
  the initial position.
- [x] Onboarding in central-ledger: participant and currency, NDC limit and initial position, the
  11 `FSPIOP_CALLBACK_URL_*` endpoints pointing at `{public-base-url}/fspiop/{fspId}`, and initial
  funds as `recordFundsIn`. Repeating it is safe, because "already exists" errors are ignored.
- [x] Customers, accounts, deposits and account statements, all scoped to the logged-in DFSP.
- [x] Parties are registered in and removed from the ALS. If registration fails, the party is
  removed again.

### Inner ledger
- [x] Double-entry journal: every entry's postings sum to zero.
- [x] Operations: reserve, commit, abort, post and reverse. Each entry has a unique reference, so
  repeating an operation has no extra effect.
- [x] Reserving checks the payer's available funds and the DFSP's net debit cap (position plus
  pending amounts).
- [x] Accounts are locked in id order, so concurrent entries can't deadlock.

### FSPIOP flows
- [x] Outbound requests carry the right FSPIOP headers (versioned content type, Accept, Date,
  Source, Destination) and an optional bearer token.
- [x] Inbound requests are acknowledged straight away and processed in the background. Headers
  and destination are validated, and errors use FSPIOP error bodies.
- [x] **Discovery:** for the payer, a GET to the hub followed by its `PUT` callback, plus a
  synchronous lookup endpoint. For the payee, the app answers with the party name, or error 3204
  if it doesn't know the party.
- [x] **Quoting:** the payer sends a SEND quote. The payee validates it, applies the fee (SEND and
  RECEIVE), and builds the ILP Payment packet and condition.
- [x] **Transfers:**
  - The payer reserves the funds, sends the transfer, checks the fulfilment and commits.
  - The payee matches the transfer to its quote by condition, checks the packet, amount and
    expiry, and fulfils it.
  - In `RESERVED` mode the payee waits for the hub's PATCH. If the hub fails a transfer after the
    payee committed it, the credit is reversed.
- [x] The payer can run a transfer step by step (accept the party, then the quote) or with
  `autoAccept`.
- [x] Pending quotes and transfers expire. Reserved ones get a grace period, so the hub can time
  them out first.

### Settlement reconciliation
- [x] Hub positions are compared with the ledger's POSITION balances.
- [x] Each hub settlement's net amount is compared with the net of committed transfers in its
  windows.
- [x] When the hub marks a settlement SETTLED, it's booked once in the ledger
  (`POSITION −net / SETTLEMENT +net`).
- [x] Reconciliation runs on a schedule (every 5 minutes) and on demand.

### Tests and docs
- [x] `FakeHubResource`, an in-process hub, plus `Fixtures` for test data. Together they run the
  full payer → payee flow between two hosted DFSPs.
- [x] Covered:
  - auto and step-by-step transfers, a rejected quote, an unknown payee, insufficient funds;
  - isolation between DFSPs, the admin role and authentication;
  - onboarding calls, invalid hub requests;
  - reconciliation, including not booking a settlement twice;
  - payee `RESERVED` mode with a fee;
  - ledger invariants, ILP encoding.
- [x] Smoke-tested the packaged app with real Keycloak tokens and a throwaway Postgres.
- [x] README (setup, env vars, curl walkthrough, ledger model) and CLAUDE.md (architecture and
  pitfalls). AUTH.md (tokens) and HUB_SETUP.md (hub onboarding).
- [x] `scripts/setup-simulator.sh`: one command that runs the realm import, hub provisioning, DFSP
  onboarding and demo data, plus discovery and transfer checks. Safe to re-run: it re-registers the
  callback endpoints and the demo parties in the ALS, and reads `SIMULATOR_PUBLIC_URL` from `.env`.
- [x] Postman collection with every `/api` endpoint (`test quarkus hexa.postman_collection.json`).

### Real hub (ml-core-test-harness)
- [x] Onboarding, discovery and a COMMITTED 100 CVE transfer cvbank → cvwallet, run with the setup
  script.
- [x] Fixes it needed:
  - the `Date` header as IMF-fixdate (Java's `RFC_1123_DATE_TIME` doesn't zero-pad the day);
  - `HubHttp` pinned to HTTP/1.1, because the ALS accepted h2c-upgrade callbacks but never
    forwarded them;
  - dev mode bound to `0.0.0.0`;
  - `PUT /api/parties/{id}/registration` to recover after a hub reset.

---

## Left to do

### Validate against a real hub (next step)
- [ ] Run the rest of the README walkthrough on **ml-core-test-harness**: manual accept, the RESERVED
  payee mode, error paths. Discovery and an auto-accepted transfer already work.
- [ ] Check what central-ledger and central-settlement actually do. Most likely to need changes:
  - the onboarding call sequence and how errors for existing resources are worded;
  - whether a `recordFundsIn` call has to be followed by a commit;
  - the JSON shape of settlement responses (participant account ids, window dates, the sign of
    `netSettlementAmount`);
  - the response shape of `GET /participants/{name}/positions`.
- [ ] Check that the hub accepts the ILP packet encoding and the callback URL templates
  (`FSPIOP_CALLBACK_URL_QUOTES` as the base URL).
- [ ] Publish quoting-service, ml-api-adapter and central-settlement ports in the harness (now reached
  through container IPs in `.env`), or run the app in Docker on `mojaloop-net`.
- [ ] Add hub-side callbacks this app doesn't register yet, if the harness needs them (e.g. the
  `*_SUB_ID_*` party endpoints, `NET_DEBIT_CAP_*` emails).

### Security
- [ ] JWS signing and validation of FSPIOP messages (`FSPIOP-Signature`, `FSPIOP-URI`,
  `FSPIOP-HTTP-Method`).
- [ ] mTLS towards and from the hub. For now `/fspiop/**` relies on network-level protection.
- [ ] An OAuth client (`quarkus-oidc-client`) for hubs behind an API gateway. Only a static
  `simulator.hub.bearer-token` is supported.
- [ ] A real `ILP_SECRET` (and the DB password) outside dev.

### Functional gaps
- [ ] Withdrawals or cash-out from customer accounts. Only deposits exist.
- [ ] Updating or moving a party between accounts. Today it has to be deleted and recreated.
- [ ] Payer-side fees. Only the payee fee exists.
- [ ] Transaction requests (payee-initiated / request-to-pay), `PUT /transactions`, bulk transfers.
- [ ] Driving hub settlement (closing windows, creating settlements, recording deposits and
  withdrawals). The hub does these today; the simulator only reconciles.
- [ ] Linking hub settlement windows to transfers by id. Today they're matched by time range,
  which can misreport transfers near a window boundary.
- [ ] FSPIOP 2.0 / ISO 20022.
- [ ] Currencies besides CVE and USD (per-currency scale in `CurrencyEnum`).

### Robustness and operations
- [ ] Row locking on `transfers`, so a callback and the expiry job can't update the same transfer
  at the same time.
- [ ] Retry or backoff for outbound hub calls; today a failed call aborts the flow.
- [ ] Multi-instance deployment: `CallbackRegistry` keeps its waits in memory, so the synchronous
  lookup assumes a single instance. Kafka isn't needed unless you scale out or run load tests.
- [ ] Health checks (`quarkus-smallrye-health`), metrics and structured logs with
  transfer/quote ids.
- [ ] Bean Validation on request DTOs. Validation is done by hand in the services today.
- [ ] Pagination on the list endpoints.
- [ ] A docker-compose setup with the app, Postgres, Keycloak and a hub (the setup script already
  covers configuring them).

### Tests
- [ ] Expiry job (pending and reserved transfers with a short `transfer-expiry`).
- [ ] Hub error paths: a transfer error after the payee committed (reversal), an invalid
  fulfilment, a duplicate prepare or quote.
- [ ] An end-to-end test against ml-core-test-harness (e.g. Testcontainers / docker-compose in CI).
- [ ] A UI for running the walkthrough (a Postman collection exists).
- [ ] Make `FakeHubResource` validate FSPIOP headers (`Date` format, Accept on requests) like the
  real hub, so header bugs fail in tests.
