# ManageStoreSystem

A desktop app for managing a clothing store chain with several branches. It runs as a
server plus multiple client windows that talk to it over the network, so employees at
different branches all work against the same live data.

**Java 8 · JavaFX · Maven · TCP sockets · Gson · Apache POI · JUnit 5**

## What it does

- **Inventory per branch.** Each branch has its own stock. Employees sell products to
  customers and restock them from the supplier.
- **Everything updates live.** When one employee makes a sale, every other employee at
  that branch sees the new stock count immediately — no refresh button.
- **Chat between branches.** An employee can ask to talk to whoever is free at another
  branch. If everyone there is busy, the request waits in line, and the first person to
  free up is told who was looking for them.
- **Different roles see different things.** An admin manages employee accounts and reads
  the system log; sellers and cashiers only see their own branch.
- **Sales reports.** By branch, product, or category, optionally for a single day.
  Exported as JSON or as a real Word document.
- **A log of everything.** Every new employee, new customer, sale, restock, and chat is
  recorded, including the full text of each conversation.

## How it's built

Three Maven modules:

| Module | What's in it |
|---|---|
| `common` | The data model (employee, customer, product, branch) and the message format both sides use to talk |
| `server` | Networking, business logic, saving to disk, logging |
| `client` | The JavaFX windows the user actually clicks on |

The client and the server never depend on each other — both depend only on `common`.
That way the two sides can't disagree about what a message looks like.

A few choices worth knowing about:

- **The network layer is written from scratch**, not taken from a framework: each message
  is one line of JSON sent over a socket, and the server gives every connected client its
  own thread.
- **Each customer type is its own class.** A new customer, a returning customer, and a VIP
  each calculate their discount differently, so instead of one class with `if` statements
  checking the type, there are three classes that each override one method.
- **Locking only where it's actually needed** — the places more than one client can touch
  at the same time: stock levels, the list of who's logged in, and the chat system.
- **Errors have their own classes** instead of one generic error with different text, so
  the code can tell "you typed something invalid" apart from "the store can't do that
  right now."

For the full design write-up — the class hierarchy, every design pattern and where it
lives, and the trade-offs made — see **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

## Running it

You need JDK 8 that includes JavaFX, and Maven 3.6+.

The easiest way: open the root `pom.xml` in IntelliJ IDEA, run `DemoServerLauncher`, then
run `ClientMain`. The demo launcher creates two branches, a product catalog, and four
accounts, so there's data to work with straight away.

| Username | Password | Role | Branch |
|---|---|---|---|
| `admin` | `Admin1234` | Admin | — (sees the System Log) |
| `mgr1` | `Manager123` | Shift manager | Downtown / B1 |
| `seller1` | `Seller123` | Seller | Downtown / B1 |
| `seller2` | `Seller123` | Cashier | Uptown / B2 |

Run `ClientMain` a second time to log in as a second employee — that's how to see stock
syncing between two windows, or chat between two branches.

To run the tests: `mvn test`.

Running from a terminal instead of an IDE, and starting from an empty system rather than
demo data, are both covered in **[docs/RUNNING.md](docs/RUNNING.md)**.
