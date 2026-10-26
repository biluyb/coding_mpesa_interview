# Design & Architecture Notes

## Written Answers

### Q1: Every Wait
Thread waits in this system are strictly bounded and isolated to prevent cascading slowdowns:

1. **Hikari Database Connection Pool (10 connections)**
   - **Where it waits**: Threads calling `DataSource.getConnection()` when all 10 pool connections are in use.
   - **Timeout & behavior**: Hard-capped at 2 seconds (`hikari.connection-timeout=2000`). If connections aren't freed in time, Hikari throws `SQLTransientConnectionException`, which our global handler maps to `503 SERVICE_UNAVAILABLE`. External bank calls never hold database connections, which keeps pool churn low and fast.

2. **Database Row Locks (`SELECT ... FOR UPDATE`)**
   - **Where it waits**: Worker threads contending for the same wallet rows during transfers, reversals, or payout reservations.
   - **Timeout & behavior**: Bounded by Hikari's 2-second timeout and PostgreSQL statement limits. We completely prevent deadlocks by always sorting and locking wallets in alphabetical order (`min(wallet_id)` before `max(wallet_id)`).

3. **External Bank HTTP Dispatch**
   - **Where it waits**: HTTP calls to `POST /bank/payouts` and `GET /bank/payouts/{id}`.
   - **Timeout & behavior**: Connection timeout is set to 2 seconds, and the request timeout is capped at 8 seconds. These calls run exclusively on a dedicated background thread pool (`bankDispatchExecutor`), so they never hold Tomcat request threads or database connections.

4. **Idempotency Spin-Wait**
   - **Where it waits**: When two duplicate requests with the same `Idempotency-Key` hit the service at the same time while the first one is still being processed.
   - **Timeout & behavior**: The second thread polls every 50ms for up to 2.5 seconds. If the first request finishes in that window, the second request returns the cached result. If it takes longer than 2.5 seconds, it returns `409 REQUEST_IN_PROGRESS`.

---

### Q2: Database Restart During Payout
**Scenario**: PostgreSQL restarts after funds are reserved (Step A), but before the bank responds or sends a callback (Step C).

1. **Funds Remain Safe in the WAL**: The initial reservation transaction commits before the database drops. PostgreSQL's write-ahead log ensures durability. The wallet still reflects `reserved = reserved + amount` and the payout stays `PENDING`. Because available balance is `balance - reserved`, the user cannot spend or withdraw those reserved funds elsewhere.
2. **Handling Traffic During Downtime**:
   - If the bank callback arrives while Postgres is restarting, the connection fails and returns `503`. The bank retries up to 5 times every 3 seconds.
   - If the background dispatch thread gets an immediate bank response while the database is down, it drops gracefully without corrupting state.
3. **Automatic Recovery on Restart**:
   - Once Postgres is back online, the next bank callback retry succeeds, locks the payout record, and finalizes the ledger.
   - If the callback was dropped entirely or the bank hung, our background `PayoutReconciler` picks up the pending payout after 5 seconds, queries the bank's status endpoint, and finalizes the transaction to `SUCCEEDED` (deducts balance and clears reservation) or `FAILED` (releases reservation only). Funds are never stranded.

---

### Q3: What Was Left Out

1. **B2C Bulk Disbursements (Section 5.6)**
   - **Why it is challenging**: A single request can contain up to 5,000 disbursements from one company wallet. Holding a row lock while processing 5,000 transfers sequentially would block all other activity on that wallet for seconds and blow past our latency SLAs.
   - **How to build it properly**: Process the batch in small, chunked transactions (e.g., 50 rows per chunk). Each chunk locks the source wallet briefly, debits the chunk total, credits the recipients, and commits immediately. Store a SHA-256 hash of the entire payload on the batch record to reject tampering with `409 Conflict`, while allowing safe re-checks on matching batch IDs.

2. **Disbursement End-to-End Test Suite (S6)**
   - We deferred the bulk disbursement test suite to focus engineering effort on rock-solid correctness for the core financial operations: P2P transfers, exact reversals, account freezing, and asynchronous bank payouts with automated reconciliation.

---

## Architectural Decisions & Trade-offs

1. **Pessimistic Locking over Serializable Isolation**
   - We chose `READ COMMITTED` with explicit `SELECT ... FOR UPDATE` over PostgreSQL's `SERIALIZABLE` isolation level. Under heavy concurrent load on popular wallets, `SERIALIZABLE` triggers frequent serialization failure aborts (`40001`), requiring application-level retries that quickly exhaust our 10-connection pool. Explicit lock ordering (`min` then `max`) gives us strict serial execution without deadlock risk or retry overhead.

2. **Idempotency with Short-Polling**
   - Instead of immediately rejecting concurrent duplicate requests with an error, we hold the duplicate request for up to 2.5 seconds with periodic checks. This allows the second request to receive the exact same response as the first without requiring client-side retry logic.

3. **Three-Phase Payout Pipeline**
   - Payouts are decoupled into three distinct stages:
     - **Phase A**: Lock row, reserve funds, create `PENDING` record, and commit (<15ms). Return `202 Accepted` immediately.
     - **Phase B**: Dispatch HTTP request to the external bank asynchronously on a separate thread pool without holding database connections.
     - **Phase C**: Finalize ledger upon receiving bank webhook callback, or reconcile via scheduled background polling if callbacks fail.
   - This design isolates slow third-party banking latency (2–60s) from our core API threads and protects database connections from starvation.
