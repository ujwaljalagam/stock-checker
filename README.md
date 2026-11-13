# Stock Checker

Watch products at physical retail stores near you and get an alert the moment they're in stock.
Kotlin Multiplatform end to end: one shared module drives the Ktor server, a Kotlin/Wasm web dashboard
(with Web Push), an Android app (Compose), and an iOS app (SwiftUI).

## How it works

1. You sign in with a 6-digit code sent to your email. There are no passwords.
2. You add a **watch**: retailer + SKU + ZIP code + radius.
3. The server's **stock-check engine** re-checks every active watch on a schedule (default every 5 minutes),
   grouping identical retailer/SKU/ZIP checks so each is made once per round.
4. When a watch goes from not available to **in stock / low stock**, the server creates an **alert**. A cooldown
   (6 h per watch) stops alerts from repeating when stock flips back and forth.
5. The alert is sent as an encrypted **Web Push** message (VAPID, RFC 8291 `aes128gcm`) to every browser or
   Home Screen web app you enabled. The payload uses the **Declarative Web Push** format
   (`{"web_push": 8030, "notification": {...}}`, Safari 18.4+). The service worker also handles it in every other browser.
6. When the notification is actually on screen, the service worker posts a **delivery receipt** to
   `/api/alert-shown`. If no device confirms within `EMAIL_FALLBACK_MINUTES`, or you have no push-enabled
   device at all, the alert is **emailed** instead.

## Modules

| Module | What it is | Key tech |
|---|---|---|
| `shared/` | Code every platform uses: models and API contracts, `AlertPolicy` (when to alert), `Validation`, the `StockCheckUseCase` + `RetailerChecker` interface, the `StockCheckerApi` HTTP client, and `AppStore` (presentation state + intents used by the web, Android and iOS UIs). | KMP (jvm, android, iosX64/iosArm64/iosSimulatorArm64, wasmJs), Ktor client, kotlinx.serialization, coroutines |
| `server/` | REST API, email-code sign-in, watch CRUD, scheduled stock-check engine, Web Push sender, email sender, H2/Postgres via Exposed + Flyway migrations. Also serves the built web app. | Ktor server (Netty), Exposed, Flyway, HikariCP, `com.interaso:webpush`, Angus Mail |
| `webApp/` | Dashboard: sign in, add/pause/remove watches, "check now", alert history (with push/email delivery status), enable/disable push, iOS Home Screen guidance. `sw.js` handles `push`, `notificationclick` and `pushsubscriptionchange`. | Kotlin/Wasm (`wasmJs`), kotlinx-browser, a small `push.js` bridge |
| `androidApp/` | Sign-in, watches and alerts screens on top of the shared `AppStore`. A WorkManager job polls for new alerts and shows local notifications. | Jetpack Compose, Material 3, WorkManager |
| `iosApp/` | SwiftUI app on the shared `AppStore` (`Shared` framework). `BGAppRefreshTask` polls for new alerts and shows local notifications. | SwiftUI, XcodeGen project spec |

```
stock-checker/
├── shared/      src/commonMain/kotlin/app/stockchecker/{model,domain,data,presentation}, src/iosMain
├── server/      src/main/kotlin/app/stockchecker/server/{auth,checkers,db,engine,mail,push,routes,tools}
│                src/main/resources/{application.conf,db/migration/V1__init.sql}
├── webApp/      src/wasmJsMain/{kotlin,resources/{index.html,sw.js,push.js,manifest.webmanifest}}
├── androidApp/  src/main/kotlin/app/stockchecker/android
├── iosApp/      project.yml, iosApp/*.swift
├── ci/          github-actions-build.yml (move to .github/workflows/ to enable CI)
└── gradle/libs.versions.toml
```

## Versions

Kotlin 2.4.21 · Gradle 9.9.1 · Android Gradle Plugin 9.5.1 (built-in Kotlin + `com.android.kotlin.multiplatform.library`) ·
Ktor 3.6.1 · Compose BOM 2026.09.00 · Exposed 1.6.1 · Flyway 13.12.2 · compileSdk 37 / targetSdk 36 / minSdk 26 · iOS 16.4+

## Prerequisites

- **JDK 17** (`JAVA_HOME` set). Gradle is downloaded by the wrapper.
- **Android SDK** with platform 37 for `androidApp`. Set `ANDROID_HOME` or put `sdk.dir=...` in `local.properties`.
- **macOS + Xcode 16+** and [XcodeGen](https://github.com/yonaskolb/XcodeGen) for `iosApp`. iOS only builds on a Mac.
- A recent Chrome, Edge, Firefox or Safari for the web app.

## Run it locally

```bash
# 1. One-time: create VAPID keys for Web Push and put them in .env
cp .env.example .env
./gradlew -q :server:generateVapidKeys      # paste the three lines into .env

# 2. Build the web app (the server serves it)
./gradlew :webApp:wasmJsBrowserDistribution

# 3. Start the server on http://localhost:8080
set -a; . ./.env; set +a
./gradlew :server:run
```

Open http://localhost:8080, enter any email, and use the code from the server log (dev mode also shows it on the page).
Then add a watch with the **Demo Mart (mock)** retailer:

| SKU | Behaviour |
|---|---|
| `RESTOCK-1-PS5` | out of stock on the first check, in stock from the second check → triggers an alert |
| `RESTOCK-3-ANYTHING` | in stock from the 4th check |
| `INSTOCK-…` / `LOW-…` / `OOS-…` | always in stock / low stock / out of stock |
| `ERROR-…` | the checker fails (the error is shown on the watch) |
| anything else | flips between in and out of stock every 10 minutes |

Set `CHECK_INTERVAL_SECONDS=20` while trying it out, or press **Check now**.
Push works on `http://localhost` without HTTPS. Any other host needs HTTPS.

### Each target

| Task | Command |
|---|---|
| Server (dev) | `./gradlew :server:run` |
| Server distribution | `./gradlew :server:installDist` → `server/build/install/server/bin/server` |
| New VAPID keys | `./gradlew -q :server:generateVapidKeys` |
| Web app (production bundle) | `./gradlew :webApp:wasmJsBrowserDistribution` → `webApp/build/dist/wasmJs/productionExecutable` |
| Web app dev server | `./gradlew :webApp:wasmJsBrowserDevelopmentRun` (static only; for the API use the server, which serves the built bundle) |
| Android debug APK | `./gradlew :androidApp:assembleDebug` (install with `./gradlew :androidApp:installDebug`) |
| Android against another server | `./gradlew :androidApp:assembleDebug -PserverUrl=https://your.server` (default `http://10.0.2.2:8080` = your machine from the emulator) |
| Shared iOS code (works on Linux too) | `./gradlew :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64` |
| iOS app (Mac only) | `cd iosApp && xcodegen generate && open iosApp.xcodeproj`, then Run. The build phase calls `./gradlew :shared:embedAndSignAppleFrameworkForXcode`. The simulator talks to `http://localhost:8080` (`StockCheckerServerURL` in `project.yml`). |

## Configuration

All server settings are environment variables. See [`.env.example`](.env.example) and
`server/src/main/resources/application.conf`. The important ones:

- `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD`: H2 file DB by default (`server/data/`). Use
  `jdbc:postgresql://…` in production. Flyway applies `db/migration` at startup. The SQL works on both databases.
- `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` / `VAPID_SUBJECT`: without these, Web Push is off and alerts go by email.
- `SMTP_*`: without `SMTP_HOST`, emails (sign-in codes and alerts) are printed to the server log.
- `DEV_MODE`: **set to `false` in production**. Otherwise sign-in codes are returned by the API.
- `BESTBUY_API_KEY`: turns on the real Best Buy checker. `ENABLE_MOCK_RETAILER=false` hides the demo retailer.
- `CHECK_INTERVAL_SECONDS`, `EMAIL_FALLBACK_MINUTES`, `PUBLIC_BASE_URL`, `WEB_STATIC_DIR`, `CORS_HOSTS`.

## API

| Method & path | Auth | Purpose |
|---|---|---|
| `GET /health` | – | Liveness |
| `GET /api/config` | – | `vapid_public_key`, retailers, `email_fallback` |
| `POST /api/auth/request-code` `{email}` | – | Email a 6-digit code (10 min TTL, 30 s resend cooldown, 5 attempts) |
| `POST /api/auth/verify` `{email, code}` | – | Returns `{token, user}` (bearer token, 30 days) |
| `POST /api/auth/logout` · `GET /api/me` | Bearer | Session |
| `GET/POST /api/watches` · `PATCH/DELETE /api/watches/{id}` · `POST /api/watches/{id}/check` | Bearer | Watches (max 50 per user) |
| `GET /api/alerts` | Bearer | Last 100 alerts with `pushSentAt`, `shownAt` and `emailedAt` |
| `POST /api/push/subscribe` · `/unsubscribe` · `/test` · `GET /api/push/status` | Bearer | Web Push subscriptions |
| `POST /api/push/resubscribe` `{oldEndpoint, subscription}` | old endpoint | Called by `sw.js` on `pushsubscriptionchange` |
| `POST /api/alert-shown` `{alertId, receipt}` | per-alert receipt | Delivery receipt from `sw.js` |

## Retailers

Checkers implement `RetailerChecker` (in `shared`) and are registered in `server/.../Application.kt`.

- **`bestbuy`**: real in-store pickup availability from Best Buy's Products API
  (`GET /v1/products/{sku}/stores.json?postalCode=…`). Stores outside the watch radius are ignored, and
  `lowStock` maps to *Low stock*. Needs `BESTBUY_API_KEY`. Best Buy issues developer keys at
  https://developer.bestbuy.com, and their availability is up to Best Buy.
- **`mock`**: the predictable Demo Mart retailer described above.

To add a retailer, implement `check(product, zip, radiusMiles)` returning per-store `StoreAvailability`, then
add it to the `checkers` list.

## iPhone and iPad (web app)

iOS only allows Web Push for sites **added to the Home Screen** (iOS/iPadOS 16.4+). The dashboard detects
Safari tabs that aren't on the Home Screen and shows the Share → *Add to Home Screen* steps. The manifest
(`display: standalone`) and Apple meta tags make the installed app open full-screen.

## Native push (future decision)

Web Push can't reach native apps. For now the Android app polls with WorkManager (about every 15 minutes) and the
iOS app with `BGAppRefreshTask` (iOS decides when), and both show **local notifications**. Email remains the
reliable channel. Real-time native push needs **FCM** (Android) and **APNs** (iOS, or FCM in front of APNs).
That means adding a `device_tokens` table, a `/api/devices` endpoint and an FCM/APNs `PushSender` next to
`VapidPushSender`. Firebase is deliberately not included yet.

## Known gaps

- No payments or plans. Every user gets every feature.
- Only one real retailer (Best Buy). Target, Walmart, Home Depot and others need their own checkers. Most have no
  public stock API, and scraping them has legal and anti-bot issues.
- Sessions are opaque bearer tokens kept in `localStorage` (web), SharedPreferences (Android) and NSUserDefaults
  (iOS). Move them to secure storage (Keychain or EncryptedSharedPreferences) and add rate limiting per IP before launch.
- There's one server instance with an in-process scheduler. Running several replicas needs a lock (e.g. Postgres
  advisory lock) around `StockCheckEngine.tick()`.
- The iOS Xcode project is generated with XcodeGen and hasn't been compiled in CI. It needs a Mac.
