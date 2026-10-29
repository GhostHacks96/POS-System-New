# GhostPOS Register API

The Javalin JSON API listens on its own port and does not take the web interface's port. Defaults are enabled, bound to `127.0.0.1:7070`, with a one-hour session lifetime. These values can be changed in `%APPDATA%\GhostPOS\config.yml`:

```yaml
api:
  enabled: true
  host: 127.0.0.1
  port: 7070
  session-ttl-minutes: 60
web:
  enabled: true
  host: 127.0.0.1
  port: 8080
  api-base-url: ""
  allowed-origins:
    - http://127.0.0.1:8080
    - http://localhost:8080
```

The management interface is served separately at `http://127.0.0.1:8080`; it loads the supplied `src/main/resources/style.css` plus its responsive management styles. Its port and bind host can be changed with `web.port` and `web.host`. Restart GhostPOS after changing these settings. The API rejects ports `80`, `443`, and `8080`; keep it on its dedicated port.

Both servers bind to loopback by default. To use them from another computer, bind them to a trusted server address, configure `web.api-base-url` to the browser-reachable API URL, add the exact browser origin(s) to `web.allowed-origins`, and put a trusted TLS reverse proxy in front of both services. The API only allows CORS for these explicitly configured origins. Javalin is not configured to provide TLS; keep both services firewalled and do not expose them directly to the public internet. Use HTTPS for any non-local deployment.

All routes are under `/api/v1`. `GET /health` is public. Sign in with `POST /auth/login` and JSON `{"username":"...","password":"..."}`; send the returned token on protected routes in an `Authorization` bearer header. `POST /auth/logout`, `GET /auth/me`, and `POST /auth/password` manage the current session.

The API currently provides:

- Products: `GET /products`, `GET /products/{id}`, `GET /products/low-stock`, and permission-protected `POST /products` / `PUT /products/{id}`.
- Inventory: `POST /inventory/{id}/receive`, `/remove`, `/adjust`, and `/count`; history is available at `GET /inventory/transactions` and `GET /inventory/{id}/transactions`. Quantity changes are recorded with the authenticated user's ID.
- Users: `GET` and `POST /users`, `PATCH /users/{id}/role`, `PATCH /users/{id}/active`, and `POST /users/{id}/password-reset`.
- Settings: `GET` and `PATCH /settings`. Only the store display name is exposed or editable; database credentials and other secrets are never returned.
- Administrator diagnostics: `GET /system/config` returns the current configuration with secret-like keys and URL user-info masked; `PUT /system/debug` with `{"enabled":true}` or `{"enabled":false}` immediately persists the debug setting to `config.yml`; `GET /system/logs?after=0` returns recent log entries with sequence numbers for incremental polling. These routes require `ADMINISTER_SYSTEM`. The live log endpoint serves a bounded in-memory tail from the current backend process, not arbitrary files.
- Backend controls: `POST /system/reload-config`, `POST /system/reload-storage`, and `POST /system/shutdown` require `ADMINISTER_SYSTEM` (the administrator role). Configuration reload applies runtime values but does not rebind running API/web listeners or alter active sessions; the response indicates when listener/session settings require a restart. Storage reload re-reads YAML settings and initializes the configured storage backend before switching services to it. Shutdown flushes its `202 Accepted` response before the backend exits gracefully.
- Checkout: `POST /sales` requires `Idempotency-Key` and JSON containing `items` (`productId`, `quantity`), optional `customerId` and `discountAmount`, and `tenders` (`method`, `amount`, optional `reference`). The backend reads product names and prices from storage, checks stock and permissions, calculates the total, then records the sale, tenders, and inventory deductions in one database transaction. A successful response contains the printable receipt, tender breakdown, and cash change due.
- Sales history: `GET /sales?limit=100` returns up to 500 recent sales. Cashiers see their own sales; users with report-view permission see all sales.
- Receipt lookup: `GET /sales/{saleNumber}/receipt` returns a persisted receipt. Cashiers can retrieve their own receipts; users with report-view permission can retrieve any sale receipt.

API permissions are checked against the user's current account and role for every request. Session tokens are random, held in memory as hashes, and expire after the configured lifetime; restarting GhostPOS invalidates them. Responses use JSON errors and do not return database exception details.

The idempotency key is stored as the unique sale number. Repeating the same key and payload returns the original receipt without decrementing stock again; reusing the key for a different payload returns a conflict. Save the key with the register's pending transaction so a network retry can reuse it.

Tender methods currently supported are `CASH`, `CARD`, `GIFT_CARD`, and `STORE_CREDIT`. These are **recorded tenders only**: GhostPOS does not authorize or charge a card, activate gift cards, or validate store credit. For card payments, the external terminal/provider must confirm payment before the register submits the sale. Never send or store a card number, CVV, PIN, or magnetic-stripe data; `reference` is only for a safe external transaction reference.

Tax calculation is not configured yet, so checkout currently records zero tax. Do not use the generated totals as tax-compliant receipts until a jurisdiction-specific tax policy is implemented and verified.

The management interface includes role-aware inventory editing and stock operations, user creation and account/role/password management, the store-name setting, recent inventory transaction history, and sales history with printable receipt views. Administrators also get confirmed actions to reload configuration and storage or shut down the backend. All writes still go through the authenticated API and its server-side permission checks; controls are hidden for roles without permission, but hiding controls is not used as authorization.
