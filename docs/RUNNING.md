# Running from a terminal

The simplest path is IntelliJ IDEA (open the root `pom.xml`, run the classes
directly) — see the README. This document covers running from a terminal, and
starting from an empty network instead of demo data.

## Prerequisites

- **JDK 8 that bundles JavaFX.** Oracle's own JDK 8 included it, as do most JDK 8
  installs already on Windows/macOS. If the client throws
  `NoClassDefFoundError: javafx/...`, your build doesn't include it — either switch to
  a free JDK 8 build that does, or add `javafx-controls`/`javafx-fxml` as explicit
  Maven dependencies and bump `maven.compiler.source/target` to 11+.
- **Maven 3.6+** — a portable copy works; no system install needed.

## Building a classpath

Compile, then resolve each module's dependency jars into a classpath file. This is
one-time setup, only repeated after changing a dependency in a `pom.xml`:

```
mvn compile
mvn -pl server -am dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
mvn -pl client -am dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
```

Two notes on that command:

- **In PowerShell, quote the `-D` argument exactly as shown** (`"-Dmdep.outputFile=..."`),
  or PowerShell's argument parsing mangles it before Maven sees it.
- **The output path is relative to each module being built**, so the file with real
  content lands at `server/target/cp.txt` and `client/target/cp.txt` — already ignored
  by git, being inside `target/`.

Load them once per terminal session:

```powershell
# PowerShell
$SERVER_CP = (Get-Content server/target/cp.txt -Raw).Trim()
$CLIENT_CP = (Get-Content client/target/cp.txt -Raw).Trim()
```

```bash
# bash / Git Bash — also swap every ';' below for ':'
SERVER_CP=$(cat server/target/cp.txt)
CLIENT_CP=$(cat client/target/cp.txt)
```

## With demo data

`DemoServerLauncher` runs the same server as `ServerMain`, but seeds two branches, a
product catalog with starting stock, demo customers, a few days of sales history, and
four accounts:

```
java -cp "server/target/classes;common/target/classes;$SERVER_CP" managestore.server.net.DemoServerLauncher
java -cp "client/target/classes;common/target/classes;$CLIENT_CP" managestore.client.ClientMain
```

Run the client command in a second terminal to sign in as another employee — that's
how to exercise live inventory sync, cross-branch chat, and duplicate-login rejection.

Accounts and roles are listed in the README.

## From an empty network

`ServerMain` is the real entry point and starts with nothing — no branches, products,
or accounts — so every account gets created through the normal admin flow. Since only
an existing admin can create employee accounts, the first one has to be created out of
band, once:

```
java -cp "server/target/classes;common/target/classes;$SERVER_CP" managestore.server.net.BootstrapAdmin admin admin123 "System Administrator"
```

It writes directly into `data/employees.json` and `data/accounts.json`. Then start the
server and sign in as `admin` / `admin123`:

```
java -cp "server/target/classes;common/target/classes;$SERVER_CP" managestore.server.net.ServerMain
java -cp "client/target/classes;common/target/classes;$CLIENT_CP" managestore.client.ClientMain
```

From there the Employees tab creates everyone else. Note that no branches exist yet
either, so a newly added employee's Inventory tab stays empty until a branch with the
same ID is added to the in-memory `StoreChain` — see `DemoServerLauncher.seedStoreChain()`
for the shape of the calls a branch-management UI would need to expose.
