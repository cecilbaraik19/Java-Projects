# Microservices-Based E-Commerce System

**Bandar Bazaar** ("bandar" = harbour) is an online shop built from **10 small services** instead of one big program,
with a normal storefront for customers and a monitoring console for the store admin.

Plain **Java 17** backend (no external libraries) and a plain **HTML / CSS / JavaScript** frontend,
the same setup as the other projects in this folder. **Light mode and dark mode** are both built in.
The look is a new **"container port"** theme: a navy gate bar with a hazard stripe, real product photos on shipping-label
cards, category tiles, an order tracker, and (for the admin) services shown as shipping containers with the event bus as a conveyor belt.

## Run it

You only need **Java 17 or newer** (JDK). No database, no Docker, no npm.

**First time only:** double-click `download-photos.bat`. It saves the 30 real product photos (from Unsplash) into
`frontend/img/products`, so after that the shop shows real photos even with no internet. If a photo is missing,
the shop shows a drawn icon instead.

| How | What to do |
|---|---|
| Windows (easiest) | Double-click `run.bat` - all 10 services start from one window |
| Windows (real microservices) | Double-click `run-separately.bat` - **10 windows, one per service**. Close a window to "crash" that service |
| Mac / Linux | `./run.sh` or `./run-separately.sh` |
| IntelliJ | Open this folder, wait for Maven to load, open `backend/src/main/java/com/cecil/shop/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/microservices-ecommerce.jar` |

Then open **http://localhost:8080** in your browser.

### Demo logins

| Username | Password | Who | Good for |
|---|---|---|---|
| admin | admin123 | Store admin | Products, stock, all orders, overview, and the **switch-off / slow-down** buttons in the Services monitor |
| riya | shop123 | Customer, Rs 75,000 in wallet | Normal shopping, a saga that succeeds |
| arjun | shop123 | Customer, only Rs 1,200 | Buying something costly - watch the saga **fail and undo** the stock hold |
| meera, kabir | shop123 | Customers | More past orders |

You can also **create an account** (you get Rs 10,000 demo money by an event). All people and brands are made up.
To start fresh, stop the app and delete the `data` folder. If you ran an older version of this project, you do not
need to: each service notices that its saved demo data is from an older version (`SeedData.VERSION`) and starts fresh.

## The 10 services

| Service | Port | Job | Own data (`data/...`) |
|---|---|---|---|
| api-gateway | 8080 | The only door for the browser: routing, login check, roles, rate limit, circuit breakers, fallback cache, trace ids, composing pages | - |
| registry | 8761 | Phone book: services register and send a heartbeat every 3 s; silent for 9 s = DOWN | (in memory) |
| event-bus | 8090 | Message broker: publish / subscribe, retries, dead-letter queue | subscriptions |
| auth-service | 8081 | Accounts, passwords (PBKDF2), signed login tokens | users |
| catalog-service | 8082 | Products, prices, categories | products |
| inventory-service | 8083 | Stock: reserve, commit, release, return | stock, reservations, movements |
| cart-service | 8084 | Carts (only product id + qty; prices fetched live from catalog) | carts |
| order-service | 8085 | Orders and the **checkout saga** | orders, pending undo steps |
| payment-service | 8086 | Demo wallet: charge, refund, top-up | wallets, payments, ledger |
| notification-service | 8087 | Inbox messages - it only listens to events | notifications |

Even with `run.bat` (one window) every service is its own web server on its own port with its own data folder,
and they only talk over HTTP - exactly as if they were on different machines.

## Architecture features

| Idea | Where to see it | Code |
|---|---|---|
| API gateway | (admin) The **Live API calls** bar at the bottom shows which service answered each call | `gateway/GatewayService.java` |
| Service registry + discovery, heartbeats | **Services** monitor (admin) - switch a service off, watch it go DOWN after 3 missed beats | `registry/RegistryService.java`, `common/Discovery.java` |
| Database per service | `data/` has one folder per service | `common/JsonStore.java` |
| Synchronous REST calls | Cart page (cart asks catalog for prices) | `common/ServiceClient.java` |
| Events (pub/sub), loose coupling | **Events** page and the conveyor belt in the Services monitor (admin) | `eventbus/EventBusService.java` |
| **Saga + compensation** | Open any order - every step and every undo step is listed | `order/OrderService.java` |
| Idempotency | Checkout key; payment never charges an order twice | `OrderService.place`, `PaymentService.charge` |
| Circuit breaker | Fuse switches in the Services monitor (gateway + order service) | `common/CircuitBreaker.java` |
| Fallback / graceful degradation | Switch off catalog or inventory, then open the shop | `GatewayService.proxy`, `shopProducts` |
| Outbox | Switch off the event bus, register or order, switch it on again | `ServiceBase.publish` |
| Retries + dead letters + idempotent consumers | Switch off notification, order, wait, replay from the Events page | `EventBusService.deliver`, `ServiceBase.receiveEvent` |
| Distributed tracing | **Traces** page - a waterfall of one action across all services | `ServiceBase.record`, `GatewayService.archTraces` |
| API composition | Shop page = catalog + inventory; admin overview = 4 services at once | `GatewayService.shopProducts`, `overview` |
| Stateless token auth (like JWT) | Login; the gateway checks the signature itself | `common/Tokens.java` |
| Rate limiting (token bucket) | 600 requests/min per visitor, 15 logins/min | `gateway/RateLimiter.java` |
| Load balancing (round-robin) | Start a second copy of a service on another port | `Discovery.pick` |

Customers only see a normal shop. The **Services**, **Traces** and **Events** pages and the Live API calls bar are visible
to the admin only (the gateway refuses /api/arch/* for everyone else).

### Failure drills (sign in as admin, open Services)

1. **Switch off Payment**, then place an order as riya: the saga cannot charge, so it releases the stock it reserved. Order = FAILED, no money taken.
2. **Switch off Catalog**, open the Shop: a yellow banner says you are seeing the gateway's **saved copy**.
3. **Switch off Inventory**: products still show, with "Stock ?".
4. **Switch off Event bus**, register a new user, switch it on: the event waited in the **outbox**; the wallet bonus arrives.
5. **Switch off Notification**, place an order, wait ~10 s, switch on: the delivery is in the **dead-letter queue** - press Replay.
6. **Slow down Inventory** (+800 ms), place an order, open its trace: the long bars show where the time went.

## The checkout saga

```
order-service (orchestrator)
  0. read cart (cart-service) + real prices (catalog-service), save order as PENDING
  1. Reserve stock   -> inventory-service   fails? order REJECTED (nothing to undo)
  2. Take payment    -> payment-service     fails? undo 1 (release stock) -> order FAILED
  3. Confirm stock   -> inventory-service   fails? undo 2 (refund) + undo 1 -> order FAILED
  4. CONFIRMED -> publish "order.confirmed"
        cart-service hears it         -> empties the cart
        notification-service hears it -> inbox message
```

If an undo step cannot reach its service, it is saved and retried every 3 seconds until it works.

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` (Mac / Linux). **33 tests**, no JUnit needed.
The first 9 test small parts (JSON, passwords, tokens, circuit breaker, rate limiter, routing, data store, data version).
The other 24 start **all 10 services** on free ports and test the real system through the gateway:
registry, internal key, composed shop page with photo paths (and no path tricks), login cookie and lock-out, fake identity headers, internal-only APIs,
cart prices, saga success / payment failure / out of stock, idempotency, cancel + refund, order status flow,
product.created event, welcome bonus event, payment charged once, catalog down (fallback), inventory down,
payment down, event bus down (outbox), receiver down (retries + dead letter + replay), tracing, and DOWN/UP in the registry.

## Security

- Passwords stored with PBKDF2-SHA256 and a random salt; 5 wrong tries lock the username for 5 minutes.
- Login token is signed (HMAC-SHA256) and kept in an **HttpOnly, SameSite=Strict cookie** - page scripts cannot read it.
- The gateway **removes any X-User-\* headers sent by the browser** and sets them itself after checking the token.
- Every service refuses calls without the **internal key**, so nobody can skip the gateway. Payment only accepts charges from the order service.
- Role checks happen at the gateway **and** again inside each service.
- Requests from other websites are refused (Origin check). Strict Content-Security-Policy; all text escaped before display.
- Rate limiting per visitor and a tighter limit on login.

## Project layout

```
microservices-ecommerce/
├── backend/
│   ├── pom.xml                         Maven build (optional - run.bat / run.sh use plain javac)
│   └── src/main/java/com/cecil/shop/
│       ├── App.java                    starts all services, or just one (java -jar ... order-service)
│       ├── common/                     shared by every service: ServiceBase (web server, heartbeat, events,
│       │                               tracing, chaos switch), ServiceClient, Discovery, CircuitBreaker,
│       │                               JsonStore, Json, Tokens, Passwords, Router, Req, Resp, Config
│       ├── registry/  eventbus/  gateway/
│       ├── auth/  catalog/  inventory/  cart/  order/  payment/  notification/
│       └── seed/SeedData.java          demo data (each service takes only its own part)
│   └── src/test/java/.../AllTests.java 33 tests
├── frontend/                           index.html, app.css, app.js (shop + admin), arch.js (services monitor, traces,
│                                       events - admin only), theme.js, icons.svg, favicon.svg
│   └── img/products/                   30 product photos (made by download-photos.bat) + sources.txt
├── download-photos.bat / .ps1          downloads the product photos once
├── run.bat / run.sh                    build and start (one window)
├── run-separately.bat / .sh            build and start each service separately
└── run-tests.bat / run-tests.sh        build and test
```

## Photo credits

All product photos are from [Unsplash](https://unsplash.com) and free to use under the Unsplash License.
`frontend/img/products/sources.txt` lists which Unsplash photo is used for each product (by SKU).
