# ManageStoreSystem

Store chain (clothing) management system — client/server Java project for the
Algorithmic Development course, HIT, Summer 2026 (instructor: Roi Zimon).

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full design write-up
(class hierarchy, design patterns, protocol) and
[docs/PRESENTATION.md](docs/PRESENTATION.md) for a walkthrough script to use
when presenting this project.

## Modules

- `common` — domain model (`Employee`, `Customer` hierarchy, `Product`,
  `Branch`, `Inventory`, ...) and the client/server wire protocol. Shared by
  both other modules so client and server can never disagree about a
  message's shape.
- `server` — networking (one thread per connection), auth, live sync
  (Observer), chat (Mediator + queue), reports (Strategy), logging.
- `client` — JavaFX desktop app: login screen + a tabbed main window
  (Inventory, Customers, Reports, Chat, Employees, and an admin-only System
  Log tab).

## Requirements

- JDK 8 that bundles JavaFX — Oracle's own JDK 8 included it, and most JDK 8
  installs people already have on Windows/macOS do too, so this usually
  needs no extra setup. If running the client throws
  `NoClassDefFoundError: javafx/...`, your JDK 8 build doesn't include it —
  either switch to a free JDK 8 build that does (search "JDK 8 with
  JavaFX" — several vendors offer one, no account or paid license needed),
  or add `javafx-controls`/`javafx-fxml` as explicit Maven dependencies and
  bump `maven.compiler.source/target` to 11+ instead.
- Maven 3.6+ (a portable copy works fine — no admin/system install required).

## Build & test

```
mvn test
```

Runs all unit and integration tests (including real-socket integration
tests that start an actual server and connect real clients) across all three
modules.

## Run

The easiest way to get correct classpaths without hand-assembling them is to
open the project in IntelliJ IDEA (File → Open → select the root
`pom.xml`) and run one of the classes below directly from the IDE. To run
from a terminal instead, first compile everything and resolve each module's
dependency jars into a classpath file — one-time setup, only needs
repeating after you add/change a dependency in a `pom.xml`:

```
mvn compile
mvn -pl server -am dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
mvn -pl client -am dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
```

Two things about that command that look pedantic but each cost real time to
track down, so they're worth calling out explicitly:

- **In PowerShell, the `-Dproperty=value` argument must be quoted as shown**
  (`"-Dmdep.outputFile=..."`), or PowerShell's own argument parsing mangles
  it before Maven ever sees it and you get a confusing "Unknown lifecycle
  phase" error.
- **The output path is relative to each module being built, not to your
  current directory** — `-pl server -am` builds the root pom, `common`, and
  `server` in sequence, and each one runs `build-classpath` again in its own
  directory (harmlessly finding nothing to write for the root pom and
  `common`, since neither has runtime dependencies of its own). Using
  `target/cp.txt` means the one file with real content always ends up at
  `server/target/cp.txt` / `client/target/cp.txt` — already covered by
  `.gitignore` since it's inside `target/`, so it's never at risk of being
  committed by accident.

Then, in every `java -cp` command below, `$SERVER_CP`/`$CLIENT_CP` means
"the contents of that file", loaded once per terminal session:

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

`DemoServerLauncher` starts the exact same server as `ServerMain`, but seeds
two branches, a small product catalog with starting stock, and four ready-to-use
accounts — so you can log in and click around immediately instead of
bootstrapping an admin and adding everything by hand first.

Start it, then start the client (one client process per employee logging in
— open a second terminal and re-run the client command to test chat or
duplicate-login rejection with two accounts at once):

```
java -cp "server/target/classes;common/target/classes;$SERVER_CP" managestore.server.net.DemoServerLauncher
java -cp "client/target/classes;common/target/classes;$CLIENT_CP" managestore.client.ClientMain
```

(PowerShell also expands `$SERVER_CP`/`$CLIENT_CP` inside a double-quoted
string exactly like this: it's a real PowerShell variable, not a template
placeholder — the `java -cp "...;$SERVER_CP"` line works unmodified in both
shells shown above.)

Log in with any of:

| Username  | Password    | Role           | Branch              |
|-----------|-------------|----------------|----------------------|
| `admin`   | `Admin1234` | ADMIN          | — (also sees System Log) |
| `seller1` | `Seller123` | SELLER         | Downtown Branch / B1 |
| `mgr1`    | `Manager123`| SHIFT_MANAGER  | Downtown Branch / B1 |
| `seller2` | `Seller123` | CASHIER        | Uptown Branch / B2   |

`seller1`/`mgr1` can buy/sell the seeded stock and see live inventory
updates; `admin` can add new employees and read the system log; two clients
logged in as employees at the same branch can chat with each other.

### Production / graded flow: empty network, bootstrap your own admin

`ServerMain` is the real entry point: it starts with a completely empty
network (no branches, no products, no accounts) so the brief's "admin screen
defines employee accounts" flow is exercised for real, not skipped via seed
data. There's no seed data by design, and only an existing ADMIN can create
new employee accounts (see `EMPLOYEE_ADD_REQUEST` handling in
`ClientHandler`) — so the very first admin has to be created outside the
normal client/server flow, once, before starting the server for the first
time:

```
java -cp "server/target/classes;common/target/classes;$SERVER_CP" managestore.server.net.BootstrapAdmin admin admin123 "System Administrator"
```

(or just run `BootstrapAdmin.main()` directly from IntelliJ). It writes
straight into `data/employees.json` / `data/accounts.json`. Then start the
server for real and log in with username `admin` / password `admin123`:

```
java -cp "server/target/classes;common/target/classes;$SERVER_CP" managestore.server.net.ServerMain
java -cp "client/target/classes;common/target/classes;$CLIENT_CP" managestore.client.ClientMain
```

From there, use the Employees tab's "Add Employee" form to create everyone
else — but note that, unlike `DemoServerLauncher`, no branches exist yet
either; a freshly-added employee's Inventory tab has nothing to show until a
branch with that same ID is added to the in-memory `StoreChain` (there's no
running UI for that yet — see `DemoServerLauncher.seedStoreChain()` for the
shape of the calls that would need to be exposed).
