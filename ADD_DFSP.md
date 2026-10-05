# Adding a DFSP and its users

A new DFSP needs no code changes. It is three pieces of data:

1. a **Keycloak user** whose `fsp_id` attribute names the DFSP (this is who operates it);
2. a **DFSP in the simulator**, which opens its POSITION and SETTLEMENT ledger accounts;
3. a **participant in the hub**, created by the simulator's onboarding endpoint.

After that, customers, accounts and parties are created by the DFSP's own user.

The examples below add a DFSP `cvbank2` operated by `cvbank2-user`. Replace the names, currency
and amounts as needed. Tokens: see [AUTH.md](AUTH.md).

```shell
KC=http://localhost:8080; REALM=dfsp
API=http://localhost:8090/api; J='Content-Type: application/json'
tok() { curl -s -d grant_type=password -d client_id=dfsp-simulator -d username=$1 -d password=$2 \
  $KC/realms/$REALM/protocol/openid-connect/token | jq -r .access_token; }
ADMIN=$(tok sim-admin admin)
```

---

## 0. Prerequisites

- [ ] The hub is provisioned for the DFSP's currency: the hub accounts, a settlement model and an
  MSISDN oracle (see [HUB_SETUP.md](HUB_SETUP.md), step 1). This is already done for CVE if you ran
  `setup-simulator.sh`. A new currency needs it first; `CURRENCY=USD ./scripts/setup-simulator.sh`
  does it.
- [ ] The simulator is running and `SIMULATOR_PUBLIC_URL` is the address the hub uses to reach it.
  That URL is stored in the hub at onboarding.
- [ ] The `fspId` is at most 32 characters and unused, both in the simulator and in the hub.

## 1. Keycloak: the operator user

The user needs:

| Field | Value |
| --- | --- |
| Username | e.g. `cvbank2-user` |
| Attribute `fsp_id` | `cvbank2`, the DFSP's `fspId` exactly. It becomes the JWT claim that `CurrentDfsp` reads |
| Realm role | `dfsp-operator` |
| Credentials | a non-temporary password (the `password` grant fails while a temporary one is set) |

You can add several users with the same `fsp_id`. They all operate the same DFSP.

### Option A: admin console

1. Open `http://localhost:8080/admin`, log in as the Keycloak admin and switch to the `dfsp` realm.
2. **Users → Add user.** Set the username, email and name, then fill in the **DFSP** field. That
   field is the `fsp_id` attribute from the realm's user profile. Click **Create**.
3. **Credentials → Set password.** Turn **Temporary** off.
4. **Role mapping → Assign role →** filter by realm roles and pick `dfsp-operator`.

### Option B: Admin REST API

```shell
KC_ADMIN=$(curl -s -d grant_type=password -d client_id=admin-cli \
  -d username=admin -d password="$KEYCLOAK_ADMIN_PASSWORD" \
  $KC/realms/master/protocol/openid-connect/token | jq -r .access_token)

# create the user with its fsp_id and password
curl -s -X POST -H "Authorization: Bearer $KC_ADMIN" -H "$J" $KC/admin/realms/$REALM/users -d '{
  "username": "cvbank2-user", "enabled": true,
  "email": "operator@cvbank2.local", "emailVerified": true, "firstName": "CV", "lastName": "Bank 2",
  "attributes": { "fsp_id": ["cvbank2"] },
  "credentials": [{ "type": "password", "value": "cvbank2", "temporary": false }]
}'

# assign the dfsp-operator realm role (the users endpoint ignores "realmRoles" on create)
USER_ID=$(curl -s -H "Authorization: Bearer $KC_ADMIN" \
  "$KC/admin/realms/$REALM/users?username=cvbank2-user&exact=true" | jq -r '.[0].id')
ROLE=$(curl -s -H "Authorization: Bearer $KC_ADMIN" $KC/admin/realms/$REALM/roles/dfsp-operator)
curl -s -X POST -H "Authorization: Bearer $KC_ADMIN" -H "$J" \
  $KC/admin/realms/$REALM/users/$USER_ID/role-mappings/realm -d "[$ROLE]"
```

### Keeping it on a fresh setup

The steps above change the running Keycloak only. To have the user every time the realm is
imported, also add it to `src/main/resources/keycloak/dfsp-realm.json`, next to `cvbank-user`.
A realm import does honour `realmRoles`:

```json
{
  "username": "cvbank2-user",
  "enabled": true,
  "email": "operator@cvbank2.local",
  "emailVerified": true,
  "firstName": "CV",
  "lastName": "Bank 2",
  "attributes": { "fsp_id": ["cvbank2"] },
  "credentials": [{ "type": "password", "value": "cvbank2", "temporary": false }],
  "realmRoles": ["dfsp-operator"]
}
```

`setup-simulator.sh` imports the realm only when it doesn't exist yet. An existing realm still
needs Option A or B.

### Check the token

```shell
B2=$(tok cvbank2-user cvbank2)
cut -d. -f2 <<<"$B2" | tr '_-' '/+' | base64 -d 2>/dev/null | jq '{preferred_username, fsp_id, realm_access}'
```

`fsp_id` must be `"cvbank2"` and the roles must include `dfsp-operator`. Without the claim, every
DFSP-scoped call returns 403.

## 2. Simulator: create the DFSP (as `sim-admin`)

```shell
curl -s -H "Authorization: Bearer $ADMIN" -H "$J" $API/admin/dfsps -d '{
  "fspId": "cvbank2", "displayName": "CV Bank 2", "currency": "CVE",
  "netDebitCap": 100000, "initialPosition": 0, "initialFunds": 100000
}'
```

| Field | Meaning |
| --- | --- |
| `fspId` | the participant name in the hub, and the `fsp_id` of the DFSP's users |
| `currency` | the DFSP's only currency. It can't be changed later |
| `netDebitCap` | the NDC set in the hub. Outbound transfers are refused when `position + pending` would exceed it |
| `initialPosition` | the starting hub position, usually `0` |
| `initialFunds` | recorded as funds-in in the hub's SETTLEMENT account on onboarding (`0` to skip) |

This opens the DFSP's POSITION and SETTLEMENT ledger accounts. It returns 409 if the `fspId`
already exists. Only `displayName` and `netDebitCap` can be changed afterwards, with
`PUT /api/admin/dfsps/cvbank2`.

## 3. Hub: onboard the DFSP (as `sim-admin`)

```shell
curl -s -X POST -H "Authorization: Bearer $ADMIN" $API/admin/dfsps/cvbank2/onboard
```

This makes the following calls to central-ledger (`CentralLedgerAdminClient`):

1. creates the participant `cvbank2` in its currency (skipped if it already exists);
2. sets the NET_DEBIT_CAP and the initial position (skipped if already set);
3. registers every FSPIOP callback endpoint as `{SIMULATOR_PUBLIC_URL}/fspiop/cvbank2/...`;
4. records `initialFunds` as funds-in in the hub SETTLEMENT account, when greater than 0.

The endpoint does **not** activate the participant. `setup-simulator.sh` does, so do the same
when onboarding by hand:

```shell
curl -s -X PUT -H "$J" http://localhost:3001/participants/cvbank2 -d '{"isActive":true}'
```

Check the result in the hub:

```shell
curl -s http://localhost:3001/participants/cvbank2 | jq '{name, isActive}'
curl -s http://localhost:3001/participants/cvbank2/endpoints | jq '.[].value'
curl -s http://localhost:3001/participants/cvbank2/accounts | jq
```

(`3001` is the ml-core-test-harness central-ledger admin port. Use your `HUB_ADMIN_URL`.)

Run onboarding again whenever `SIMULATOR_PUBLIC_URL` changes. The endpoints are stored in the hub.

## 4. The DFSP's first customer (as `cvbank2-user`)

A DFSP can only receive transfers for identifiers it has registered in the ALS.

```shell
B2=$(tok cvbank2-user cvbank2)

C=$(curl -s -H "Authorization: Bearer $B2" -H "$J" $API/customers \
  -d '{"name":"Ana Tavares","contact":{"phone":"2389930000"}}' | jq .id)
A=$(curl -s -H "Authorization: Bearer $B2" -H "$J" $API/accounts -d "{\"customerId\":$C}" | jq .id)
curl -s -H "Authorization: Bearer $B2" -H "$J" $API/accounts/$A/deposits -d '{"amount":5000}'

# registers the MSISDN in the hub's ALS, pointing at cvbank2
curl -s -H "Authorization: Bearer $B2" -H "$J" $API/parties \
  -d "{\"idType\":\"MSISDN\",\"idValue\":\"2389930000\",\"accountId\":$A}"
```

Use an MSISDN that no other DFSP has registered. After a hub reset, register it again with
`PUT /api/parties/{id}/registration`.

## 5. Verify

```shell
# the DFSP as its operator sees it: details, POSITION and SETTLEMENT accounts
curl -s -H "Authorization: Bearer $B2" $API/dfsp | jq

# discovery from another DFSP must resolve the new MSISDN to cvbank2
BANK=$(tok cvbank-user cvbank)
curl -s -H "Authorization: Bearer $BANK" $API/parties/lookup/MSISDN/2389930000 | jq '{fspId, name}'
```

From then on, `cvbank2-user` sends transfers as the payer from `cvbank2`. The app also answers
as the payee for `cvbank2` whenever the hub routes a transfer to it. See
[P2P_WALKTHROUGH.md](P2P_WALKTHROUGH.md).

## Shortcut: `setup-simulator.sh`

Once the Keycloak user exists (step 1), the script can do steps 2–5. Pass every DFSP you want,
as `fspId:keycloakUser:password:customerName:msisdn:deposit` entries separated by `;`. The first
entry pays the second in the final check.

```shell
DFSPS="cvbank:cvbank-user:cvbank:Maria Silva:2389910000:5000;cvbank2:cvbank2-user:cvbank2:Ana Tavares:2389930000:0" \
  ./scripts/setup-simulator.sh
```

The script is meant to be re-run. It skips DFSPs and customers that already exist, and it
re-registers existing MSISDNs in the ALS.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| `401` on `/api/**` | bad token, or a temporary password still set on the user |
| `403` on `/api/**` | the user lacks `dfsp-operator` (or `admin` for `/api/admin/**`), or the token has no `fsp_id` claim |
| `404` on a DFSP-scoped call | the token's `fsp_id` is misspelt or names a DFSP the simulator doesn't have |
| hub rejects quotes/transfers for the DFSP | the participant isn't active (step 3) |
| `409` creating the DFSP | the `fspId` already exists; use `PUT` to change it |
| onboarding fails on the participant or its accounts | the hub isn't provisioned for the DFSP's currency (step 0) |
| registering a party fails | no MSISDN oracle in the ALS, or the DFSP isn't onboarded |
| discovery finds the party but the transfer never completes | the hub can't reach `SIMULATOR_PUBLIC_URL`; check the endpoints from step 3 |
