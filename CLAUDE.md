# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Quarkus 3 (Java 21, Maven) **Mojaloop DFSP simulator**. One deployment hosts several DFSPs and talks to a real Mojaloop hub over FSPIOP v1.1. It acts as the **payer** for the DFSP of the logged-in Keycloak user (`fsp_id` claim), and as the **payee** for every hosted DFSP the hub routes to. Each DFSP has an inner double-entry ledger, and hub positions and settlements are reconciled against it. README.md covers setup, env vars and a curl walkthrough.

Persistence is PostgreSQL via Hibernate ORM with Panache. The schema is managed by Flyway, and MapStruct maps between domain objects and JPA entities.

## Commands

```shell
./mvnw quarkus:dev                         # dev mode on port 8090 (local Keycloak owns 8080); reads DB_PASSWORD from .env
./mvnw test                                # all tests; needs Docker (Dev Services PostgreSQL)
./mvnw test -Dtest=P2pFlowTest             # single class (append #method for one method)
./mvnw package                             # builds target/quarkus-app/quarkus-run.jar
```

No linter/formatter is configured. `./mvnw -o` fails when a newly added extension isn't in `~/.m2` yet; run online once.

## Database

- Dev and prod use `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` (default `jdbc:postgresql://localhost:5432/dfsp`). Tests use a Dev Services `postgres:16-alpine` container, so they never touch the dev DB.
- `quarkus.flyway.migrate-at-start=true` with `schema-management.strategy=validate`: **every entity change needs a new `V<n>__*.sql` migration**, otherwise startup fails. Never edit an applied migration.
- Ids use Hibernate's default `@GeneratedValue`: one `<table>_seq` per table, increment 50. New tables need a matching sequence. `dfsp` is the exception, with PK `fsp_id varchar(32)`.
- Hibernate uses no naming strategy, so every multi-word column needs an explicit `@Column(name = "snake_case")`. Enums are stored as `String` in entities, and MapStruct converts them.

## Architecture (hexagonal / ports & adapters, all under `cv.sitech`)

- `domain/`: POJOs with no framework annotations.
  - `ledger/`: `JournalEntry` and `Posting`.
  - `transfer/`: `Transfer`, `TransferState`, `PartyInfo` and `FspiopError`.
  - `settlement/`: the reconciliation types.
  - `exception/`: `BusinessException` carries an FSPIOP error code.
- `application/port/in`: use-case interfaces and command records. `application/port/out`: repositories plus the hub gateways `FspiopGateway`, `HubAdminGateway` and `HubSettlementGateway`.
- `application/service/`:
  - `LedgerService`: reserve, commit, abort, post and reverse, idempotent by reference.
  - `PayerService`: the outbound use case plus the payer callbacks.
  - `PayeeService`: the payee side.
  - `IlpService`: ILP packet, fulfilment and condition.
  - `SettlementService`: reconciliation.
  - `CallbackRegistry`: lets synchronous API calls wait for an async callback.
- `adapter/in/rest`: the Keycloak-protected `/api/**` resources. `CurrentDfsp` reads `fsp_id` from the JWT, and every DFSP-scoped use-case method takes that `fspId`. Other DFSPs' data is reported as 404.
- `adapter/in/fspiop/FspiopResource`: the hub-facing `/fspiop/{fspId}/**`. It acknowledges straight away (202 for requests, 200 for callbacks) and hands the work to `FspiopDispatcher` (virtual threads).
- `adapter/out/hub`: `HubHttp` (a `java.net.http` client that adds the FSPIOP headers), `FspiopHubClient`, `CentralLedgerAdminClient` and `CentralSettlementClient`. `adapter/fspiop/Fspiop` holds the wire records, conversions and header names.
- `adapter/out/persistence`: `PanacheAdapter<D, E, ID>` is the generic base, with `list`/`first` query helpers. Each port has one `XPersistenceAdapter`.
- `adapter/in/scheduler/SimulatorJobs`: transfer expiry and periodic reconciliation.
- `config/SimulatorConfig`: `@ConfigMapping(prefix = "simulator")`. Every `simulator.*` key must be declared there, or startup fails.

### How the flows work

- **One `transfers` row per side.** `direction` is OUTBOUND (payer) or INBOUND (payee). An INBOUND row is created by the quote request and matched to the later `POST /transfers` by `ilp_condition`. `transfer_id` is unique per direction, so one transfer between two hosted DFSPs has two rows.
- **Store state before calling the hub.** The hub's callback can arrive before the outgoing call returns, so services commit each state change first using `QuarkusTransaction.joiningExisting()`, and only then call the gateway. Never call the hub inside a transaction that the callback depends on.
- **ILP (v1.1 style).**
  - The packet is an OER ILP Payment packet: the amount in minor units, the address `g.{fsp}.{type}.{id}`, and a base64url transaction object.
  - The fulfilment is `HMAC-SHA256(simulator.ilp-secret, ilpPacket)` and the condition is `sha256(fulfilment)`, so the payee needs no per-transfer secret.
  - FSPIOP amounts go through `IlpService.amountString`, which strips trailing zeros (the spec forbids `10.00`).
- **Ledger sign convention.**
  - CUSTOMER balance is what the DFSP owes the customer. POSITION is positive when the DFSP owes the hub. SETTLEMENT holds the DFSP's own funds.
  - A RESERVED entry holds negative legs in `reserved_balance` and positive legs in `pending_credit`. The NDC check uses `position.balance + pendingCredit`.
  - Balances change only through `LedgerService`; never set them directly.
- **Payee modes.** With `simulator.payee.transfer-state=COMMITTED`, the payee credits as soon as it has fulfilled. With `RESERVED`, it waits for the hub's `PATCH /transfers/{id}`. A hub error after commit is undone with `LedgerService.reverse`.

### Conventions and gotchas

- **MapStruct naming.** Domain and entity property names must match. MapStruct maps the domain constructor's parameters by name and sets the rest through setters. The processor runs with `-Amapstruct.defaultComponentModel=jakarta-cdi` (see `pom.xml`), so don't set `componentModel` per mapper. Check build output for "Unmapped target property".
- **Persistence adapter constructors.** An `XPersistenceAdapter` needs a `protected` no-arg constructor (for ArC's proxy) **and** an `@Inject` constructor. Without `@Inject`, CDI uses the no-arg constructor and every field is `null`.
- **Read balances inside a transaction.** Reads outside a transaction use the request-scoped Hibernate session and can return stale entities. That's why `AccountService` reads and the payer/payee lookups run inside transactions. Keep it that way for anything that reads balances or flow state.
- **Media types on `/fspiop`.** `FspiopResource` uses `@Produces(WILDCARD)` and reads bodies as `String`, because the hub sends `application/vnd.interoperability.*+json` content types and Accept headers. `@Produces(APPLICATION_JSON)` there would return 406 to the hub.
- **`HubHttp` wire details.** The client is pinned to HTTP/1.1: with the default HTTP/2 it sends an h2c upgrade, and the ALS answers 200 but never forwards the callback. The `Date` header must be IMF-fixdate with a two-digit day (`Fri, 02 Oct 2026 ...`), which `RFC_1123_DATE_TIME` doesn't produce. `FakeHubResource` checks neither, so header bugs only show up against a real hub.
- **Callback URLs.** The hub endpoints registered on onboarding are `{simulator.public-base-url}/fspiop/{fspId}/...`; see `CentralLedgerAdminClient.endpoints`. They are stored in the hub at onboarding, so changing `SIMULATOR_PUBLIC_URL` means onboarding again (the setup script does it).

## Tests

- `testhub/FakeHubResource` (test sources only) is an in-process hub at `/test-hub/**`. It plays ALS oracle, quoting, ml-api-adapter (validates fulfilments, tracks positions, sends `PATCH` in RESERVED mode), central-ledger admin and central-settlement. The `%test` profile points `simulator.hub.*` at it, and it routes FSPIOP messages back to `/fspiop/{dest}`. It's static state shared across tests.
- `testhub/Fixtures` builds DFSPs (`testbank`, `testwallet`), customers, deposits and parties through the use cases. Use `Fixtures.msisdn()` for unique identifiers, because the DB persists across test classes in a run.
- Auth in tests uses `@TestSecurity` + `@OidcSecurity(claims = @Claim(key = "fsp_id", ...))`, with `%test.quarkus.oidc.tenant-enabled=false`.
- `@TestTransaction` doesn't wrap `@BeforeEach`, so `LedgerServiceTest` calls its setup from inside each test. Async flow tests don't use `@TestTransaction`; they wait with Awaitility.
- To vary config, use a `QuarkusTestProfile` (see `PayeeReservedModeTest`). It restarts the app.
- Keycloak realm for dev: `src/main/resources/keycloak/dfsp-realm.json` (users `sim-admin`, `cvbank-user`, `cvwallet-user`, with passwords in README).
