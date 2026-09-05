# ManageStoreSystem

A desktop client–server system for managing a multi-branch clothing retail chain,
built in Java on raw TCP sockets. Branches share live inventory and customer data,
employees chat across branches through a routed queue, and managers export sales
reports as JSON or Word documents.

**Java 8 · JavaFX · Maven · TCP sockets · Gson · Apache POI · JUnit 5 · 102 tests**

## What it does

- **Live sync across clients** — a sale on one machine updates every other employee's
  inventory table at that branch instantly, pushed over the socket rather than polled.
- **Cross-branch chat with a waiting queue** — an employee requests any free colleague
  at another branch; if everyone is busy the request queues, and whoever frees up first
  is told who was trying to reach them.
- **Role-based access** — admins manage employee accounts and read the system audit
  log; sellers and cashiers see only their own branch.
- **Sales reports** — grouped by branch, product, or category, filterable to a single
  day, exported as JSON or as a real `.docx`.
- **Audited** — every registration, sale, restock, and chat is logged, with chat
  transcripts saved in full.

## How it's built

Three Maven modules: `common` (domain model + wire protocol), `server` (networking,
business logic, persistence), `client` (JavaFX app). The client and server never
depend on each other — only on `common` — so they can't drift apart on the shape of
a message.

A few decisions worth calling out:

- **A hand-rolled protocol** rather than a framework: line-framed JSON over sockets,
  one thread per connection, with a single enum defining every legal message type.
- **Polymorphism over conditionals** for customer pricing — each customer tier is its
  own class overriding one method, behind a `final` Template Method so no subclass can
  skip the stock check.
- **Synchronization only where state is genuinely shared** — inventory mutation, the
  session registry (`putIfAbsent`, so duplicate logins can't race), and the chat
  mediator.
- **Failures are typed, not stringly** — a two-root exception hierarchy separating
  "your input is invalid" from "the store can't do that right now," each carrying the
  failure's data as fields.

Full write-up with class diagrams, all eight design patterns, and the trade-offs:
**[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**

## Running it

Requires JDK 8 with JavaFX bundled and Maven 3.6+.

```
mvn test          # 102 tests, including real-socket integration tests
mvn compile
```

Open the root `pom.xml` in IntelliJ IDEA and run `DemoServerLauncher`, then
`ClientMain` — the demo launcher seeds two branches, a product catalog, and four
accounts so there's data to work with immediately.

| Username  | Password    | Role          |
|-----------|-------------|---------------|
| `admin`   | `Admin1234` | Admin         |
| `mgr1`    | `Manager123`| Shift manager |
| `seller1` | `Seller123` | Seller        |
| `seller2` | `Seller123` | Cashier       |

Run `ClientMain` a second time to log in as another employee and watch inventory sync
or chat between them.

Terminal instructions, and how to start from an empty network instead of demo data,
are in **[docs/RUNNING.md](docs/RUNNING.md)**.
