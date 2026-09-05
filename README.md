# ManageStoreSystem

A client–server management system for a multi-branch retail chain, written in Java
over raw TCP sockets. Employees across branches share live inventory and customer
data, chat with each other through a mediator with a waiting queue, and generate
sales reports as JSON or as real Word documents.

## Highlights

- **Custom wire protocol** over plain TCP sockets — a line-framed JSON `Message`
  envelope (`MessageChannel` + `MessageType`), one thread per connection, no
  networking framework.
- **Eight design patterns applied with intent, not for their own sake** — Template
  Method, Observer, Mediator, Singleton, Factory, Strategy, Repository, Facade. See
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for where each one lives and why.
- **Concurrency handled at the point of shared state**, not everywhere: `synchronized`
  inventory mutation with an atomic overflow guard, a `putIfAbsent`-based session
  registry that makes duplicate-login rejection race-free, and a mediator whose
  internal queueing is provably deadlock-free under its own single monitor.
- **A two-root exception hierarchy** (`InvalidRequestException` vs.
  `StoreStateException`) that separates "the input is wrong" from "the input is fine
  but the system can't do that right now" — each carrying the failure's data as
  fields, not just a formatted string.
- **102 automated tests**, including full integration tests that open a real
  `ServerSocket`, connect real client sockets, and assert on the messages actually
  pushed back.
- **Real `.docx` generation** via Apache POI, delivered over the same JSON protocol
  as everything else (Base64-encoded).

## Architecture

Three Maven modules:

- **`common`** — the domain model (`Employee`, `Customer` hierarchy, `Product`,
  `Branch`, `Inventory`, …) and the client/server wire protocol. Shared by both other
  modules so client and server can never disagree about a message's shape — enforced
  by the Maven module graph, not by convention.
- **`server`** — networking (one thread per connection), authentication, live sync
  (Observer), cross-branch chat (Mediator + queue), reports (Strategy), logging.
- **`client`** — a JavaFX desktop app: a login screen and a tabbed main window
  (Inventory, Customers, Reports, Chat, Employees, and an admin-only System Log tab).

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full design write-up —
class hierarchy, every pattern's rationale, the protocol, and the trade-offs made
along the way.

## Tech stack

Java 8 · JavaFX · Maven (3-module reactor) · raw TCP sockets · Gson · Apache POI ·
JUnit 5

## Requirements

- JDK 8 that bundles JavaFX — Oracle's own JDK 8 included it, and most JDK 8 installs
  people already have on Windows/macOS do too. If running the client throws
  `NoClassDefFoundError: javafx/...`, your JDK 8 build doesn't include it — either
  switch to a free JDK 8 build that does, or add `javafx-controls`/`javafx-fxml` as
  explicit Maven dependencies and bump `maven.compiler.source/target` to 11+ instead.
- Maven 3.6+ (a portable copy works fine — no admin/system install required).

## Build & test

```
mvn test
```

Runs all 102 tests across the three modules — unit tests for the domain model,
services and validators, plus integration tests that start a real `ServerSocket`,
connect real client sockets, and assert on the messages actually pushed back (live
inventory sync, chat queueing, duplicate login, employee add/delete, logging, and
malformed-request handling).

## Run

The easiest way to get correct classpaths without hand-assembling them is to open the
project in IntelliJ IDEA (File → Open → select the root `pom.xml`) and run one of the
classes below directly from the IDE. To run from a terminal instead, first compile
everything and resolve each module's dependency jars into a classpath file:

```
mvn compile
mvn -pl server -am dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
mvn -pl client -am dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
```

Two notes on that command:

- **In PowerShell, quote the `-D` argument exactly as shown** (`"-Dmdep.outputFile=..."`)
  — otherwise PowerShell's own argument parsing mangles it before Maven sees it.
- **The output path is relative to each module being built**, so the one file with
  real content ends up at `server/target/cp.txt` / `client/target/cp.txt` (already
  covered by `.gitignore`, since it's inside `target/`).

Then, in every `java -cp` command below, `$SERVER_CP`/`$CLIENT_CP` means "the
contents of that file", loaded once per terminal session:

- **PowerShell:**
  ```
  $SERVER_CP = (Get-Content server/target/cp.txt -Raw).Trim()
  $CLIENT_CP = (Get-Content client/target/cp.txt -Raw).Trim()
  ```
- **bash / Git Bash (swap every `;` below for `:`):**
  ```
  SERVER_CP=$(cat server/target/cp.txt)
  CLIENT_CP=$(cat client/target/cp.txt)
  ```

### Quickest way to see it working: demo data

`DemoServerLauncher` starts the exact same server as `ServerMain`, but seeds two
branches, a small product catalog with starting stock, and four ready-to-use
accounts — so you can log in and click around immediately instead of bootstrapping
an admin and adding everything by hand first.

Start it, then start the client (one client process per employee logging in — open a
second terminal and re-run the client command to test chat or duplicate-login
rejection with two accounts at once):

```
java -cp "server/target/classes;common/target/classes;$SERVER_CP" managestore.server.net.DemoServerLauncher
java -cp "client/target/classes;common/target/classes;$CLIENT_CP" managestore.client.ClientMain
```

Log in with any of:

| Username  | Password    | Role           | Branch              |
|-----------|-------------|----------------|----------------------|
| `admin`   | `Admin1234` | ADMIN          | — (also sees System Log) |
| `seller1` | `Seller123` | SELLER         | Downtown Branch / B1 |
| `mgr1`    | `Manager123`| SHIFT_MANAGER  | Downtown Branch / B1 |
| `seller2` | `Seller123` | CASHIER        | Uptown Branch / B2   |

`seller1`/`mgr1` can buy/sell the seeded stock and see live inventory updates;
`admin` can add new employees and read the system log; two clients logged in as
employees at the same branch can chat with each other.

### Clean start: empty network, bootstrap your own admin

`ServerMain` is the real entry point: it starts with a completely empty network (no
branches, no products, no accounts), so every account is created through the normal
admin flow rather than skipped via seed data. The very first admin has to be created
outside the normal client/server flow, once, before starting the server for the
first time:

```
java -cp "server/target/classes;common/target/classes;$SERVER_CP" managestore.server.net.BootstrapAdmin admin admin123 "System Administrator"
```

(or just run `BootstrapAdmin.main()` directly from IntelliJ). It writes straight
into `data/employees.json` / `data/accounts.json`. Then start the server for real and
log in with username `admin` / password `admin123`:

```
java -cp "server/target/classes;common/target/classes;$SERVER_CP" managestore.server.net.ServerMain
java -cp "client/target/classes;common/target/classes;$CLIENT_CP" managestore.client.ClientMain
```

From there, use the Employees tab's "Add Employee" form to create everyone else —
but note that, unlike `DemoServerLauncher`, no branches exist yet either; a
freshly-added employee's Inventory tab has nothing to show until a branch with that
same ID is added to the in-memory `StoreChain` (see `DemoServerLauncher.seedStoreChain()`
for the shape of the calls that would need to be exposed through a UI).
