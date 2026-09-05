# Architecture

This document describes the system **as built**: every class named here exists in the
repository, matched to the design decision behind it.

## 1. High-level shape

Client–server over plain TCP sockets, as three Maven modules:

```
ManageStoreSystem/
  common/   shared domain model + wire protocol (used by BOTH client and server)
  server/   networking, business logic, persistence, logging
  client/   JavaFX desktop app: screens + a thin networking layer
```

`common` exists because the client and server must agree on the exact shape of every
`Employee`, `Customer`, `Product`, and `Message` crossing the socket. One shared module
instead of duplicated classes means the two sides can never drift apart — a
compile-time guarantee rather than a convention.

**Dependency direction:** `client → common ← server`. The client and server never
depend on each other; both depend only on `common`. Enforced by the Maven module
graph, not by discipline.

## 2. Domain model (`common/model`)

```
Employee                employeeNumber, fullName, personalId, phone,
                        accountNumber, branchId, role
Role (enum)             ADMIN, SHIFT_MANAGER, CASHIER, SELLER

Customer (abstract)     personalId, fullName, phone
  ├── NewCustomer       no discount (pays list price)
  ├── ReturningCustomer 5% loyalty discount
  └── VIPCustomer       15% discount, then a flat 10 perk credit, floored at 0
  abstract String getCustomerType()
  abstract double applyDiscount(double amount)
  final    PurchaseResult purchase(Product, int quantity, Inventory)

Product                 sku, name, category, price
Branch                  id, name, Inventory inventory, List<Employee> staff
Inventory               Map<Product,Integer> stock  (per branch) + InventoryObserver list
CustomerDirectory       Map<String,Customer> by personal id (network-wide) + observers
StoreChain              branches + product catalog + the one CustomerDirectory
SalesRecord             branchId, product, customer, quantity, amountCharged, timestamp
LogEvent / LogType      one audit entry + its category
```

### Why a `Customer` class hierarchy instead of one class with a type field

Each customer type gets its own class rather than a `type` field checked with
conditionals — textbook **polymorphism over conditionals**: rather than
`if (type == VIP) … else if (type == RETURNING) …`, each subclass overrides
`applyDiscount(double)`. `PurchaseService.purchase(...)` calls
`customer.purchase(product, qty, inventory)` and never learns which subclass it holds.

`Customer.purchase(...)` is deliberately **`final`** and is a **Template Method**: the
steps (validate quantity → check stock → compute list total → apply the subclass's
discount → decrement stock → build a `PurchaseResult`) are fixed and identical for
every customer type. Only the discount step varies. A subclass cannot accidentally
change the order of operations or skip the stock check — it can only fill in the one
hole the template leaves open.

## 3. Design patterns actually used

| Pattern | Class(es) | Why it's here |
|---|---|---|
| **Template Method + polymorphism** | `Customer.purchase` (final) + `applyDiscount` overrides in `NewCustomer` / `ReturningCustomer` / `VIPCustomer` | a different class per customer type, each with its own purchase track |
| **Observer** | `Inventory` and `CustomerDirectory` are Subjects; `InventoryObserver` / `CustomerDirectoryObserver` are the interfaces; each logged-in `ClientHandler` registers itself | inventory and customer changes propagate live to every relevant employee |
| **Mediator + FIFO queue** | `ChatMediator` + per-branch `BlockingQueue<ChatRequest>` | cross-branch chat routing, queueing when nobody is free, notifying on free-up |
| **Singleton** | `SessionManager`, `LogManager` | "one user, one session" and "one system log" only make sense as a single process-wide source of truth |
| **Factory** | `CustomerFactory` | maps a `CustomerType` to the right subclass in exactly one place |
| **Strategy** | `ReportExporter` ← `JsonReportExporter`, `WordReportExporter` | the same report data rendered as JSON or as a real `.docx` |
| **Repository** | `EmployeeRepository`, `AccountRepository` interfaces + their `JsonFile…` implementations | storage is swappable (JSON files today, a DB later) without touching services |
| **Facade** | `ServerContext` | one object carrying the shared server state each `ClientHandler` thread needs |

## 4. Networking and protocol

- **Wire format:** one JSON `Message` per line — `{"type": …, "payload": …}` — via
  Gson. `MessageChannel` wraps a socket and does the line-framing and (de)serialization
  for both sides, so client and server share identical transport code.
- **`MessageType`** is the single enum listing every legal message. Adding a feature
  means adding a request/response pair here, which makes the whole protocol surface
  readable in one file.
- **`ServerMain`** opens a `ServerSocket` and hands each accepted connection to its own
  `ClientHandler` thread from a cached thread pool — the standard one-thread-per-client
  socket-server shape.
- **`ClientHandler`** loops on `receive()` and dispatches by `MessageType`. On
  successful login it registers itself as an observer of its branch's `Inventory` and
  of the network-wide `CustomerDirectory`, so it receives pushes without polling, and
  unregisters in a `finally` block on disconnect.
- **Client side:** `ServerConnection` runs one background reader thread and dispatches
  each incoming message to listeners registered per `MessageType`, always via
  `Platform.runLater` so screens can touch JavaFX nodes directly. There is no
  request/response correlation id, and a push can legitimately arrive interleaved with
  a reply — an event-driven listener model has no ordering assumptions to get wrong.

### Robustness: one bad request must not kill a session

`dispatchSafely` wraps every dispatch in a `catch (RuntimeException)`, reports the
failure back to that client as an `ERROR` message, and keeps the connection alive. A
malformed payload or an invalid enum value costs the client one rejected request, not
their whole session. `ClientMain` registers a single global `ERROR` listener that
surfaces those as a dialog, so no server-side rejection can fail silently.

## 5. Authentication, sessions, and the duplicate-login rule

Three separate classes, deliberately not merged:

- **`AuthService`** — validates credentials and creates/deletes accounts. It returns
  the same generic *"Invalid username or password"* for both an unknown username and a
  wrong password, so a caller cannot use the response to enumerate valid usernames.
- **`PasswordHasher`** — salted SHA-256, iterated 100,000 times. A single hash round is
  fast enough to brute-force on a GPU if `accounts.json` ever leaks; iterating makes
  each guess proportionally expensive. No plaintext password is ever stored.
- **`SessionManager`** (Singleton) — `Map<username, sessionId>`, and `tryLogin` uses
  `putIfAbsent`, so the duplicate-login check is **atomic**: two simultaneous logins for
  the same username can never both win.

`AuthService` deliberately does **not** touch `SessionManager`. "Are these credentials
correct?" and "is this user allowed to open a session right now?" are two different
questions; `ClientHandler.handleLogin` asks them in order. Keeping them apart means
each is independently testable, which is why `AuthServiceTest` needs no session state
and `SessionManagerTest` needs no passwords.

## 6. Admin, roles, and input validation

- The **Employees tab** is the admin console. The add-employee form and the
  delete button render **only** for `Role.ADMIN`; the server independently re-checks the
  role on `EMPLOYEE_ADD_REQUEST` and `EMPLOYEE_DELETE_REQUEST`. Client-side gating is
  for usability; the server-side check is the actual security boundary.
- **`PasswordPolicy`** — minimum length, requires a digit and a letter. Its own class so
  the rule can be pointed at and changed without touching auth logic.
- **`PersonalIdValidator`** — implements the real Israeli ID checksum (a Luhn-style
  check digit over 9 digits, zero-padded). Not merely "9 digits" — an actually invalid
  ID number is rejected.
- **`PhoneValidator`** — accepts Israeli landline/mobile shapes after stripping spaces
  and dashes; deliberately loose enough not to reject real numbers, strict enough to
  catch `"asdf"`.
- **Uniqueness** — a duplicate username is rejected by `AuthService.createAccount`, and
  a duplicate employee number by `ClientHandler` before the account is created.
  Without the second check, re-adding an existing number would silently overwrite that
  employee's record.

### Domain exceptions (`common/exception`)

Failures in the domain are their own named types rather than `IllegalArgumentException`
with different text each time, so callers can tell one failure from another without
matching on message strings, and each exception carries the facts of the failure as
fields (`InsufficientStockException.getShortfall()`, `ValidationException.getFieldName()`).

They sit under two abstract roots, and the split is the useful part — the two groups
call for different responses:

- **`InvalidRequestException extends IllegalArgumentException`** — the request itself is
  malformed and can be fixed by correcting the input: `InvalidQuantityException`,
  `ValidationException`, `DuplicateEmployeeException`, `DuplicateUsernameException`,
  `StockOverflowException`.
- **`StoreStateException extends IllegalStateException`** — the request is well-formed but
  the store cannot satisfy it right now; the identical request could succeed later:
  `InsufficientStockException`, `DuplicateCustomerException`, `CustomerNotFoundException`.

Each root extends the standard exception it replaced, which is what made the change
safe to make late: every existing `catch (IllegalArgumentException | IllegalStateException)`
in `ClientHandler` keeps working untouched, and only code that wants the precise reason
has to name the subclass. `DomainExceptionTest` pins both halves down — the carried data,
and the fact that each type still matches the standard one.

## 7. Inventory: sale and purchase

`Inventory` is the Observer *Subject* and the single point of mutation:

- `addStock` / `removeStock` are `synchronized`, so two employees selling the last unit
  concurrently cannot both succeed — one gets an `IllegalStateException`.
- `removeStock` re-checks sufficiency inside the lock, which is what actually makes the
  sale safe (the caller's earlier check is only a fast path).
- `addStock` uses `Math.addExact`, so a restock quantity large enough to overflow `int`
  fails loudly instead of silently wrapping stock to a negative number.
- Every mutation notifies observers, which is how "המלאי מתעדכן אצל כל העובדים בסניף"
  is implemented — including for the employee who performed the action, who learns the
  new level through the same push as everyone else rather than a special case.

## 8. Chat (`ChatMediator`)

The mediator is the only object that knows who is connected, who is busy, and who is
waiting. Employees never hold references to each other.

- `requestChat(from, targetBranchId)` finds a free employee at that branch and opens a
  `ChatSession`, or enqueues the request on that branch's `BlockingQueue<ChatRequest>`
  and replies `CHAT_QUEUED`.
- When a participant frees up, `notifyIfQueuedRequestWaiting` pops the oldest queued
  request (FIFO) and sends that employee a `CHAT_FREE_NOTICE` naming who tried to reach
  them, so they can call back via `requestDirectChat`.
- `joinChat` lets a `SHIFT_MANAGER` join an existing session (a session holds a
  participant *list*, not a pair).
- **All three entry points refuse an already-busy employee.** Without that guard,
  starting a second conversation would repoint the employee's session mapping while the
  first session still listed them, so ending the first would delete their real mapping
  to the second and tell their client the wrong chat ended. Re-requesting the session
  you are already in is treated as a harmless no-op rather than an error.
- `unregister` (on disconnect) also purges that employee's own queued requests, so a
  person who closed the app can't later surface as a "waiting for you" notice.
- Every state field is guarded by one monitor (`synchronized` methods on the mediator),
  which is why plain `LinkedHashMap`/`LinkedHashSet` are safe here — and gives
  deterministic, registration-order matching for "first free employee".

## 9. Logging

`LogManager` (Singleton) collects `LogEvent`s. `LogType` covers employee and customer
lifecycle events, purchases and sales, and chat sessions:

| LogType | Written when |
|---|---|
| `EMPLOYEE_REGISTERED` | an admin adds an employee |
| `EMPLOYEE_REMOVED` | an admin deletes one *(added for symmetry, since every other admin mutation is audited)* |
| `CUSTOMER_REGISTERED` | a customer is added |
| `PURCHASE` | stock is restocked from the supplier |
| `SALE` | a product is sold to a customer |
| `CHAT` | a chat session ends — **the entry stores the full transcript**, not just the fact that a chat happened |

The admin-only System Log tab reads these back, filterable by type and sorted
newest-first.

## 10. Reports

`ReportService` aggregates `SalesRecord`s into a `ReportResponse` (a list of
`ReportLineDto` plus totals), grouped by `ReportScope` — `BRANCH`, `PRODUCT`,
`CATEGORY`, or `ALL` — optionally narrowed to one `filterValue` and/or one calendar day.
Day comparison is done in UTC so results don't shift with the server's timezone, and
filter matching is case-insensitive so `"tops"` finds `"Tops"`.

`ReportExporter` is the Strategy interface. `JsonReportExporter` produces JSON;
`WordReportExporter` produces a genuine `.docx` via **Apache POI**, which the client
saves to disk after Base64-decoding it from the response.

## 11. Persistence — and what is deliberately not persisted

| Data | Storage | Why |
|---|---|---|
| Employees | `data/employees.json` | survives restart; needed to log in again |
| Accounts (credentials) | `data/accounts.json` | same, hashed + salted |
| Branches, products | in memory, seeded at startup | no DB was required; seeding is `DemoServerLauncher`'s job |
| Customers, sales history, log | in memory | see below |

Both JSON repositories write to a temp file and then **atomically rename** it over the
real one, so a crash mid-write cannot leave a half-written, corrupt file — a reader only
ever sees the complete old version or the complete new one.

`Customer` is polymorphic, and Gson can serialize a concrete instance but cannot know
which subclass to rebuild on the way back without extra type machinery. Rather than add
that machinery for data that doesn't need to survive a restart, customers and sales stay
in memory — a deliberate, documented trade-off, isolated behind the same repository
interfaces so a real database could replace it without touching any service.

## 12. Testing (102 tests)

| Area | Tests |
|---|---|
| Domain model | `CustomerTest` (per-type discounts, the VIP floor-at-zero boundary, stock guard), `InventoryTest` (add/remove, overflow, observer notify/unregister), `CustomerDirectoryTest` |
| Domain exceptions | `DomainExceptionTest` (8 — the data each exception carries, and that every one still matches the standard type it replaced) |
| Transport | `MessageChannelTest` |
| Services | `AuthServiceTest`, `SessionManagerTest`, `ChatMediatorTest` (14 — matching, queueing, callback, join, and every busy-guard), `ReportServiceTest` (11 — grouping, day filter, case-insensitive filter, both formats), `PasswordHasherTest`, `PersonalIdValidatorTest`, `PhoneValidatorTest`, `LogManagerTest` |
| Persistence | `JsonFileEmployeeRepositoryTest`, `JsonFileAccountRepositoryTest` (round-trip through disk, delete, no temp file left behind) |
| End-to-end over **real sockets** | `ServerMainIntegrationTest` (duplicate login), `LiveSyncIntegrationTest` (Observer push between two clients), `ChatIntegrationTest`, `RestockIntegrationTest`, `EmployeeAndLogIntegrationTest`, `EmployeeDeleteIntegrationTest`, `InputValidationIntegrationTest`, `LoggingCoverageIntegrationTest`, `MalformedRequestResilienceIntegrationTest` |

The integration tests start a real `ServerSocket`, connect real client sockets, and
assert on real pushed messages — they exercise the actual `ClientHandler` wiring, not a
mock of it.

## 13. Package layout (as built)

```
common/src/main/java/managestore/common/
  model/     Employee, Role, Customer(+3 subclasses), CustomerType, CustomerFactory,
             Product, Branch, Inventory, InventoryObserver, CustomerDirectory,
             CustomerDirectoryObserver, StoreChain, PurchaseResult, SalesRecord,
             LogEvent, LogType
  exception/ InvalidRequestException + StoreStateException (abstract roots), and the
             concrete InvalidQuantity, StockOverflow, Validation, DuplicateEmployee,
             DuplicateUsername, InsufficientStock, DuplicateCustomer, CustomerNotFound
  protocol/  Message, MessageChannel, MessageType, NetworkDefaults + one DTO per
             request/response/notice (Login, Inventory, Purchase, Restock, Customer,
             Employee, Branch, Report, Chat, Log, Error)

server/src/main/java/managestore/server/
  net/       ServerMain, ClientHandler, ServerContext, BootstrapAdmin, DemoServerLauncher
  service/   AuthService, PasswordHasher, PasswordPolicy, PersonalIdValidator,
             PhoneValidator, SessionManager, PurchaseService, ReportService,
             ChatMediator, ChatSession, ChatRequest, ChatEndpoint, LogManager
  repository/EmployeeRepository, AccountRepository, SalesRecordRepository,
             JsonFileEmployeeRepository, JsonFileAccountRepository
  report/    ReportExporter, JsonReportExporter, WordReportExporter
  model/     Account (server-only: credentials, never sent to a client)

client/src/main/java/managestore/client/
  ClientMain, net/ServerConnection
  ui/        LoginScreen, MainWindow, InventoryPanel, CustomersPanel, ReportsPanel,
             ChatPanel, EmployeesPanel, LogsPanel, PasswordRevealField, UiUtil
  resources/ app.css
```

## 14. Design trade-offs and known limitations

1. **No database** — JSON files behind repository interfaces (§11).
2. **Customers/sales/log are in-memory** — reset on restart; not required to persist (§11).
3. **No edit-in-place for employees or customers** — create and delete exist; editing
   an existing record was out of scope for this iteration.
4. **Branches and products are seeded in code**, not creatable from the UI —
   `ServerMain` starts an empty network by design so the "admin creates the accounts"
   flow is real; `DemoServerLauncher` seeds a populated one for demos.
5. **A deleted employee's live session keeps working until they disconnect** — they can
   never log in again (`AuthService.login` refuses an account with no employee record),
   but no mechanism force-closes another thread's socket.
6. **Employee-number uniqueness is check-then-act**, not atomic. Two admins adding the
   same number in the same instant is a theoretical race; a repository-level
   `putIfAbsent` would close it, as `CustomerDirectory` already does for customers.
