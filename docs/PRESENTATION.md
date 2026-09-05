# Presentation walkthrough

A script for presenting this project: what to say, what to show, and what to be ready
to be asked. Pair with [ARCHITECTURE.md](ARCHITECTURE.md) for the full design rationale.

## 1. One-sentence pitch

"A client–server store-chain management system in Java: multiple branches share live
inventory and customer data over TCP sockets, employees chat across branches through a
mediator with a waiting queue, and the system produces sales reports as JSON and as
real Word documents."

## 2. The three OOP points to lead with

These are what the brief is most directly testing. Know them cold.

### a) `Customer` is polymorphic, not an if/else on a type field

`common/model/Customer.java` is abstract; `NewCustomer`, `ReturningCustomer` and
`VIPCustomer` each override `applyDiscount(double)`. `purchase(...)` is **`final`** — a
**Template Method** whose steps (check quantity → check stock → compute list total →
apply the subclass's discount → decrement stock) are identical for every customer type;
only the discount step varies. A subclass physically cannot skip the stock check.

**Show:** `CustomerTest` — the same `purchase()` call, three different charged amounts,
zero branching on type.

### b) Observer for live sync

`Inventory` and `CustomerDirectory` are Subjects (`addObserver` / `removeObserver` /
notify-on-change). Every logged-in client's `ClientHandler` registers itself as an
observer of its own branch's inventory and of the network-wide customer directory, and
unregisters on disconnect.

**Show:** `LiveSyncIntegrationTest` — two real sockets; Cashier A buys something and
Seller B, who did nothing, receives the new stock count pushed to their socket.

### c) Mediator + FIFO queue for chat

`ChatMediator` is the only object that knows who is connected, busy, or waiting;
employees never reference each other. A per-branch `BlockingQueue<ChatRequest>` holds
requests that couldn't be matched. When someone frees up, the mediator pops the oldest
request and tells *that employee* who tried to reach them, so they can call back.

**Show:** `ChatMediatorTest.freedEmployeeIsNotifiedOfQueuedRequestAndCanCallBack`.

## 3. Requirement → implementation map

| Brief requirement | Where it lives |
|---|---|
| Client–server system | `ServerMain` + `ClientHandler` (thread per connection) ↔ `ServerConnection`; `MessageChannel` + `MessageType` protocol |
| Login screen with authentication | `LoginScreen`, `AuthService`, `PasswordHasher` (salted, 100k-iteration) |
| Admin screen: employee accounts + password policy | `EmployeesPanel` add/delete forms (admin-only), `PasswordPolicy`, `EMPLOYEE_ADD_REQUEST` / `EMPLOYEE_DELETE_REQUEST` in `ClientHandler` (role re-checked server-side) |
| Info shown per the logged-in user's branch | `ClientHandler` subscribes only to *that employee's* branch inventory; `MainWindow` shows the System Log tab only to ADMIN |
| Per-branch inventory, live for all its employees | `Inventory` (Observer Subject), `InventoryPanel` |
| Buy (restock) and sell products | `InventoryPanel`'s Sell / Restock buttons → `PurchaseService.purchase` / `.restock` |
| Network-wide customer list, live for everyone | `CustomerDirectory` (Observer Subject), `CustomersPanel` |
| Customer details: name, ID, phone, type | `CustomerDto` / `CustomersPanel` table columns |
| A different class per customer type, each with its own deal | `Customer` hierarchy — see §2a |
| Purchase applies the customer's own track | `Customer.purchase` Template Method — see §2a |
| Reports: sales per branch | `ReportService` + `ReportScope.BRANCH` |
| Reports: by product / category, daily | `ReportScope.PRODUCT` / `.CATEGORY`, `ReportRequest.day` |
| Reports sent as JSON | whole protocol is JSON; `JsonReportExporter` for the report body |
| Reports viewable as a Word document | `WordReportExporter` (Apache POI) → client's "Save as Word…" |
| Employee management (all 7 fields) | `Employee`, `EmployeesPanel` table (employee #, name, personal ID, phone, account #, branch, role) |
| Chat: employee ↔ employee across branches | `ChatMediator.requestChat`, `ChatPanel` |
| Shift manager joins an existing chat | `ChatMediator.joinChat` (role-gated), `ChatPanel`'s join bar |
| Queue searches for a free employee | `ChatMediator.findFreeEmployeeAtBranch` |
| If nobody is free, server keeps the waiting list | per-branch `BlockingQueue<ChatRequest>` in `ChatMediator` |
| Freed employee is notified and can call back | `notifyIfQueuedRequestWaiting` → `CHAT_FREE_NOTICE` → "Call back" button → `requestDirectChat` |
| No simultaneous session from two computers | `SessionManager.tryLogin` (atomic `putIfAbsent`) |
| System log by action type | `LogManager`, `LogType`, admin-only `LogsPanel` with a type filter |
| — employee registration | `LogType.EMPLOYEE_REGISTERED` |
| — customer registration | `LogType.CUSTOMER_REGISTERED` |
| — purchases / sales | `LogType.PURCHASE` / `LogType.SALE` |
| — chat details + option to save the conversation | `LogType.CHAT`, whose entry stores the full transcript |

## 4. Design patterns — the full list to be ready to name

- **Template Method + polymorphism** — `Customer.purchase` (final) over `applyDiscount` (§2a)
- **Observer** — `Inventory`, `CustomerDirectory` (§2b)
- **Mediator** — `ChatMediator` (§2c)
- **Singleton** — `SessionManager`, `LogManager`
- **Factory** — `CustomerFactory`
- **Strategy** — `ReportExporter` → `JsonReportExporter` / `WordReportExporter`
- **Repository** — `EmployeeRepository` / `AccountRepository` interfaces over JSON-file implementations
- **Facade** — `ServerContext`

## 5. Live demo script

Fastest path — `DemoServerLauncher` seeds two branches, six products, four customers
and a few days of sales, so there is data to show immediately (see README for the exact
run commands). Log in as `admin / Admin1234`, and a second client as
`seller1 / Seller123`.

1. **Login + validation** — try logging in with an empty password (specific message),
   then a wrong one ("Invalid username or password" — and explain in §6 why it doesn't
   say *which* was wrong). Use the 👁 button to show the typed password briefly.
2. **Duplicate login** — try `seller1` in a third client while it's already signed in →
   refused, "already logged in on another computer."
3. **Inventory + live sync** — with two clients on branch B1, sell from one and watch
   the other's table update with no refresh. Point out the amber low-stock rows.
4. **Customer types** — sell the same product to a NEW vs a VIP customer and compare
   the charged amounts; that difference is the polymorphism, not an `if`.
5. **Validation** — add a customer with personal ID `123` → rejected by the real Israeli
   ID checksum, with the reason shown inline on the form.
6. **Admin: add + delete an employee** — show the form is invisible to the seller
   client, add one, then delete it (confirmation dialog; and note you can't delete
   yourself).
7. **Chat** — request a chat from one client to the other's branch, exchange messages,
   end it. Then, with the target branch's employees all busy, show a third request
   getting **queued**, and the **callback notice** appearing when someone frees up.
8. **Reports** — generate by branch, by category, and with a day filter; switch format
   to WORD, click "Save as Word…", and open the resulting `.docx` to prove it's a real
   Word file with a table.
9. **System Log** — on the admin client, show the log filtered by type: the employee and
   customer registrations, the sale, the restock, and the chat entry **containing the
   full transcript**.
10. **Tests** — finish with `mvn test`: 102 tests, including real-socket integration tests.

## 6. Things to be ready to defend

- **Why does login not say whether the username or the password was wrong?**
  Deliberate. Distinguishing them lets an attacker enumerate valid usernames. The
  generic message is standard practice; the code comment in `AuthService.login` says so.
- **Why no database?** A documented scope decision. JSON files sit behind repository
  interfaces, so services never know the difference and a real DB could replace them.
- **Why aren't customers/sales persisted?** `Customer` is polymorphic; Gson can
  serialize a concrete instance but can't know which subclass to rebuild without extra
  type machinery. Not required to survive a restart, so it wasn't added.
- **Why one thread per connection?** Standard and simple at this scale. Each
  `ClientHandler` touches only its own socket; all genuinely shared state (`Inventory`,
  `CustomerDirectory`, `SessionManager`, `ChatMediator`) carries its own synchronization
  — that's the layer to discuss if asked about thread safety.
- **Why does the seller who made the sale also learn the new stock via a push?**
  Uniformity: everyone in the branch learns it the same way, through the Observer
  notification, rather than special-casing "also tell the sender" into the response.
- **What happens if a client sends garbage?** `dispatchSafely` turns any unexpected
  exception into an `ERROR` reply and keeps the session alive — proven by
  `MalformedRequestResilienceIntegrationTest`.
- **Known limits** — see ARCHITECTURE.md §14: no edit-in-place, branches/products seeded
  in code, a deleted employee's *current* session isn't force-closed, and
  employee-number uniqueness is check-then-act rather than atomic.
