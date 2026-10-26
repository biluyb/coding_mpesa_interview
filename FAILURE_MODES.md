# Failure Modes Analysis

## Global & Infrastructure
- Holding a database connection while waiting on external bank HTTP call exhausts the 10-connection Hikari pool, starving all other requests: Prevented by reserving funds in a short DB transaction (<5ms), returning the DB connection to the pool, and dispatching the HTTP call to the bank on a dedicated async thread pool without holding any DB connection.
- Exhausting Tomcat 50-thread budget due to synchronous blocking calls to external bank or slow downstream: Prevented by returning 202 Accepted immediately (<20ms) and offloading bank communication to an asynchronous executor.
- Unhandled database deadlock when concurrent transfers acquire wallet locks in opposite orders: Prevented by strict lexicographical lock ordering: always locking `min(wallet_a, wallet_b)` before `max(wallet_a, wallet_b)` using `SELECT ... FOR UPDATE`.
- Multiple instances concurrently attempting to process the same idempotency key create duplicate transactions: Prevented by atomic insert into PostgreSQL `idempotency_keys` table with primary key uniqueness.
- Reusing an idempotency key with a mutated payload succeeds instead of returning 409 conflict: Prevented by storing SHA-256 hash of endpoint and canonical body; mismatched hashes immediately trigger 409 IDEMPOTENCY_CONFLICT.
- Race condition on idempotency key insertion allows two concurrent requests to both proceed to execution: Prevented by DB primary key constraint; only one thread succeeds to insert 'IN_PROGRESS', the other polls the record with backoff or returns 409 REQUEST_IN_PROGRESS.
- JVM crash or kill -9 on one instance leaves in-flight operations or reservations orphaned permanently: Prevented by `PayoutReconciler` scheduled polling task with `SELECT ... FOR UPDATE SKIP LOCKED` that detects pending payouts > 5s and queries the bank's status endpoint, finalizing within 2 minutes.
- Transient database connection drop causes in-doubt state where balance was debited but client received 500 error: Prevented by handling database connection exceptions in `GlobalExceptionHandler` returning 503 SERVICE_UNAVAILABLE and ensuring atomic rollback via Spring `@Transactional`.

## 5.1 POST /mpesa/v1/p2p/transfers
- Concurrent transfers from the same wallet overdraft balance below zero due to non-atomic read-then-write: Prevented by row-level locking (`SELECT ... FOR UPDATE`) and database constraint `CHECK (balance >= reserved)`.
- Transfer to self allows balance inflation or unexpected lock self-deadlock: Prevented by validation rejecting `from_wallet.equals(to_wallet)` with 422 SAME_WALLET prior to database locking.
- Debit succeeds on sender but credit fails on receiver leaving total money supply reduced: Prevented by wrapping both debits and credits in an atomic database transaction.
- Concurrent transfer from wallet undergoing freeze commits after the freeze was acknowledged 200: Prevented by acquiring wallet row lock and checking `status == 'ACTIVE'` within the transfer transaction.
- Debit succeeds while ignoring active reservations on sender wallet, causing available funds to drop below zero: Prevented by checking `available = balance - reserved >= amount` while holding the row lock.
- Database exception during transfer emits raw SQL or driver error stack trace leaking internal schema: Prevented by `GlobalExceptionHandler` catching database exceptions and mapping them to standardized Section VI JSON errors.

## 5.2 POST /mpesa/v1/p2p/transfers/{id}/reversal
- Concurrent reversal requests for the same transfer execute twice, duplicating credited funds: Prevented by locking the transfer record (`FOR UPDATE`), verifying status is `COMPLETED`, and database unique constraint on `reversals.transfer_id`.
- Reversal debits recipient wallet below zero available balance when recipient already spent received funds: Prevented by locking recipient wallet row, checking `available >= amount`, and rejecting with 422 INSUFFICIENT_FUNDS if funds are insufficient.
- Reversing an in-flight, failed, or already reversed transfer alters ledger incorrectly: Prevented by checking transfer status, returning 409 ALREADY_REVERSED if already reversed, or 422 NOT_REVERSIBLE if not COMPLETED.
- Reversal is rejected because original sender is now FROZEN, even though returning money to frozen wallet is required to succeed: Prevented by allowing credited wallet to be ACTIVE or FROZEN per Section 5.2.
- Race condition between a normal transfer out of recipient wallet and reversal creates double-spend: Prevented by row-level lock on recipient wallet enforcing serial execution.

## 5.3 GET / PUT /mpesa/v1/wallets/{id}
- Non-atomic snapshot calculation allows GET to report available != balance - reserved: Prevented by calculating `available = balance - reserved` within a single row read.
- Freeze request blocks on slow concurrent transfers causing freeze PUT to exceed 2-second SLA: Prevented by lightweight status update with row lock that commits in milliseconds.
- Freeze request updates database status but uncommitted in-flight transfers commit afterwards without checking status: Prevented by in-flight transfers validating wallet status while holding the row lock.
- Repeated freeze or unfreeze calls create unnecessary database locks or errors: Prevented by idempotent status updates returning 200 without side effects.

## 5.4 POST /mpesa/v1/payouts
- Payout endpoint blocks waiting on slow external bank (2-6s or 60s timeout), exceeding 500ms p95 SLA: Prevented by reserving funds in <20ms DB transaction, returning 202 Accepted immediately, and dispatching to bank asynchronously.
- Payout deducts balance immediately instead of reserving funds, causing balance to decrease before bank acceptance: Prevented by incrementing `reserved` on acceptance and decrementing `balance` only upon confirmed bank success.
- Payout reserves funds without atomic available balance verification, allowing reserved > balance: Prevented by checking `available >= amount` under row lock and enforced by `CHECK (balance >= reserved)` database constraint.
- Failed payout fails to release reservation, locking user's funds permanently: Prevented by `finalizeFailed` decrementing `reserved` without touching `balance`.
- Submitting duplicate payout with same idempotency key sends duplicate payout_id to external bank: Prevented by idempotency deduplication returning cached 202 response.
- Bank 504 timeout or network drop treated as hard failure, releasing reservation while bank actually moved the money: Prevented by treating timeouts as unknown outcome (retaining reservation) and allowing reconciler or callback to resolve final state.
- Instance killed after reserving funds and dispatching to bank leaves payout pending past 2-minute deadline: Prevented by `PayoutReconciler` running across surviving instances with `SKIP LOCKED` to finalize within 2 minutes.

## 5.5 POST /mpesa/v1/callbacks/bank
- Duplicate callbacks arriving simultaneously execute double balance deduction or double reservation release: Prevented by row-level lock on payout record and idempotently ignoring callbacks if status is already terminal.
- Callback arrives before payout submit call returns, finding no payout record and failing or hanging: Prevented by inserting and committing payout record before the async bank HTTP call is dispatched.
- Bank callback reports FAILED after payout was already marked SUCCEEDED (or vice versa), creating conflicting ledger state: Prevented by leaving recorded terminal status intact, setting `conflict: true`, and returning 200.
- Callback handler takes longer than 2 seconds, triggering aggressive bank callback retries: Prevented by keeping callback processing strictly limited to local indexed DB row updates (<10ms).
- Callback for unknown payout_id does not return 404, preventing bank from properly retrying: Prevented by checking payout existence and returning 404 PAYOUT_NOT_FOUND if absent.

## 5.6 POST /mpesa/v1/b2c/disbursements
- Processing 5,000 disbursement rows in a single monolithic transaction holds wallet lock for >60s and blocks P2P transfers: [Not handled in minimal core - described in DESIGN.md]
- Row failure (e.g. invalid recipient) aborts the entire batch instead of processing remaining rows independently: [Not handled in minimal core - described in DESIGN.md]
- Resubmitting same batch_id re-executes already succeeded rows, paying recipients twice: [Not handled in minimal core - described in DESIGN.md]
- Resubmitting same batch_id with altered rows executes silently instead of returning 409 conflict: [Not handled in minimal core - described in DESIGN.md]
- Duplicate row_id within the same batch executes multiple times instead of failing with DUPLICATE_ROW: [Not handled in minimal core - described in DESIGN.md]

## 5.7 GET /mpesa/v1/wallets/{id}/statement
- Pagination cursor breaks balance chain invariants when concurrent transfers occur between page reads: Prevented by encoding snapshot max sequence ID into opaque cursor, freezing snapshot view.
- Statement query performs full table scan on 1,000,000 entries exceeding 150ms latency budget: Prevented by B-tree index on `(wallet_id, seq DESC)`.
- Unconfirmed reservations appear as statement ledger entries before payout actually succeeds: Prevented by only creating statement entry when payout transitions to SUCCEEDED.
- Off-by-one error or cursor drift returns the same statement entry across adjacent pages: Prevented by strictly monotonic sequence cursor (`seq < cursorSeq`).