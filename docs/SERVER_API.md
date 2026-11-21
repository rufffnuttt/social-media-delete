# NoScroll ⇄ website API

The store build of the NoScroll app (`play` flavor) talks to **one website** whose
address is set at build time (`NOSCROLL_SITE_URL`). Any backend can host it, as long
as it implements the endpoints below. A reference implementation exists in
`rufffnuttt/dbaudioservices` (branch `noscroll-pro`, `app/api/noscroll/*`); the same
contract can be implemented in forumsapp (e.g. following its `/api/kingdom/checkout`
→ `/api/kingdom/unlock` pattern) and the app works unchanged.

All bodies are JSON. Authenticated calls send `Authorization: Bearer <token>`.
Errors return a non-2xx status with `{ "error": "message shown to the user" }`.

## Sign-in

### `POST /api/noscroll/auth`
Request: `{ "idToken": "<Google ID token>" }`

The app gets the ID token from Android Credential Manager, requested for the
**web** OAuth client ID (`NOSCROLL_GOOGLE_WEB_CLIENT_ID`), so its `aud` is that web
client ID. The server must **verify** it (signature, `aud`, `iss`, `exp`,
`email_verified`), e.g. via `https://oauth2.googleapis.com/tokeninfo?id_token=…`
or Google's JWKS. Never just base64-decode it.

Response `200`:
```json
{ "token": "opaque session token (long-lived, e.g. 1 year)",
  "email": "user@gmail.com", "name": "User Name",
  "pro": true, "plan": "yearly", "status": "active", "expiresAt": 1797839804000 }
```

### `POST /api/noscroll/logout`
Revokes the bearer token. Response `{ "success": true }`.

## Pro status

### `GET /api/noscroll/status` (bearer)
Response `200`:
```json
{ "signedIn": true, "email": "user@gmail.com", "name": "User Name",
  "pro": true,
  "plan": "monthly" | "yearly" | "lifetime" | null,
  "status": "active" | "trialing" | "past_due" | "canceled" | null,
  "expiresAt": 1797839804000 }
```
- `expiresAt` = end of the paid period in **milliseconds** (null for lifetime).
- `401` means the token is invalid/revoked; the app signs out locally.
- The app caches the result and keeps Pro for 3 days past `expiresAt` if offline.

## Buying

### `GET /api/noscroll/plans` (public)
```json
{ "plans": [
  { "id": "monthly",  "label": "Monthly",  "price": "$2.99 / month" },
  { "id": "yearly",   "label": "Yearly",   "price": "$19.99 / year" },
  { "id": "lifetime", "label": "Lifetime", "price": "$39.99 once" } ] }
```
Shown in this order (yearly first, then monthly, then lifetime); `price` is display text.

### `POST /api/noscroll/checkout` (bearer)
Request `{ "plan": "monthly" | "yearly" | "lifetime" }` → `{ "url": "https://checkout.stripe.com/…" }`.
The app opens the URL in the browser. Stripe's `success_url` should land on a page
with a button linking to **`noscroll://pro`**, which reopens the app's upgrade
screen; the app then calls `/status` again.

### `POST /api/noscroll/portal` (bearer)
→ `{ "url": "https://billing.stripe.com/…" }` (Stripe Customer Portal: cancel,
change plan, update card).

## Keeping status current
Fulfil on return from Checkout (verify the session server-side) **and** via a Stripe
webhook for `checkout.session.completed` and `customer.subscription.created /
updated / deleted`, so renewals and cancellations update `status` / `expiresAt`.
