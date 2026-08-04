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

- JDK 8 (Amazon Corretto 8 on Windows/macOS bundles JavaFX; on other JDK 8
  distributions you may need to add JavaFX separately, or upgrade the
  project's `maven.compiler.source/target` and add `javafx-controls` as an
  explicit dependency if you move to JDK 11+).
- Maven 3.6+ (a portable copy works fine — no admin/system install required).

## Build & test

```
mvn test
```

Runs all unit and integration tests (including real-socket integration
tests that start an actual server and connect real clients) across all three
modules.

## Run

Start the server first (creates `data/employees.json` and
`data/accounts.json` on first run, initially empty — you'll need at least
one ADMIN account before anything else can happen; see below):

```
mvn -pl server -am compile
java -cp server/target/classes;common/target/classes;<gson+poi jars on classpath> managestore.server.net.ServerMain
```

Then start the client (one process per employee logging in):

```
mvn -pl client -am compile
java -cp client/target/classes;common/target/classes;<gson jar on classpath> managestore.client.ClientMain
```

The easiest way to get correct classpaths without hand-assembling them is to
open the project in IntelliJ IDEA (File → Open → select the root
`pom.xml`) and run `ServerMain` / `ClientMain` directly from the IDE.

### Bootstrapping the first admin account

There's no seed data, and by design only an existing ADMIN can create new
employee accounts (see `EMPLOYEE_ADD_REQUEST` handling in `ClientHandler`)
— so the very first admin has to be created outside the normal client/server
flow. Run this once, from the same working directory the server will run
in, before starting the server for the first time:

```
java -cp server/target/classes;common/target/classes;<gson jar> managestore.server.net.BootstrapAdmin admin admin123 "System Administrator"
```

(or just run `BootstrapAdmin.main()` directly from IntelliJ). It writes
straight into `data/employees.json` / `data/accounts.json`. Log in with
username `admin` / password `admin123`, then use the Employees tab's
"Add Employee" form to create everyone else.
