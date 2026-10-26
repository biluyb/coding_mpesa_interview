# DESIGN.md

## Written Answers

### Q1: Every Wait
In this implementation, thread waits occur in four distinct locations:

1. **Hikari Connection Pool**:
   - *Wait point*: Worker threads requesting a JDBC connection via `DataSource.getConnection()`.
   - *Bound*: Explicitly bounded by `hikari.connection-timeout=2000` (2,000 ms). If all 10 connections are busy for >2 seconds, Hikari throws `SQLTransientConnectionException`, mapped to `503 SERVICE_UNAVAILABLE`. HTTP calls to the bank never hold connections, preventing pool exhaustion.

2. **Row Locks in PostgreSQL (`SELECT ... FOR UPDATE`)**:
   - *Wait point*: Threads contending for wallet rows (transfers/reversals/payout reservations) or payout records.
   - *Bound*: Bounded by Postgres transaction timeout / statement timeout (2,000 ms) and Hikari's 2-second timeout. Deadlocks between multiple wallets are eliminated entirely by lexicographical lock ordering (`min(wallet_id)` locked before `max(wallet_id)`).

3. **External Bank HTTP Dispatch**:
   - *Wait point*: Bank client making HTTP calls to `POST /bank/payouts` and `GET /bank/payouts/{id}`.
   - *Bound*: HTTP client connection timeout is bounded at 2 seconds; request timeout is hard-bounded at 8 seconds (`Duration.ofSeconds(8)`). Crucially, these calls execute inside a separate fixed executor pool (`bankDispatchExecutor`), completely decoupled from Tomcat request threads and holding zero DB connections.

4. **Idempotency In-Progress Spin-Wait**:
   - *Wait point*: Concurrent requests arriving with an identical `Idempotency-Key` while the initial request is in progress.
   - *Bound*: Bounded by a 2.5-second polling loop with 50 ms backoff. If the in-flight request does not complete within 2.5 seconds, it aborts and returns `409 REQUEST_IN_PROGRESS`.

---

### Q2: Database Restart During Payout
Scenario: PostgreSQL restarts after funds are reserved in Step A, but before the bank response or callback is processed in Step C.

1. **Reservation Preservation**: The reservation transaction committed in Phase A before Postgres went down. PostgreSQL WAL ensures crash durability. The wallet row retains `reserved = reserved + 5000` and the payout row exists with `status = 'PENDING'`. Available funds remain safe (`available = balance - reserved`), preventing any double-spend or overdraft.
2. **Bank Interaction During DB Outage**: The asynchronous bank dispatch thread was already dispatched (or is attempting HTTP dispatch). If the bank finishes while Postgres is restarting:
   - If a callback arrives at either instance on `/mpesa/v1/callbacks/bank`, the DB connection fails (`503`). The fake bank retries every 3 seconds up to 5 times.
   - If the async HTTP call receives bank acceptance or rejection, its attempt to open a DB transaction fails and drops quietly.
3. **Recovery After DB Restart**: Once Postgres restarts, the next callback retry succeeds, acquires the payout row lock, and finalizes the ledger. If callbacks were lost or never arrived (e.g. 504 hang), `PayoutReconciler` triggers via `@Scheduled(fixedDelay = 2000)`, claims the pending payout using `SELECT ... FOR UPDATE SKIP LOCKED`, queries `GET /bank/payouts/{payout_id}`, and finalizes the payout to `SUCCEEDED` (debiting balance & releasing reservation) or `FAILED` (releasing reservation only). No funds are ever lost or permanently stuck.

---

### Q3: What You Left Out
1. **API 5.6 (B2C Batch Disbursements)**:
   - *Why it is hard*: Processing up to 5,000 disbursements while maintaining <300 ms p95 on concurrent transfers out of the source wallet. Holding a row lock across 5,000 sequential row debits would starve transfers for seconds.
   - *How to build it*: Chunk the batch into smaller atomic sub-transactions (e.g., chunks of 50 rows). In each chunk, acquire source wallet lock briefly, debit chunk amount, update individual recipient rows, and commit immediately. Resubmissions under the same `batch_id` compare a SHA-256 hash of all rows stored on the batch master record to reject changes with 409, while returning current progress for matching batches.

2. **Full End-to-End Test Suite S6 (Disbursements)**:
   - Left out to prioritize rock-solid verification and zero-leak guarantees on the core money paths: P2P transfers (S1), reversals (S2), wallet freezes (S3), and asynchronous bank payouts & callbacks (S4, S5).

---

## Decisions Left Open by Specification

1. **Transaction Isolation Level**:
   - *Choice*: `READ COMMITTED` paired with explicit pessimistic locking (`SELECT ... FOR UPDATE`).
   - *Rationale*: PostgreSQL's `SERIALIZABLE` level introduces serialization failures (`40001`) under high write concurrency, forcing complex retry loops that could exhaust the 10-connection pool. Explicit deterministic lock ordering (`min(w1, w2)` then `max(w1, w2)`) under `READ COMMITTED` guarantees serial consistency without serialization aborts or deadlocks.

2. **Concurrent Idempotency Handling**:
   - *Choice*: Poll with backoff up to 2.5 seconds, then fallback to `409 REQUEST_IN_PROGRESS`.
   - *Rationale*: S1.2 requires that concurrent duplicate requests return the original `transfer_id`. Short polling ensures concurrent threads wait for the winning thread's commit and replay the cached 201 response seamlessly.

3. **Decoupled Payout Architecture (Saga / Three-Phase Payout)**:
   - *Choice*: Split payout into Phase A (Reserve funds & create PENDING record in DB <15ms), Phase B (Non-blocking HTTP dispatch without holding DB connection), and Phase C (Finalize upon callback or background polling).
   - *Rationale*: Guarantees <500 ms p95 response time regardless of bank latency (2–60s) and protects Hikari's 10-connection pool from starvation.
