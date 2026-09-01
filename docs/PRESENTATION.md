# Presentation walkthrough

A script for presenting this project, organized as: what to say, what to
show, and what to be ready to be asked about. Pair with
[ARCHITECTURE.md](ARCHITECTURE.md) for the full design rationale.

## 1. One-sentence pitch

"A client-server store-chain management system in Java: multiple branches
share live inventory and customer data over sockets, support cross-branch
chat, and produce sales reports in JSON and Word."

## 2. The three OOP points to lead with

These are the ones the brief is most directly testing. Know these cold.

### a) `Customer` is polymorphic, not an if/else on a type field

`common/model/Customer.java` is abstract; `NewCustomer`, `ReturningCustomer`,
`VIPCustomer` each override `applyDiscount(double)`. `purchase()` is a
**Template Method** — the steps (check stock, compute total, apply discount,
decrement inventory) are fixed and identical for every customer type; only
the discount step varies, via the override. Show `CustomerTest.java` — same
`purchase()` call, three different discount outcomes, no branching.

### b) Observer pattern for live sync

`Inventory` and `CustomerDirectory` are Subjects (`addObserver`/
`removeObserver`/notify-on-change). Every logged-in client's `ClientHandler`
registers itself as an Observer on its own branch's inventory and the
network-wide customer directory. Show `LiveSyncIntegrationTest.java`: two
real sockets, Cashier A buys something, Seller B — who did nothing — gets
the new stock count pushed to their socket automatically.

### c) Mediator + queue for chat

`ChatMediator` is the only thing that knows who's connected/busy; employees
never reference each other. A per-branch `BlockingQueue<ChatRequest>` holds
requests that couldn't be matched to a free employee. When someone becomes
free, the mediator pops the oldest queued request (FIFO) and tells *that
employee* who tried to reach them, so they can call back — this exact
behavior is what the brief's self-study section asked for. Show
`ChatMediatorTest.java`'s `freedEmployeeIsNotifiedOfQueuedRequestAndCanCallBack`.

## 3. Requirement → implementation map

| Brief requirement | Where it lives |
|---|---|
| Login screen + auth | `LoginScreen` (client), `AuthService` + `SessionManager` (server) |
| Admin screen, employee accounts, password policy | `EmployeesPanel`'s add-employee form (admin-only), `PasswordPolicy`, `EmployeeAddRequest` handling in `ClientHandler` |
| Per-branch inventory synced live | `Inventory` (Observer), `InventoryPanel` |
| Network-wide customer list synced live | `CustomerDirectory` (Observer), `CustomersPanel` |
| Different class per customer type | `Customer` hierarchy — see §2a |
| Per-branch inventory: purchase (restock) and sale | `InventoryPanel`'s Sell/Restock buttons, `PurchaseService.purchase`/`.restock` |
| Sales reports by branch/product/category, JSON + Word, optionally by day | `ReportService`, `ReportExporter` (Strategy) → `JsonReportExporter` / `WordReportExporter` (Apache POI); `ReportRequest.day` for the daily-report filter |
| Employee management | `EmployeeRepository`, `EmployeesPanel` |
| Cross-branch chat with queueing + callback | `ChatMediator` — see §2c |
| Shift manager joins existing chat | `ChatMediator.joinChat`, `ChatPanel`'s join form |
| No duplicate login from multiple computers | `SessionManager` (Singleton) — `tryLogin` rejects a second session for the same username |
| System log by action type, optional chat save | `LogManager` (Singleton), `LogsPanel` (admin-only); chat transcripts included in the `CHAT` log entry's details |

## 4. Design patterns — full list to be ready to name

- **Polymorphism / Template Method** — `Customer` hierarchy (§2a)
- **Observer** — `Inventory`, `CustomerDirectory` (§2b)
- **Mediator** — `ChatMediator` (§2c)
- **Singleton** — `SessionManager`, `LogManager` (one shared instance is the
  entire point: "is this user already logged in" / "the system log" only
  make sense as one process-wide source of truth)
- **Strategy** — `ReportExporter` (`JsonReportExporter` / `WordReportExporter`
  implement the same interface, produce different output formats)
- **Factory** — `CustomerFactory` maps a `CustomerType` to the right
  `Customer` subclass in one place

## 5. Live demo script

1. Run `BootstrapAdmin`, then start `ServerMain`.
2. Launch two `ClientMain` instances. Log into one as `admin`.
3. In the Employees tab, add two employees at different branches (e.g.
   `BRANCH-1` and `BRANCH-2`), one a SELLER and one a SHIFT_MANAGER.
4. Log the second client in as one of those new employees.
5. **Inventory sync**: on the admin client (or a third client on the same
   branch), watch the Inventory tab update live when the other client sells
   a product — no refresh needed.
6. **Chat**: from one client, request a chat targeting the other's branch;
   send messages back and forth; end the chat.
7. **Chat queueing**: with the target branch's only employee already busy in
   another chat, send a third request — show it gets queued, then ends the
   busy chat and shows the free-employee gets a callback notice.
8. **Reports**: generate a report by branch as WORD format, save it, open
   the resulting `.docx` to show it's a real Word document with a table.
9. **Logs**: on the admin client's System Log tab, show the
   EMPLOYEE_REGISTERED / SALE / CHAT entries that were just generated.

## 6. Things to be ready to defend

- **Why no database?** Deliberate scope decision (documented in
  ARCHITECTURE.md §10) — JSON-file repositories behind a `Repository`
  interface, so the domain/service layers don't know or care; a real DB
  could replace `JsonFileEmployeeRepository` without touching anything else.
  Sales history and the system log are in-memory only, which is also a
  documented, deliberate cut (`SalesRecordRepository`, `LogManager`).
- **Why not persist `Customer`/`SalesRecord` to JSON?** `Customer` is
  polymorphic (see §2a) — Gson can serialize a concrete instance fine, but
  can't reliably deserialize back into "the right subclass" from JSON alone
  without extra machinery. Documented in `CustomerDto`'s and
  `SalesRecordRepository`'s javadoc.
- **Why one thread per client connection?** Standard, simple model for a
  socket server at this scale; each `ClientHandler` only ever touches its
  own socket, so there's no shared mutable state to protect at the
  connection level — all the actual shared state (`Inventory`,
  `CustomerDirectory`, `SessionManager`, `ChatMediator`) is protected by its
  own synchronization (`synchronized`, `ConcurrentHashMap`, etc.), which is
  the layer worth explaining if asked about thread safety.
- **Why does the actor also get inventory updates via the push channel
  instead of the response?** Uniformity — every branch employee, including
  whoever made the sale, learns the new stock level exactly the same way,
  through the Observer notification, not a special case baked into the
  response. See the comment in `LiveSyncIntegrationTest`.
