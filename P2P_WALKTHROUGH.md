# P2P transfer, step by step

This guide makes one person-to-person transfer through the Mojaloop hub, one API call at a time.
Carlos, a customer of **cvbank**, sends 250 CVE to Ana, a customer of **cvwallet**. Both DFSPs are
hosted by this simulator, so it acts as the payer for cvbank and as the payee for cvwallet.

The responses shown are from a real run against ml-core-test-harness. Ids, timestamps and
balances will differ on yours.

```
 phase 0          phase 1-2             phase 3        phase 4        phase 5         phase 6
 hub + DFSPs ─▶ customers, accounts ─▶ discovery ─▶   quoting   ─▶   transfer   ─▶   verify
 (once)          deposit, parties       GET /parties   POST /quotes   POST /transfers
```

The same requests are in the Postman collection (`test quarkus hexa.postman_collection.json`); see
[AUTH.md](AUTH.md#postman--bruno).

---

## Setup for the shell

```shell
API=http://localhost:8090/api
J='Content-Type: application/json'
tok() { curl -s http://localhost:8080/realms/dfsp/protocol/openid-connect/token \
  -d grant_type=password -d client_id=dfsp-simulator -d username=$1 -d password=$2 | jq -r .access_token; }

ADMIN=$(tok sim-admin admin)            # role admin: manages DFSPs
BANK=$(tok cvbank-user cvbank)          # fsp_id=cvbank: the payer DFSP
WALLET=$(tok cvwallet-user cvwallet)    # fsp_id=cvwallet: the payee DFSP

PAYER_MSISDN=238991032                  # any identifier not registered yet
PAYEE_MSISDN=238981032
```

The token decides which DFSP you act as. Every `/api` call works on the logged-in user's DFSP
(the `fsp_id` claim), and other DFSPs' data comes back as 404.

Tokens expire after a few minutes. If a call returns 401, run `tok` again.

---

## Phase 0: hub and DFSPs (once)

A DFSP must exist in the simulator **and** be a participant in the hub before it can send or
receive money. In the hub, that means:
- the participant, with a position account in the currency;
- a **net debit cap** (NDC): how far its position may go into debt to the hub;
- the 11 **callback endpoints**, so the hub knows where to send FSPIOP messages for it;
- activation;
- **funds-in** to its hub settlement account.

`./scripts/setup-simulator.sh` does all of this for cvbank and cvwallet, and is safe to re-run. By
hand, for one DFSP:

```shell
# 0.1 create it in the simulator: opens its POSITION and SETTLEMENT ledger accounts
curl -s -H "Authorization: Bearer $ADMIN" -H "$J" $API/admin/dfsps -d '{
  "fspId": "cvbank", "displayName": "CV Bank", "currency": "CVE",
  "netDebitCap": 1000000, "initialPosition": 0, "initialFunds": 1000000 }'

# 0.2 onboard it in the hub: participant, NDC + initial position, 11 endpoints, funds-in
curl -s -X POST -H "Authorization: Bearer $ADMIN" $API/admin/dfsps/cvbank/onboard
```

The hub also needs its own accounts and a settlement model for the currency, and an MSISDN oracle
in the ALS. [HUB_SETUP.md](HUB_SETUP.md) covers these, together with the networking the hub needs
to reach the app.

Check it as the DFSP:

```shell
curl -s -H "Authorization: Bearer $BANK" $API/dfsp
```
```json
{"dfsp":{"fspId":"cvbank","currency":"CVE","netDebitCap":1000000.0000, ...},
 "position":{"id":101,"type":"POSITION","balance":300.0000, ...},
 "settlement":{"id":102,"type":"SETTLEMENT","balance":-30000.0000, ...}}
```

---

## Phase 1: the payee's customer (as cvwallet)

A customer can receive money once they have three things: a **customer**, a **CUSTOMER account**,
and a **party**, which is an identifier registered in the hub's ALS that points to that account.

```shell
# 1.1 customer
curl -s -H "Authorization: Bearer $WALLET" -H "$J" $API/customers \
  -d "{\"name\":\"Ana Fortes\",\"contact\":{\"phone\":\"$PAYEE_MSISDN\"}}"
```
```json
{"id":251,"name":"Ana Fortes","contact":{"phone":"238981032","telephone":null,"email":null},"address":null,"nif":null}
```

```shell
# 1.2 account (CUSTOMER type, in the DFSP's currency)
curl -s -H "Authorization: Bearer $WALLET" -H "$J" $API/accounts -d '{"customerId":251}'
```
```json
{"id":251,"type":"CUSTOMER","customerId":251,"balance":0,"reservedBalance":0,"pendingCredit":0,"availableBalance":0,"currency":"CVE"}
```

```shell
# 1.3 party: maps the MSISDN to the account and registers it in the ALS
curl -s -H "Authorization: Bearer $WALLET" -H "$J" $API/parties \
  -d "{\"idType\":\"MSISDN\",\"idValue\":\"$PAYEE_MSISDN\",\"accountId\":251}"
```
```json
{"id":251,"idType":"MSISDN","idValue":"238981032","accountId":251}
```

Behind 1.3, the simulator calls `POST {ALS}/participants/MSISDN/238981032` with
`{"fspId":"cvwallet","currency":"CVE"}`. From then on, the hub knows that this number belongs to
cvwallet. If the ALS rejects the call, the API returns 502 and the party isn't saved.

## Phase 2: the payer's customer (as cvbank)

The same three steps, plus a **deposit**, because the payer needs money to send.

```shell
# 2.1 customer
curl -s -H "Authorization: Bearer $BANK" -H "$J" $API/customers \
  -d "{\"name\":\"Carlos Tavares\",\"contact\":{\"phone\":\"$PAYER_MSISDN\"}}"        # -> id 252

# 2.2 account
curl -s -H "Authorization: Bearer $BANK" -H "$J" $API/accounts -d '{"customerId":252}'   # -> id 252

# 2.3 deposit: cash-in at the DFSP (no hub involved)
curl -s -H "Authorization: Bearer $BANK" -H "$J" $API/accounts/252/deposits -d '{"amount":1000}'
```
```json
{"id":252,"type":"CUSTOMER","customerId":252,"balance":1000.0000,"reservedBalance":0.0000,"pendingCredit":0.0000,"availableBalance":1000.0000,"currency":"CVE"}
```

```shell
# 2.4 party: optional for sending, but it lets others pay Carlos and gives the transfer a "from"
curl -s -H "Authorization: Bearer $BANK" -H "$J" $API/parties \
  -d "{\"idType\":\"MSISDN\",\"idValue\":\"$PAYER_MSISDN\",\"accountId\":252}"
```

The deposit is a ledger entry inside cvbank: +1000 on Carlos's account and −1000 on cvbank's
SETTLEMENT account (the DFSP's own funds). It doesn't touch the hub position.

**Mistakes the API rejects:**
- `accountId` belonging to another DFSP: 404.
- An identifier already registered: `3003 ... already exists`.
- A party on a non-CUSTOMER account: 400.

---

## The transfer

`POST /api/transfers` starts the whole Mojaloop flow. With `"autoAccept": true`, it runs discovery →
quote → transfer without stopping. With `false`, it pauses twice so you can see each phase, the
way a real app would show the payee's name and the fees to the customer before they confirm.

```
cvbank (payer)                     hub                       cvwallet (payee)
     │ GET /parties/MSISDN/{id}     │                               │      Phase 3
     │─────────────────────────────▶│── oracle: cvwallet ──────────▶│      discovery
     │◀──────── PUT /parties ───────│◀──────── PUT /parties ────────│
     │      (you: accept-party)     │                               │
     │ POST /quotes                 │                               │      Phase 4
     │─────────────────────────────▶│──────────────────────────────▶│      quoting
     │◀──────── PUT /quotes ────────│◀──── PUT /quotes (ILP) ───────│
     │      (you: accept-quote)     │                               │
     │ POST /transfers (reserve)    │ position check vs NDC         │      Phase 5
     │─────────────────────────────▶│──────────────────────────────▶│      transfer
     │◀────── PUT /transfers ───────│◀── PUT /transfers (fulfil) ───│
     │      COMMITTED               │ positions moved               │ COMMITTED
```

The transfer has **60 seconds** to complete (`simulator.transfer-expiry`), counted from when it
starts. In manual mode, both accept calls must come in within that time, or the expiry job marks
the transfer `EXPIRED`.

### Phase 3: discovery

```shell
# 3.1 start the transfer (autoAccept false: stop after each phase)
curl -s -H "Authorization: Bearer $BANK" -H "$J" $API/transfers -d "{
  \"from\": {\"idType\":\"MSISDN\",\"idValue\":\"$PAYER_MSISDN\"},
  \"to\":   {\"idType\":\"MSISDN\",\"idValue\":\"$PAYEE_MSISDN\"},
  \"amount\": 250, \"currency\": \"CVE\", \"note\": \"jantar\", \"autoAccept\": false }"
```
```json
{"transferId":"b045bc32-1edd-46a9-944b-a086b853dcc9","direction":"OUTBOUND","state":"WAITING_FOR_PARTY_INFO",
 "payerFspId":"cvbank","payer":{"idType":"MSISDN","idValue":"238991032"},"payee":{"idType":"MSISDN","idValue":"238981032"},
 "amount":250.0000,"currency":"CVE","expiration":"2026-10-02T11:33:50.598508Z","note":"jantar", ...}
```

The API answers **202** straight away, and the rest happens asynchronously:
- cvbank sends `GET /parties/MSISDN/238981032` to the ALS.
- The oracle says cvwallet holds that number, so the hub forwards the request to cvwallet.
- cvwallet answers with `PUT /parties` (the name).
- The hub passes that back to cvbank.

Poll the transfer:

```shell
T=b045bc32-1edd-46a9-944b-a086b853dcc9
curl -s -H "Authorization: Bearer $BANK" $API/transfers/$T
```
```json
{"state":"WAITING_FOR_PARTY_ACCEPTANCE","payeeFspId":"cvwallet","payeeName":"Ana Fortes", ...}
```

The payer now knows **who** it's paying (Ana Fortes) and **which DFSP** holds the account
(cvwallet). Confirm it:

```shell
# 3.2 accept the party (false aborts the transfer)
curl -s -X PUT -H "Authorization: Bearer $BANK" -H "$J" $API/transfers/$T/accept-party -d '{"accept":true}'
```
```json
{"state":"WAITING_FOR_QUOTE","quoteId":"9d319f7d-8ad0-487d-80f4-6a42560b4644", ...}
```

To test discovery without starting a transfer, use
`GET $API/parties/lookup/MSISDN/$PAYEE_MSISDN`. It waits for the callback and returns
`{"idType":"MSISDN","idValue":"238981032","fspId":"cvwallet","name":"Ana Fortes"}`.

### Phase 4: quoting

Accepting the party sends `POST /quotes` to cvwallet through the hub (a SEND quote for 250 CVE).
The payee:
- checks that the account exists and can receive;
- applies its fee (`simulator.payee.fee-percent`, 0 by default);
- builds the **ILP packet** and the **condition** `sha256(fulfilment)`;
- stores an INBOUND transfer row in state `QUOTED`;
- answers with `PUT /quotes`.

```shell
curl -s -H "Authorization: Bearer $BANK" $API/transfers/$T
```
```json
{"state":"WAITING_FOR_QUOTE_ACCEPTANCE","transferAmount":250.0000,"payeeReceiveAmount":250.0000,
 "payeeFee":0.0000,"quoteId":"9d319f7d-8ad0-487d-80f4-6a42560b4644","expiration":"2026-10-02T11:33:52.775Z", ...}
```

- `transferAmount` is what leaves the payer.
- `payeeReceiveAmount` is what Ana gets.
- `payeeFee` is the difference.

This is the moment to show the customer the terms. Accept them:

```shell
# 4.1 accept the quote (false aborts the transfer)
curl -s -X PUT -H "Authorization: Bearer $BANK" -H "$J" $API/transfers/$T/accept-quote -d '{"accept":true}'
```
```json
{"state":"RESERVED","transferAmount":250.0000, ...}
```

### Phase 5: transfer

Accepting the quote does two things.
1. **cvbank reserves the funds in its own ledger.** −250 on Carlos's account and +250 on cvbank's
   POSITION, held as reserved or pending until the hub answers. The state is now `RESERVED`.
2. **It sends `POST /transfers`** (the prepare) to ml-api-adapter, carrying the ILP packet and the
   condition. Then:
   - the hub checks that cvbank's position plus 250 stays within its NDC, and reserves it;
   - it forwards the prepare to cvwallet;
   - cvwallet reserves the credit for Ana, computes the **fulfilment**
     (`HMAC-SHA256(ilp-secret, ilpPacket)`) and answers `PUT /transfers` with it;
   - the hub checks `sha256(fulfilment) == condition`, commits both positions, and notifies cvbank.

```shell
curl -s -H "Authorization: Bearer $BANK" $API/transfers/$T
```
```json
{"state":"COMMITTED","transferAmount":250.0000,"payeeReceiveAmount":250.0000,
 "completedAt":"2026-10-02T11:32:55.010Z", ...}
```

When the fulfilment arrives, cvbank commits its reserved entry. On the payee side, with
`simulator.payee.transfer-state=COMMITTED` (the default), cvwallet has already credited Ana when it
fulfilled. With `RESERVED`, it waits for the hub's `PATCH /transfers/{id}` before crediting her.

---

## Phase 6: verify

```shell
# the payee's view of the same transfer (its own INBOUND row)
curl -s -H "Authorization: Bearer $WALLET" $API/transfers/incoming
```
```json
[{"transferId":"b045bc32-...","direction":"INBOUND","state":"COMMITTED","payerFspId":"cvbank","payeeName":"Ana Fortes",
  "payeeReceiveAmount":250.0000,"completedAt":"2026-10-02T11:32:55.026620Z", ...}]
```

```shell
# customer balances
curl -s -H "Authorization: Bearer $BANK"   $API/accounts/252     # Carlos: 1000 -> 750
curl -s -H "Authorization: Bearer $WALLET" $API/accounts/251     # Ana:       0 -> 250

# Carlos's ledger entries
curl -s -H "Authorization: Bearer $BANK" $API/accounts/252/statement
```
```json
[{"reference":"DEPOSIT:e7bf35f8-...","type":"DEPOSIT","status":"COMMITTED",
  "postings":[{"accountId":252,"amount":1000.0000},{"accountId":102,"amount":-1000.0000}]},
 {"reference":"OUT:b045bc32-...","type":"TRANSFER_OUT","status":"COMMITTED",
  "postings":[{"accountId":252,"amount":-250.0000},{"accountId":101,"amount":250.0000}]}]
```

Every entry is double-entry (its postings sum to zero). The transfer moved 250 from Carlos to
cvbank's POSITION account, which now records that cvbank owes the hub 250 more.

**Positions: the simulator and the hub should agree**

```shell
curl -s -H "Authorization: Bearer $BANK"   $API/dfsp | jq .position.balance   # 300 -> 550
curl -s -H "Authorization: Bearer $WALLET" $API/dfsp | jq .position.balance   # -300 -> -550
curl -s http://localhost:3001/participants/cvbank/positions     # [{"currency":"CVE","value":550,...}]
curl -s http://localhost:3001/participants/cvwallet/positions   # [{"currency":"CVE","value":-550,...}]
```

A positive position means the DFSP owes the hub (it has sent more than it received); a negative one
means the hub owes it. These are netted when the hub settles the window. The reconciliation job
(`GET /api/settlements`, `POST /api/settlements/reconcile` as admin) compares both sides every 5
minutes.

---

## States

| State | Side | Meaning | Next step |
| --- | --- | --- | --- |
| `WAITING_FOR_PARTY_INFO` | payer | `GET /parties` sent | wait for the callback |
| `WAITING_FOR_PARTY_ACCEPTANCE` | payer | payee name and DFSP known | `PUT .../accept-party` |
| `WAITING_FOR_QUOTE` | payer | `POST /quotes` sent | wait for the callback |
| `WAITING_FOR_QUOTE_ACCEPTANCE` | payer | amounts and fee known | `PUT .../accept-quote` |
| `QUOTED` | payee | quote answered, waiting for the prepare | none |
| `RESERVED` | both | funds held in the ledger, prepare in flight | wait for fulfil (or `PATCH`) |
| `COMMITTED` | both | final; balances moved | none |
| `ABORTED` | both | final; rejected, hub error or customer said no (`errorCode`/`errorDescription`) | none |
| `EXPIRED` | both | final; didn't finish within `transfer-expiry` | none |

## When it fails

| What you see | Why |
| --- | --- |
| `ABORTED`, `3204 Party not found` | the payee identifier isn't in the ALS; register it, or re-register after a hub reset (`PUT /api/parties/{id}/registration`) |
| `ABORTED`, `4001` | the payer's account has less than `transferAmount`; deposit first. The hub also answers `4001` when the payer's position would exceed its NDC |
| `ABORTED`, `4200 Net debit cap ... exceeded` | the simulator's own NDC check (POSITION + pending) stopped it before reaching the hub |
| `ABORTED`, `5101` | the customer declined the party or the quote (`accept: false`) |
| `ABORTED`, `3106` / `3208` / `3302` on the payee | the prepare didn't match the quote (ILP packet or condition), no quote was found, or the quote expired |
| `EXPIRED`, `3303` | the accept calls came too late, or the hub never answered |
| 504 on `parties/lookup` | no callback within 30s; the hub can't reach the app ([HUB_SETUP.md](HUB_SETUP.md#troubleshooting-discovery)) |

Sending money back the other way is the same sequence with the roles swapped: log in as
cvwallet-user and send from `$PAYEE_MSISDN` to `$PAYER_MSISDN`.
