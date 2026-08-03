# Architecture — Store Chain Management System (ManageStoreSystem)

Course: Algorithmic Development, JAVA — HIT, Summer 2026 (instructor: Roi Zimon)

This document is written so you can present and defend every design decision. For each
requirement from the brief, it names the concrete class/pattern that satisfies it.

## 1. High-level shape

Client-server system over plain TCP sockets. Three Maven modules:

```
ManageStoreSystem/
  common/   <- shared model + wire protocol (used by BOTH client and server)
  server/   <- server app: networking, business logic, persistence, logging
  client/   <- JavaFX desktop app: screens + a thin networking layer
```

`common` exists because client and server must agree on the exact shape of
`Employee`, `Customer`, `Product`, and every `Message` sent over the socket. Putting
those classes in one shared module (rather than duplicating them) is itself a basic
but important design decision — no drift between what the client sends and what the
server expects.

## 2. Domain model (`common/model`)

```
Employee
  - id, fullName, personalId, phone, accountNumber, branchId, employeeNumber, role
  - Role (enum): SHIFT_MANAGER, CASHIER, SELLER, ADMIN

Customer (abstract)
  - fullName, personalId, phone
  - abstract double applyDiscount(double amount)
  - abstract PurchaseResult purchase(Product product, int quantity)
  NewCustomer      extends Customer   // e.g. no discount, welcome track
  ReturningCustomer extends Customer  // e.g. small loyalty discount
  VIPCustomer       extends Customer  // e.g. larger discount + priority perks

Product
  - sku, name, category, price

Branch
  - id, name
  - Inventory inventory
  - List<Employee> staff

Inventory
  - Map<Product, Integer> stock  (per branch)
  - operations: addStock(), removeStock(), getQuantity()

StoreChain
  - List<Branch> branches                  (the whole network)
  - CustomerDirectory customerDirectory     (network-wide, shared by all branches)
```

### Why a `Customer` class hierarchy instead of one class + an enum field

The brief explicitly requires: *"יש להגדיר מחלקה שונה עבור כל סוג לקוח... כל מחלקה
תטפל בפרטי מבצע שונה"* — a distinct class per customer type, each handling its own
deal/discount logic. That is a textbook case for **polymorphism over
conditionals**: instead of `if (type == VIP) {...} else if (type == RETURNING)
{...}`, every subclass overrides `applyDiscount()` and `purchase()` with its own
logic. `PurchaseService.sell(customer, product, qty)` just calls
`customer.purchase(product, qty)` and never needs to know which subclass it's
talking to. This is the single most important OOP point to be ready to explain.

## 3. Design patterns used (be ready to name these explicitly)

| Pattern | Where | Requirement it satisfies |
|---|---|---|
| **Polymorphism / "Strategy via inheritance"** | `Customer` hierarchy | different class per customer type, different purchase track |
| **Observer** | `Inventory`, `CustomerDirectory` are `Subject`s; each logged-in client's `ClientHandler` is an `Observer` | inventory/customer changes must propagate live to every other employee |
| **Mediator + Producer/Consumer queue** | `ChatMediator` + `BlockingQueue<ChatRequest>` | cross-branch chat routing, queueing a request when no employee is free, notifying when one becomes free — this is the pattern the brief tells you to research and apply yourself |
| **Singleton** | `SessionManager` (tracks who's logged in, blocks duplicate logins), `LogManager` (single log sink) | "no duplicate login from multiple computers", centralized logging |
| **Factory** | `CustomerFactory` (creates the right `Customer` subclass), `MessageFactory` (deserializes incoming socket messages by type) | keeps object-creation logic in one place instead of scattering `new` + type-checks |
| **Strategy** | `ReportExporter` interface, implemented by `JsonReportExporter` and `WordReportExporter` | reports must be exportable as both JSON and Word from the same report data |

You don't have to use every pattern in the table to pass — but Observer (for sync),
the Customer polymorphism, and the chat queue pattern are directly required by the
brief's wording, so those three are the ones to know cold.

## 4. Networking / protocol (`common/protocol`, `server/net`, `client/net`)

- One `Message` base class, serialized to JSON over the socket (via Gson). Every
  message has a `type` field (e.g. `LOGIN_REQUEST`, `INVENTORY_UPDATE`,
  `CHAT_MESSAGE`, `REPORT_REQUEST`) and a `payload`.
- `ServerMain` opens a `ServerSocket`; each accepted connection gets its own
  `ClientHandler` thread (`implements Runnable`). This is the standard
  multithreaded-socket-server shape expected in a course like this — one thread
  per client, reading/writing that client's socket only.
- On successful login, `ClientHandler` subscribes itself as an `Observer` to:
  - the `Inventory` of the employee's own branch
  - the network-wide `CustomerDirectory`
  so it receives push updates without polling.

## 5. Duplicate-login prevention

`SessionManager` (Singleton) holds `Map<String username, ClientHandler activeSession>`.
`AuthService.login(username, password)`:
1. Validates credentials against `EmployeeRepository`.
2. If `SessionManager` already has an active session for that username, reject
   with `ALREADY_LOGGED_IN`.
3. Otherwise registers the new session; on socket disconnect the `ClientHandler`
   removes itself from `SessionManager`.

## 6. Admin & password policy

- `Role.ADMIN` sees the Admin screen: create/edit employee accounts.
- `PasswordPolicy` class (min length, requires digit, etc.) is applied whenever a
  password is set — kept as its own class so the policy can be explained/changed
  independently of `AuthService`.

## 7. Reports

- `SalesRecord` (branch, product, customer, quantity, price, timestamp) is logged
  on every sale.
- `ReportService` aggregates `SalesRecord`s by branch / by product / by category
  into a `SalesReport`.
- `ReportExporter` (Strategy): `JsonReportExporter` (Gson) and `WordReportExporter`
  (Apache POI — this is the "self-study" library the brief mentions) both take the
  same `SalesReport` and produce different output formats.

## 8. Chat system

- `ChatMediator` on the server is the only thing that knows about all connected
  employees' busy/free status (`Map<Employee, ChatStatus>`).
- Employee A requests a chat with an employee at branch B:
  - Mediator looks for a free employee at branch B → if found, opens a
    `ChatSession` and both sides are marked BUSY.
  - If none free, the request is placed on that branch's
    `BlockingQueue<ChatRequest>`.
  - When any employee at branch B goes free, the mediator pops the queue (FIFO)
    and notifies that waiting requester so they can re-initiate.
- A shift manager can join an existing `ChatSession` (session tracks a list of
  participants, not just 2).
- `ChatSession` end-of-conversation drops all socket-level chat routing; per the
  brief, the server does not keep relaying anything afterward — it only still
  knows both users are "busy" until the session formally ends.
- Duplicate-call prevention reuses the same `SessionManager` concept: a user only
  has one active socket/session, so they cannot originate two calls at once.

## 9. Logging

`LogManager` (Singleton) exposes `log(LogEvent)`. `LogEvent` types:
`EMPLOYEE_REGISTERED`, `CUSTOMER_REGISTERED`, `SALE`, `CHAT` (with an option to
persist the full chat transcript). Every relevant service call
(`EmployeeService`, `CustomerService`, `PurchaseService`, `ChatMediator`) fires an
event into `LogManager` rather than writing logs itself — keeps logging
cross-cutting and in one place.

## 10. Persistence

No database was required by the brief, so to keep the focus on OOP/sockets/design
patterns (what's actually being graded), persistence is simple **JSON files**
(one per entity type: `employees.json`, `customers.json`, `products.json`,
`sales.json`), loaded at server startup and rewritten on change, via the same
Gson dependency already used for the wire protocol. This is a deliberate scope
decision — swappable for a real DB later without touching the domain model,
since a `Repository` interface sits in front of it (`EmployeeRepository`,
`CustomerRepository`, etc.) with a `JsonFileXRepository` implementation.

## 11. Testing

JUnit 5 in every module. Priority test targets (these map directly to the
requirements, so failing/missing tests here are the first thing a grader checks):
- `Customer` subclasses: correct discount/purchase behavior per type
- `Inventory`: stock add/remove, negative-stock guard
- `SessionManager`: duplicate login rejected, freed on disconnect
- `ChatMediator`: queueing when nobody free, FIFO notification on free-up
- `ReportService` + both `ReportExporter`s: correct aggregation and output shape

## 12. Package layout (concrete)

```
common/src/main/java/managestore/common/
  model/        Employee, Role, Customer, NewCustomer, ReturningCustomer, VIPCustomer,
                Product, Branch, Inventory, StoreChain, LogEvent, SalesRecord
  protocol/     Message, MessageType, LoginRequest, LoginResponse, InventoryUpdate,
                CustomerUpdate, ChatMessage, ReportRequest, ReportResponse, ...

server/src/main/java/managestore/server/
  net/          ServerMain, ClientHandler, SessionManager
  service/      AuthService, EmployeeService, CustomerService, PurchaseService,
                ReportService, ChatMediator, LogManager
  repository/   EmployeeRepository, CustomerRepository, ProductRepository,
                SalesRepository, JsonFileXRepository implementations
  report/       ReportExporter, JsonReportExporter, WordReportExporter

client/src/main/java/managestore/client/
  net/          ServerConnection
  ui/           LoginScreen, AdminScreen, InventoryScreen, CustomersScreen,
                ReportsScreen, EmployeesScreen, ChatScreen, LogsScreen
```

## 13. Build order (matches the task list this was designed against)

1. `common` domain model + unit tests
2. `server` networking + auth + session control
3. Observer-based inventory/customer sync
4. Chat system (Mediator + queue)
5. Reports (JSON + Word export)
6. Logging
7. `client` JavaFX screens wired to networking
8. Tests throughout, README, push to GitHub
