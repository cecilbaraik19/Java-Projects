# Sanket – Event-Driven Order Processing System

An online shop where **every order travels as events** between independent services, instead of one big program
calling functions in one big database. When a customer presses *Book order*, the order service only writes down the
order and an **OrderPlaced** event. The inventory, payment, shipping and notification services each **react to
events**, do their own small job, and publish new events. Nobody waits for anybody.

| Part | What it does in Sanket |
|---|---|
| **Event broker** | Written from scratch, works like Apache Kafka: **topics** (orders, inventory, payments, shipments, dead-letter), each split into **partitions**. Every event gets an **offset**. Events are never changed or deleted. Saved as NDJSON files (one event per line). |
| **Consumer groups** | 6 services read the log: inventory, payment, shipping, order-saga, notifier, order-view. Each remembers its own **committed offset** per partition, so it can stop, restart and continue, or **replay** from 0. |
| **Saga with compensation** | Placing an order is a business process over 4 services, without one big transaction. If stock is missing or the bank says no, the saga sends **OrderCancelled** and the others **undo** their part: stock is released, money is refunded, the courier booking is cancelled. |
| **Transactional outbox** | A command saves its change and its event **in one step**. A relay publishes the event a moment later. If the broker is down, orders are still accepted – they wait in the outbox. |
| **Retries and dead letters** | A technical error is retried after **1 s, 2 s, 4 s** (exponential back-off). After the 4th try the event goes to the **dead-letter list** ("Lost property") and the consumer moves on. An admin can **redrive** it after the fix, or **discard** it. |
| **Idempotent consumers** | Delivery is **at-least-once**: an event is never lost but may come twice. Every consumer skips event ids it has seen, and checks its own records ("is this order already paid?"). So nothing is ever charged, reserved or booked twice – even after a full replay. |
| **CQRS read model** | The order screens are built **only from events** by the order-view consumer. It can be thrown away and **rebuilt from the log**. Its status does not depend on the order events arrive in. |
| **Schema registry and versions** | Every event type has a schema; the broker refuses broken events. The old mobile app still sends **OrderPlaced v1**; consumers **upcast** it to v2. |
| **Tracing** | Every event of an order carries the same **correlation id** and the id of the event that caused it (**causation id**). The order page shows the full journey and which service has handled each event. |
| **Fault drill** | Payment gateway down, broker down, slow consumer, consumer crash, duplicate delivery, poison message, flash sale – cause them on purpose and watch the system cope. |

The backend is plain **Java 17** with no external libraries: the web server, JSON reader, password hashing, the event
broker, the dispatcher and the simulator all use the JDK only. The frontend is plain **HTML / CSS / JavaScript**, the
same setup as the other projects in this folder. **Light mode and dark mode** are both built in.

The look is a new **"railway signal box"** theme. An event-driven system works a lot like a railway: events are
trains running on tracks (topics), and every service is a signal cabin along the line.

- the menu is the **lever frame** of a signal box – red levers for the live screens, black for the yards, yellow for
  the charts, blue for the admin locks; the open page's lever is "pulled";
- every page title is a yellow **station name board** in English and Hindi, like on Indian Railways;
- the **Signal box** page is a **mimic panel**: topics are coloured tracks, the events of the last minute are little
  trains moving along them, and every consumer group is a cabin with a **three-lamp signal** (green = clear,
  yellow = caution, red = danger) and its lag on a seven-segment counter;
- the newest events run on a split-flap **departure board**;
- an order is a cardboard **Edmondson ticket**, and its journey is a **route strip** with stations;
- the event log is a ruled **register**, dead letters are **luggage tags** in the *Lost property* office, the outbox is
  the *Parcel office*, the schema registry is the *Rule book*;
- the text is set in Barlow Condensed (signage), Barlow and JetBrains Mono.

Dark mode is **Night shift** (switch it at the bottom of the menu).

*Sanket* (संकेत) means "signal".

## Run it

You only need **Java 17 or newer** (JDK). No internet, no database, no Kafka, no npm.

| How | What to do |
|---|---|
| Windows | Double-click `run.bat` |
| Mac / Linux | `./run.sh` |
| IntelliJ | Open this folder, wait for Maven to load, open `backend/src/com/cecil/sanket/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/sanket-orders.jar` |

Then open **http://localhost:8080** in your browser. (Set `PORT` to use another port.)

The first start takes a few seconds. It builds the demo shop in the `data` folder: the made-up **Jharkhand Haat**
online shop in Ranchi, with 18 products (Dokra brass, Sohrai mugs, Tussar stoles, earbuds, power banks …) and 40
customers in 10 cities. Then it **plays about 3½ days of shopping through the real system** – every order goes
through the outbox, the broker and all six consumers with the clock moved back – so the log, the charts and the
read model are real (about 3,000 events and 430 orders). Everything is dated relative to *today*. **All people,
products and numbers are made up.** To start fresh, stop the app and delete the `data` folder.

A built-in **simulator** keeps the shop alive: about 6 orders a minute, the warehouse dispatches and the courier
delivers, and the supplier restocks when something runs low. Real orders from the website go through exactly the
same path.

### Demo logins

| Username | Password | Role | Can do |
|---|---|---|---|
| admin | admin123 | Platform admin | Everything: stop / restart / replay consumers, rebuild the read model, redrive or discard dead letters, **fault drill**, user accounts, activity log |
| priya | staff123 | Operations staff | Signal box, orders, event register, consumers, lost property, outbox, inventory (restock), charts, rule book, dispatch and deliver parcels, test orders |
| manoj | staff123 | Operations staff | Same as priya |
| neha | customer123 | Customer (Ranchi) | Shop, her own orders with their live journey, cancel, messages |
| arjun | customer123 | Customer (Jamshedpur) | Same, for Arjun |

### Try it

1. Sign in as **neha**. At the **Booking counter** add two things, choose UPI and press **Book order**. You get an
   answer at once and land on your order. Watch the **route strip** fill up in well under a second: placed → stock
   held → paid → confirmed, with the time of each hop. The **trace** below lists every event of your order with its
   topic, partition and offset, and green lamps for the services that handled it. Then **Cancel order** and watch the
   compensation: OrderCancelled → PaymentRefunded, StockReleased, ShipmentCancelled. **Messages** shows what the
   notifier sent you.
2. Sign in as **admin**. The **Signal box** shows the whole railway live. Five problems were planted for the demo:
   yesterday the payment gateway was down for 20 minutes – most of those payments were redriven, but **3 are still in
   Lost property** and their orders are **stuck** at "stock held"; and 2 hours ago a gift note with `{{` **poisoned**
   the notifier.
3. Open **Lost property** and **Redrive** the three payment dead letters. Their orders continue: paid → courier booked
   → confirmed. Redrive the notifier one: it fails again. Go to **Fault drill → Deploy a fix**, tick *Notifier template
   fix deployed*, come back and redrive it – now it works.
4. **Fault drill → Payment gateway down** for 2 minutes. On the Signal box the payment cabin turns **yellow**
   (retrying); after 1 + 2 + 4 seconds each payment lands in Lost property. Orders wait at "stock held". When the
   fault ends, redrive them.
5. **Broker down** for 1 minute. The mimic shows BROKER DOWN, all signals go red – but customers can still order:
   watch the **Parcel office** (outbox) fill up. When the broker is back, the rows go out in order and everything
   catches up.
6. **Consumer crash** on *shipping*. Its signal turns red and its lag climbs; orders wait at "paid". On **Consumer
   cabins** press **Restart**: it continues from its committed offset and the lag falls back to 0. Nothing is lost.
7. **Duplicate delivery** on *payment*: the next 5 offset commits are "lost", so the broker delivers those events
   again. On the payment cabin, *duplicates skipped* goes up – and nobody is charged twice.
8. **Consumer cabins → order-view → Rebuild from log**: all order screens are thrown away and rebuilt by replaying
   every event from offset 0. The lag jumps to ~3,000 and comes back to 0 in a second, with the same result.
9. **Flash sale**: 6× more orders, mostly for the Dokra brass horse (only ~14 in stock). When it runs out, inventory
   answers StockRejected and the saga cancels those orders ("out of stock").
10. **Event register**: browse any topic and partition offset by offset. Blue lines show where each consumer group
    will read next. **Rule book**: all 19 event schemas, who writes and who reads each one.

## How it works

```
 website / simulator
        │  command (place, cancel, dispatch, restock)
        ▼
 OrderService ──► change + outbox row (one step) ──► OutboxRelay (every 200 ms) ──► EventBroker
                                                                                    topics × partitions
                                                                                    (data/log/*.ndjson)
                                                                                         │
            Dispatcher (every 200 ms): for each consumer group, each partition, from its committed offset
                                                                                         │
     ┌───────────────┬───────────────┬───────────────┬───────────────┬───────────────┬───┴───────────┐
  inventory        payment         shipping        order-saga       notifier        order-view
  hold / release   charge / refund courier / cancel decides outcome  messages        read model (CQRS)
     └──── each publishes its own new events back to the broker ─────┘
```

### The happy path

| # | Event | Topic | Written by | Read by |
|---|---|---|---|---|
| 1 | OrderPlaced | orders | order-service (via outbox) | inventory, order-saga, notifier, order-view |
| 2 | StockReserved | inventory | inventory-service | payment, order-view |
| 3 | PaymentAuthorized | payments | payment-service | shipping, order-view |
| 4 | ShipmentCreated | shipments | shipping-service | order-saga, order-view |
| 5 | OrderConfirmed | orders | order-saga | notifier, order-view |
| 6 | ShipmentDispatched | shipments | shipping-service (via outbox) | inventory (StockCommitted), order-saga, notifier, order-view |
| 7 | ShipmentDelivered | shipments | shipping-service (via outbox) | order-saga, notifier, order-view |

**When something goes wrong** – StockRejected or PaymentFailed or a customer's OrderCancelRequested – the saga
publishes **OrderCancelled**, and every service undoes its own step if it had done one: StockReleased,
PaymentRefunded, ShipmentCancelled. A cancel that arrives after the parcel left gets **OrderCancelDenied**.

### Ordering

The partition is chosen from the order id: `partition = hash(orderId) mod 3`. So **all events of one order on one
topic are in one partition, in order**. Between topics there is **no** order guarantee – the payment service may see
OrderCancelled (orders topic) before StockReserved (inventory topic). It handles that: it writes a "voided" record so
it will never charge when StockReserved arrives later. The read model also does not trust arrival order: it records
when each stage happened and works the status out from the set of stages.

### Retries

| Try | Waits before it |
|---|---|
| 1 | – |
| 2 | 1 s |
| 3 | 2 s |
| 4 | 4 s |
| then | dead letter + DeadLettered event, offset committed, the group moves on |

While a partition is waiting for a retry, the later events in that partition wait too (so the order stays right).
Other partitions keep flowing.

### Signals

| Signal | When |
|---|---|
| 🟢 Clear | keeping up |
| 🟡 Caution | retrying, running slowly, or more than 25 events behind |
| 🔴 Danger | stopped (crashed / paused) or the broker is down |

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` runs **36 tests** without JUnit (nothing to download). The clock is
frozen at Wednesday 11 Nov 2026, 10:30 IST, so the results never change. The tests cover:

* JSON, passwords and percentiles;
* partitioning, offsets, the schema registry, upcasting, broker down, and reloading the log (with a half-written line);
* the saga: happy path, out of stock, payment declined, customer cancel with full compensation, dispatch and
  delivery, cancel denied after dispatch, old-app v1 orders, order validation;
* retries with back-off, dead letters, redrive after the fix, discard;
* lost commits → duplicates skipped → no double charge; replay from offset 0 does nothing twice;
  rebuilding the read model; status independent of arrival order; cancel seen before StockReserved;
* the outbox while the broker is down; a stopped consumer catching up; a slow consumer; a poison message;
  the notifier retrying for an unknown customer;
* the demo history, the overview and analytics numbers, the simulator and its faults;
* account lock, customers seeing only their own orders, and the web server (CSRF header, roles, static files,
  placing an order over HTTP).

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | 8080 | Web port |
| `DATA_DIR` | `data` in this folder | Where the JSON data and the event log (`log/`) are kept |
| `SIMULATOR` | on | `off` = no made-up shoppers, only orders from the website |

## Security

* Passwords: PBKDF2-HMAC-SHA256 with 120,000 rounds and a random salt. 5 wrong passwords lock the account for 5
  minutes. There is a limit of 10 sign-in attempts per minute per computer, and the same message for "no such user"
  and "wrong password".
* Sessions: a random token in an **HttpOnly, SameSite=Strict** cookie. Only its SHA-256 hash is kept, and it ends
  after 12 hours without use.
* CSRF: every change needs the `X-Sanket` header, a same-site Origin and JSON.
* Content-Security-Policy, X-Frame-Options and nosniff headers are sent. Pages are built without `innerHTML`, so
  there is no XSS.
* Prices always come from the catalogue on the server, never from the browser. Quantities, payment methods and
  cities are checked.
* Roles are checked on the server: customers see only their own orders and not the services' internal records;
  only staff can dispatch or restock; only the admin can replay, redrive, cause faults or manage accounts.
* The schema registry stops broken events from entering the log.
* The activity log records sign-ins, orders, cancels, dispatches, restocks, replays, redrives, faults and more.

## Folders

```
order-events/
├─ run.bat / run.sh, run-tests.bat / run-tests.sh   start the app / the tests
├─ pom.xml                                           Maven parent (IntelliJ)
├─ backend/
│  ├─ pom.xml
│  ├─ src/com/cecil/sanket/
│  │  ├─ App.java              starts everything + background jobs
│  │  ├─ Platform.java         wires the broker, the dispatcher and the six consumer groups
│  │  ├─ broker/               EventBroker, Schemas, ConsumerGroup, Dispatcher (retries, dead letters), Handler
│  │  ├─ service/              OrderService (+ saga), OutboxRelay, InventoryService, PaymentService, ShippingService,
│  │  │                        NotificationService, OrderProjection (read model), AnalyticsService, AuthService
│  │  ├─ model/                Event, Order, OrderView, OutboxEntry, Product, Reservation, Payment, Shipment, DeadLetter …
│  │  ├─ sim/                  Simulator (shoppers, warehouse, supplier, faults)
│  │  ├─ store/                DataStore (memory + JSON files), SeedData (demo shop + 3½ days of history)
│  │  ├─ web/                  ApiServer (routes, CSRF), Http
│  │  └─ util/                 Json, Passwords, Clock, Stats, Text
│  └─ test/com/cecil/sanket/AllTests.java
├─ frontend/                   index.html, app.css (signal-box theme), app.js, theme.js, favicon.svg
└─ data/                       created on first start (not in git)
   └─ log/                     the event log: orders-0.ndjson … shipments-2.ndjson, dead-letter-0.ndjson
```

## Limits (honest)

* The broker runs **inside the same program** as the services. Real systems run Kafka (or RabbitMQ, Pulsar) as a
  separate cluster with copies of each partition on several machines. The ideas – topics, partitions, offsets,
  consumer groups, at-least-once delivery – are the same, and each service here only talks to the broker.
* The services share one `DataStore` object, but each only touches its own collections. In a real system each
  service would have its own database.
* Each service saves its data and its offset to separate files about once a second, not in one transaction. After a
  crash an event may be handled again – which is safe, because every consumer is idempotent.
* One partition is handled by one thread at a time. There is no consumer **rebalancing** between several copies of a
  service.
* There is no log **retention** or compaction: the log only grows. A few days of demo data is a few MB.
* Payments, couriers and customers are **simulated**. Nothing is really charged or shipped.
