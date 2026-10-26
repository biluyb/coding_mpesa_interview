# Failure Modes & Resilience Analysis

This document provides a comprehensive, plain-language engineering breakdown of how the M-PESA Wallet Service behaves under stress, concurrent traffic, external failures, and network partitions. It details the protections currently implemented, followed by a thorough code audit highlighting suspected failure modes and subtle edge cases you should be aware of.

---

## 1. Global & System Infrastructure

### 1.1 Database Connection Pool Exhaustion (HikariCP 10-Connection Limit)
* **The Risk**: If worker threads hold onto PostgreSQL database connections while waiting for slow external bank HTTP calls (which take 2–6 seconds or time out at 60 seconds), all 10 connections in the Hikari pool get choked within seconds. Every other API endpoint (balance lookups, P2P transfers) immediately stalls and throws connection timeout errors.
* **How It Is Prevented**: Database operations and external HTTP calls are strictly separated. The API reserves funds in a quick database transaction (under 5 milliseconds) and immediately releases the connection back to the Hikari pool. The HTTP call to the bank is dispatched on a separate asynchronous background thread pool with zero database connections held.

### 1.2 Tomcat Worker Thread Exhaustion (50-Thread Limit)
* **The Risk**: If the web server blocks incoming request threads until external partner APIs reply, 50 concurrent requests will consume every available Tomcat worker. The application becomes completely unresponsive, dropping new incoming HTTP handshakes.
* **How It Is Prevented**: All slow external workflows (such as bank payouts) use an asynchronous processing pattern. The API validates the request, locks the reservation in PostgreSQL, returns `202 Accepted` to the client in less than 20 milliseconds, and frees the Tomcat thread immediately.

### 1.3 Database Deadlocks in Concurrent Transfers (The ABBA Problem)
* **The Risk**: When Alice transfers money to Bob at the exact same moment Bob transfers money to Alice, Thread 1 locks Alice's wallet and waits for Bob's wallet, while Thread 2 locks Bob's wallet and waits for Alice's wallet. PostgreSQL detects an unresolved deadlock and aborts one or both transactions.
* **How It Is Prevented**: Strict alphabetical (lexicographical) lock ordering. The system always locks `min(wallet_a, wallet_b)` before `max(wallet_a, wallet_b)` using `SELECT ... FOR UPDATE`. Because all threads acquire locks in the exact same global order, cyclic lock waits are mathematically impossible.

### 1.4 Duplicate Execution via Idempotency Race Conditions
* **The Risk**: A mobile client or payment gateway experiences a network blip and instantly retries a request with the same `Idempotency-Key`. If two requests hit Instance 1 and Instance 2 at the exact same millisecond, both might read "key does not exist" and execute the money movement twice.
* **How It Is Prevented**: An atomic insertion into the PostgreSQL `idempotency_keys` table using a primary key constraint. Exactly one thread succeeds in inserting the initial record with `IN_PROGRESS` status; the other thread receives a unique constraint violation and is held back.

### 1.5 Payload Mutation on Key Re-use
* **The Risk**: A malicious or buggy client sends a request with an existing idempotency key but changes the destination wallet or increases the transfer amount.
* **How It Is Prevented**: The system calculates a SHA-256 hash of the target endpoint combined with the normalized request body. If a subsequent request matches an existing key but has a different hash, the system immediately rejects it with `409 IDEMPOTENCY_CONFLICT`.

### 1.6 Server Crash / Hard Kill (`kill -9`) Leaving Operations Orphaned
* **The Risk**: A node reserves user funds and sends an HTTP request to the bank, but the JVM crashes or the container is terminated before the bank replies or before the database is updated. The user's funds remain locked in `reserved` status forever.
* **How It Is Prevented**: An active background `PayoutReconciler` runs periodically across surviving nodes. It queries pending transactions older than 5 seconds using `FOR UPDATE SKIP LOCKED`, contacts the bank's query API, and reconciles the transaction to either `SUCCEEDED` or `FAILED` within 2 minutes.

### 1.7 Transient Network or Database Drops
* **The Risk**: A temporary glitch in database connectivity leaves money debited without returning a clean confirmation, prompting users to retry blindly.
* **How It Is Prevented**: Atomic transactional demarcation (`@Transactional`) ensures that any unexpected error forces an automatic PostgreSQL rollback. A centralized `GlobalExceptionHandler` converts raw SQL and connection pool exceptions into standardized JSON responses with `503 SERVICE_UNAVAILABLE`, preventing internal schema or stack trace leakage.

---

## 2. Peer-to-Peer Transfers (`POST /mpesa/v1/p2p/transfers`)

### 2.1 Concurrent Overdrafts & Race Conditions
* **The Risk**: A user with 1,000 santim fires ten simultaneous transfer requests of 1,000 santim across multiple browser tabs or parallel API calls. A standard "read balance, check balance, deduct balance" flow results in a negative balance.
* **How It Is Prevented**: Both sender and receiver rows are locked simultaneously using `SELECT ... FOR UPDATE`. The available balance is computed and validated while holding this exclusive lock. In addition, the database schema enforces a hard invariant constraint: `CHECK (balance >= reserved)` and `CHECK (balance >= 0)`.

### 2.2 Self-Transfers & Lock Self-Deadlocks
* **The Risk**: A user initiates a transfer where `from_wallet` equals `to_wallet`. Attempting to lock the same row twice in a single transaction can cause unexpected driver stalls or artificially inflate/deflate balances.
* **How It Is Prevented**: Request validation checks `from_wallet.equals(to_wallet)` upfront before any database query is issued, immediately rejecting the request with `422 SAME_WALLET`.

### 2.3 Partial Transfer Execution (Asymmetric Balance Shift)
* **The Risk**: The sender's account is successfully debited, but an error occurs while crediting the recipient, resulting in money disappearing from the system.
* **How It Is Prevented**: The debit, credit, transaction record creation, and ledger statement entries are executed in a single atomic database transaction. If any part fails, the entire transaction is rolled back completely.

### 2.4 Race Conditions with Account Freezes
* **The Risk**: A compliance officer freezes a suspicious wallet, but a concurrent transfer that started milliseconds earlier slips through and moves the funds out after the freeze was confirmed.
* **How It Is Prevented**: The transfer transaction locks the sender's row and verifies that `status == 'ACTIVE'` under the row lock. If the freeze transaction committed first, the transfer sees `status == 'FROZEN'` and aborts with `422 WALLET_NOT_ACTIVE`.

### 2.5 Active Reservations Ignored During Transfer
* **The Risk**: A wallet has 5,000 santim total balance, but 4,000 santim are currently reserved for an in-flight bank payout. If a P2P transfer only checks `balance >= amount`, it could send 3,000 santim, leaving insufficient funds to settle the bank payout later.
* **How It Is Prevented**: Transfers strictly check `available = balance - reserved >= amount`. Active reservations are never touched or compromised by P2P transfers.

---

## 3. Transfer Reversals (`POST /mpesa/v1/p2p/transfers/{id}/reversal`)

### 3.1 Double Reversals (Duplicate Refund)
* **The Risk**: Two customer support agents or automated retry mechanisms trigger a reversal for the same transfer at the same time, refunding the sender twice.
* **How It Is Prevented**: The transfer record is locked with `FOR UPDATE` and inspected. If the status is already `REVERSED`, the request is rejected with `409 ALREADY_REVERSED`. A unique database constraint on `reversals.transfer_id` guarantees that only one reversal row can ever be committed per transfer.

### 3.2 Overdrafting Recipient on Reversal
* **The Risk**: Alice sends 5,000 santim to Bob. Bob immediately withdraws or transfers that money away. Alice then requests a reversal. Forcing a debit on Bob would push his account into a negative balance.
* **How It Is Prevented**: The reversal treats the original recipient as the debited party. It locks Bob's wallet and checks whether his `available >= amount`. If Bob lacks the funds, the reversal is rejected with `422 INSUFFICIENT_FUNDS`.

### 3.3 Reversals to Frozen Accounts
* **The Risk**: Alice's wallet was frozen due to suspicious activity after she sent a transfer. If the system rejects returning funds because the destination wallet is frozen, money remains stranded.
* **How It Is Prevented**: The system explicitly allows the credited wallet in a reversal to be either `ACTIVE` or `FROZEN`. Money can always safely flow back into a frozen account; only outgoing debits from frozen accounts are blocked.

---

## 4. Wallet State & Balances (`GET / PUT /mpesa/v1/wallets/{id}`)

### 4.1 Inconsistent Balance Snapshots
* **The Risk**: Reading `balance` in one query and `reserved` in a subsequent query during heavy transaction activity can produce an inconsistent view where `available != balance - reserved`.
* **How It Is Prevented**: Balance, reservation, and status are retrieved in a single SQL row read (`SELECT balance, reserved FROM wallets WHERE id = ?`). The `available` field is computed directly in application logic from that exact atomic snapshot.

### 4.2 Freeze Operations Blocked by Heavy Traffic
* **The Risk**: Freezing an account times out (violating the 2-second SLA) because thousands of incoming P2P transfers are queued up on the wallet.
* **How It Is Prevented**: Account freeze is an ultra-lean status update (`UPDATE wallets SET status = ? WHERE id = ?`) that takes less than 5 milliseconds once the row lock is acquired.

### 4.3 Redundant Freeze / Unfreeze Idempotence
* **The Risk**: Repeating a freeze request on an already frozen account causes errors, confusion, or redundant audit logs.
* **How It Is Prevented**: Status update operations are idempotent. Setting an already frozen wallet to `FROZEN` succeeds cleanly and returns `200 OK` with the confirmed status.

---

## 5. Bank Payouts (`POST /mpesa/v1/payouts`)

### 5.1 Slow Partner Bank Latency Exceeding SLAs
* **The Risk**: The partner bank takes up to 6 seconds to respond (or hangs for 60 seconds). Blocking the HTTP request causes the client's payout submission to breach the 500ms p95 latency SLA.
* **How It Is Prevented**: The endpoint immediately reserves funds, logs the payout as `PENDING`, and returns `202 Accepted` within 20 milliseconds. The external HTTP call to the bank is dispatched asynchronously in the background.

### 5.2 Premature Balance Deduction
* **The Risk**: Deducting balance immediately upon payout initiation causes user account balance to drop before the bank has confirmed acceptance. If the bank subsequently rejects the transaction, the money has to be manually restored.
* **How It Is Prevented**: The balance is untouched at submission time; only the `reserved` counter is incremented. The actual `balance` is only deducted when the bank explicitly confirms success. If the payout fails, `reserved` is decremented back to normal without ever modifying the ledger balance.

### 5.3 Ambiguous Bank Timeouts (HTTP 504 / Network Dropped)
* **The Risk**: The wallet service sends a payout request to the bank, but the connection times out. If the wallet assumes failure and releases the reserved funds, the bank might still execute the payout, leading to a financial deficit.
* **How It Is Prevented**: Timeouts and network connection errors are treated as "Unknown Outcome". The system keeps the funds safely reserved and does not mark the transaction as failed. The background reconciler and incoming webhooks query and verify the bank's actual state before releasing any funds.

---

## 6. Bank Webhooks & Callbacks (`POST /mpesa/v1/callbacks/bank`)

### 6.1 Duplicate or Out-of-Order Webhook Delivery
* **The Risk**: The bank sends duplicate webhook notifications or retries simultaneously across multiple instances. Both instances attempt to debit the user's balance and finalize the payout twice.
* **How It Is Prevented**: The payout row is locked (`SELECT ... FOR UPDATE`). If the payout has already transitioned to a terminal state (`SUCCEEDED` or `FAILED`), subsequent callbacks are safely ignored, returning an idempotent `200 OK`.

### 6.2 Status Contradictions (Bank Callback vs. Reconciler Race)
* **The Risk**: The background reconciler marks a payout as `FAILED` because the bank reported `REJECTED`, but a delayed bank callback arrives later claiming the payout actually `SUCCEEDED`.
* **How It Is Prevented**: If a terminal status change contradicts an already finalized record, the system preserves the existing status, flags the record with `conflict = true`, updates the timestamp, and alerts operators for manual auditing without corrupting ledger integrity.

### 6.3 Callback Arriving Before Payout Record is Committed
* **The Risk**: If the bank processes payouts extremely fast, a callback could arrive while the initial payout insertion transaction is still committing, causing the callback to throw a `404 PAYOUT_NOT_FOUND`.
* **How It Is Prevented**: The initial payout record is inserted and fully committed to PostgreSQL *before* the asynchronous HTTP dispatch to the bank is triggered.

---

## 7. Account Statements & Ledger Pagination (`GET /mpesa/v1/wallets/{id}/statement`)

### 7.1 Cursor Drift and Inconsistent Pagination
* **The Risk**: When a user pages through their transaction statement while new transfers are actively settling, traditional offset pagination (`OFFSET 50`) shifts, causing duplicate entries or missed transactions across page boundaries.
* **How It Is Prevented**: Keyset cursor pagination. On the initial query, the system captures `snapshotMaxSeq = MAX(seq)` and embeds it into an opaque Base64 cursor alongside the lowest sequence number on the current page (`seq < beforeSeq`). All subsequent pages view a frozen point-in-time snapshot of the ledger.

### 7.2 Full Table Scans on High-Volume Wallets
* **The Risk**: Generating statements for high-activity merchant wallets with millions of entries causes slow queries and CPU spikes.
* **How It Is Prevented**: Covered B-tree index on `(wallet_id, seq DESC)`. PostgreSQL traverses only the necessary leaf index pages directly matching the requested wallet and sequence window.

---

## 8. Suspected Failure Modes & Architectural Vulnerabilities (Code Audit)

During our deep inspection of the active codebase, we identified several subtle edge cases, architectural gaps, and latent failure suspects that could manifest under high concurrency or real-world production stress:

### ⚠️ Suspect 1: In-Memory `AtomicLong` Sequence Collisions Across Multi-Instance Deployments
* **Location**: `TransferService.java` (lines 23–24, 86, 161)
* **The Vulnerability**: Transfer IDs (`T-<seq>`) and Reversal IDs (`R-<seq>`) are generated using in-memory `AtomicLong` counters initialized to `System.currentTimeMillis() % 1000000`. In a multi-node deployment (e.g., Instance 1 on port 8081 and Instance 2 on port 8082), both JVMs run independent counters. Under concurrent traffic, both instances can easily generate identical IDs (e.g., `T-481920`). When this occurs, PostgreSQL rejects the second insert with a `DuplicateKeyException` on the primary key, causing an unhandled 500 error for legitimate users.
* **Recommendation**: Replace in-memory `AtomicLong` sequences with PostgreSQL native sequences (`CREATE SEQUENCE transfer_seq`) or UUID-based identifiers like those used in `PayoutService`.

### ⚠️ Suspect 2: `PayoutReconciler` Autocommit Releases Lock Immediately
* **Location**: `PayoutReconciler.java` (lines 35–47)
* **The Vulnerability**: The scheduled method `reconcilePendingPayouts()` executes `SELECT id FROM payouts WHERE status = 'PENDING' ... FOR UPDATE SKIP LOCKED`. However, the method is **not** annotated with `@Transactional`. In Spring and PostgreSQL, queries executed outside a transaction run in autocommit mode—meaning the row lock is acquired during the query and **instantly released** as soon as `jdbcTemplate.query()` returns! As a result, when both Instance 1 and Instance 2 run their scheduled reconciler jobs, both can read the exact same pending IDs and fire duplicate status checks to the bank concurrently.
* **Recommendation**: Wrap individual payout reconciliation tasks in explicit transactions, or maintain an in-memory lock / lease per payout ID.

### ⚠️ Suspect 3: Reconciler Busy-Loop / Polling Storm on Bank "PENDING" Responses
* **Location**: `PayoutReconciler.java` (lines 58–79)
* **The Vulnerability**: When the reconciler queries the bank and the bank responds with status `"PENDING"` (meaning the bank is still processing the transfer), the code does nothing. It does not update the `updated_at` timestamp on the payout row. Because the query looks for payouts where `updated_at < (CURRENT_TIMESTAMP - INTERVAL '5 SECOND')`, this payout will qualify again on the very next reconciler tick 2 seconds later. If hundreds of payouts take 30–60 seconds to process at the bank, the reconciler will aggressively poll the bank every 2 seconds in a tight loop.
* **Recommendation**: Update `updated_at = CURRENT_TIMESTAMP` when the bank confirms the payout is still pending to apply exponential backoff.

### ⚠️ Suspect 4: Poisoned Idempotency Keys on Application Exceptions
* **Location**: `IdempotencyService.java` (lines 88–92)
* **The Vulnerability**: When an operation throws an exception, `IdempotencyService` checks:
  ```java
  if (!(ex instanceof ApiException)) {
      jdbcTemplate.update("DELETE FROM idempotency_keys WHERE key = ?", key);
  }
  ```
  If the failure **is** an `ApiException` (for example, `INSUFFICIENT_FUNDS`, `WALLET_NOT_ACTIVE`, or validation failure), the key is **not** deleted from the database, nor is its status updated to `FAILED` or `COMPLETED`. It remains permanently stuck with `status = 'IN_PROGRESS'`. If the user fixes the issue (such as depositing money) and retries with the same `Idempotency-Key`, `handleExistingKey` will loop for 2.5 seconds waiting for the operation to finish, and then fail with `409 REQUEST_IN_PROGRESS`. The idempotency key becomes permanently unusable.
* **Recommendation**: Either delete the key on `ApiException` so the client can retry cleanly, or record the terminal failure response status and body in `idempotency_keys` so subsequent calls receive the cached error immediately.

### ⚠️ Suspect 5: Busy-Waiting Threads in Idempotency Checks Can Exhaust Tomcat Pool
* **Location**: `IdempotencyService.java` (lines 95–129)
* **The Vulnerability**: When a concurrent duplicate request arrives while the first request is still being processed, `handleExistingKey` executes a polling loop with `Thread.sleep(50)` for up to 2.5 seconds on the active Tomcat thread. With Tomcat configured for a maximum of 50 threads (`--server.tomcat.threads.max=50`), a sudden burst of 50 concurrent requests sharing keys or waiting on slow database locks will occupy every available worker thread in sleep loops, starving all other healthy endpoints in the system.
* **Recommendation**: Instead of blocking Tomcat worker threads in polling loops, use non-blocking asynchronous deferred results or fail fast with `409 REQUEST_IN_PROGRESS` if a concurrent request is detected.

### ⚠️ Suspect 6: Unbounded Bank Dispatch Thread Pool Queue
* **Location**: `PayoutService.java` (line 31)
* **The Vulnerability**: The bank dispatch executor is instantiated as:
  ```java
  private final ExecutorService bankDispatchExecutor = Executors.newFixedThreadPool(30);
  ```
  `Executors.newFixedThreadPool(30)` uses an unbounded `LinkedBlockingQueue`. If traffic spikes or the bank server degrades and responds slowly (e.g. 5–10 seconds per call), incoming payout requests will keep queuing up in JVM heap memory without bound. If memory runs out or if requests sit in the queue longer than 20 seconds, the background reconciler may prematurely consider the payout missing at the bank and fail it while the task is still waiting in memory to be dispatched.
* **Recommendation**: Use a bounded queue with an explicit rejection handler and backpressure monitoring.

### ⚠️ Suspect 7: Unauthenticated Bank Webhook Endpoint
* **Location**: `BankCallbackController.java` (lines 22–30)
* **The Vulnerability**: The `POST /mpesa/v1/callbacks/bank` endpoint currently accepts payloads without any cryptographic signature verification, shared HMAC secret, mutual TLS (mTLS), or IP allowlisting. Any client on the network can send arbitrary POST requests to trigger `finalizeSuccess` or `finalizeFailed` on any known payout ID.
* **Recommendation**: Require an HMAC-SHA256 signature header or an API bearer token verified against bank credentials before processing callbacks.

### ⚠️ Suspect 8: Unimplemented B2C Bulk Disbursements Endpoint
* **Location**: Section 5.6 (`POST /mpesa/v1/b2c/disbursements`)
* **The Vulnerability**: The bulk disbursement feature specified in the requirements is not implemented in the active codebase. Any client attempting to invoke `/mpesa/v1/b2c/disbursements` will receive an HTTP 404.
* **Recommendation**: Implement batch disbursement processing with row-by-row transactional isolation and deduplication as outlined in the system architecture.