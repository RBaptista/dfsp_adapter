# API authentication (Keycloak)

The `/api/**` endpoints require a Keycloak bearer token. The `/fspiop/**` endpoints are for the
Mojaloop hub and don't use Keycloak.

## Client

`dfsp-simulator` is a **public** client, so there is **no client secret**. A token comes from the
client id plus the username and password of a Keycloak user.

| | |
| --- | --- |
| Token URL | `http://localhost:8080/realms/dfsp/protocol/openid-connect/token` |
| Client ID | `dfsp-simulator` |
| Client secret | none |
| Grant type | `password` |

## Users

The user you log in as decides what you can do. Users have the `dfsp-operator` role, plus an
`fsp_id` attribute that ties them to one DFSP. Admins have the `admin` role.

| User | Password | Access |
| --- | --- | --- |
| `sim-admin` | `admin` | admin endpoints (`/api/admin/**`, `POST /api/settlements/reconcile`) |
| `cvbank-user` | `cvbank` | operates DFSP `cvbank` |
| `cvwallet-user` | `cvwallet` | operates DFSP `cvwallet` |

These are demo credentials for local development only.

## 1. Import the realm (once)

1. Open <http://localhost:8080/admin> and log in as the Keycloak admin. Locally that's
   `admin` / `admin`, from the container's env vars.
2. In the realm dropdown at the top left, choose **Create realm**.
3. Click **Browse**, select `src/main/resources/keycloak/dfsp-realm.json`, then **Create**.

Instead of the console, you can start Keycloak with `--import-realm` and the file mounted in
`/opt/keycloak/data/import`.

## 2. Get a token

```shell
curl -s http://localhost:8080/realms/dfsp/protocol/openid-connect/token \
  -d grant_type=password -d client_id=dfsp-simulator \
  -d username=cvbank-user -d password=cvbank | jq -r .access_token
```

Tokens are valid for 30 minutes.

## 3. Call the API

```shell
TOKEN=$(curl -s http://localhost:8080/realms/dfsp/protocol/openid-connect/token \
  -d grant_type=password -d client_id=dfsp-simulator \
  -d username=cvbank-user -d password=cvbank | jq -r .access_token)

curl -H "Authorization: Bearer $TOKEN" http://localhost:8090/api/dfsp
```

### Postman / Bruno

`test quarkus hexa.postman_collection.json` (repo root) covers every `/api` endpoint:
- It needs an environment with `url` (`http://localhost:8090`) and `keycloak_url`
  (`http://localhost:8080`).
- Run one of the requests in the `auth` folder first (cvbank-user, cvwallet-user or sim-admin). It
  stores the access token in the environment's `token`, and the whole collection uses it as a bearer
  token.
- Create requests save the new id in a collection variable (`customerId`, `accountId`, `partyId`,
  `transferId`), so the get/update requests after them work without editing.
- `payerMsisdn`/`payeeMsisdn` default to the setup script's demo parties. Change `msisdn` before
  creating another party, because each identifier can be registered only once.

To get a token without the collection, on the **Authorization** tab:
- Type: **OAuth 2.0**
- Grant type: **Password Credentials**
- Access token URL: the token URL above
- Client ID: `dfsp-simulator`
- Client secret: leave empty
- Username and password: one of the users above

## Adding users

In the `dfsp` realm:
1. Go to **Users → Add user** and create the user.
2. On the **Attributes** tab, set `fsp_id` to the DFSP id (for example `cvbank`).
3. On the **Credentials** tab, set a password and turn **Temporary** off.
4. Under **Role mapping**, assign `dfsp-operator` (or `admin`).

The DFSP-scoped endpoints need `fsp_id`; without it they return 403. Admin endpoints only need the
`admin` role.

## Expected responses

| Situation | Status |
| --- | --- |
| No token, or an expired one | 401 |
| Missing role (e.g. an operator calling `/api/admin/**`) | 403 |
| Token without `fsp_id` on a DFSP-scoped endpoint | 403 |
| Another DFSP's resource | 404 |

## Configuration

The app finds Keycloak through `KEYCLOAK_URL` (default `http://localhost:8080`) and
`KEYCLOAK_REALM` (default `dfsp`). See `quarkus.oidc.*` in `application.properties`.

## Machine-to-machine access (not set up yet)

To get a real client id and secret (the client-credentials grant), add one **confidential** client
per DFSP to the realm. Turn on its service account and add a hardcoded-claim mapper that sets
`fsp_id` to that DFSP.
