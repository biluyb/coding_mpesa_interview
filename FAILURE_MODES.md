# Failure Modes Analysis

## Global & Infrastructure
- Holding a database connection while waiting on external bank HTTP call exhausts the 10-connection Hikari pool, starving all other requests: [Pending implementation]
- Exhausting Tomcat 50-thread budget due to synchronous blocking calls to external bank or slow downstream: [Pending implementation]
- Unhandled database deadlock when concurrent transfers acquire wallet locks in opposite orders: [Pending implementation]
- Multiple instances concurrently attempting to process the same idempotency key create duplicate transactions: [Pending implementation]
- Reusing an idempotency key with a mutated payload succeeds instead of returning 409 conflict: [Pending implementation]
- Race condition on idempotency key insertion allows two concurrent requests to both proceed to execution: [Pending implementation]
- JVM crash or kill -9 on one instance leaves in-flight operations or reservations orphaned permanently: [Pending implementation]
- Transient database connection drop causes in-doubt state where balance was debited but client received 500 error: [Pending implementation]

## 5.1 POST /mpesa/v1/p2p/transfers
- Concurrent transfers from the same wallet overdraft balance below zero due to non-atomic read-then-write: [Pending implementation]
- Transfer to self allows balance inflation or unexpected lock self-deadlock: [Pending implementation]
- Debit succeeds on sender but credit fails on receiver leaving total money supply reduced: [Pending implementation]
- Concurrent transfer from wallet undergoing freeze commits after the freeze was acknowledged 200: [Pending implementation]
- Debit succeeds while ignoring active reservations on sender wallet, causing available funds to drop below zero: [Pending implementation]
- Database exception during transfer emits raw SQL or driver error stack trace leaking internal schema: [Pending implementation]

## 5.2 POST /mpesa/v1/p2p/transfers/{id}/reversal
- Concurrent reversal requests for the same transfer execute twice, duplicating credited funds: [Pending implementation]
- Reversal debits recipient wallet below zero available balance when recipient already spent received funds: [Pending implementation]
- Reversing an in-flight, failed, or already reversed transfer alters ledger incorrectly: [Pending implementation]
- Reversal is rejected because original sender is now FROZEN, even though returning money to frozen wallet is required to succeed: [Pending implementation]
- Race condition between a normal transfer out of recipient wallet and reversal creates double-spend: [Pending implementation]

## 5.3 GET / PUT /mpesa/v1/wallets/{id}
- Non-atomic snapshot calculation allows GET to report available != balance - reserved: [Pending implementation]
- Freeze request blocks on slow concurrent transfers causing freeze PUT to exceed 2-second SLA: [Pending implementation]
- Freeze request updates database status but uncommitted in-flight transfers commit afterwards without checking status: [Pending implementation]
- Repeated freeze or unfreeze calls create unnecessary database locks or errors: [Pending implementation]

## 5.4 POST /mpesa/v1/payouts
- Payout endpoint blocks waiting on slow external bank (2-6s or 60s timeout), exceeding 500ms p95 SLA: [Pending implementation]
- Payout deducts balance immediately instead of reserving funds, causing balance to decrease before bank acceptance: [Pending implementation]
- Payout reserves funds without atomic available balance verification, allowing reserved > balance: [Pending implementation]
- Failed payout fails to release reservation, locking user's funds permanently: [Pending implementation]
- Submitting duplicate payout with same idempotency key sends duplicate payout_id to external bank: [Pending implementation]
- Bank 504 timeout or network drop treated as hard failure, releasing reservation while bank actually moved the money: [Pending implementation]
- Instance killed after reserving funds and dispatching to bank leaves payout pending past 2-minute deadline: [Pending implementation]

## 5.5 POST /mpesa/v1/callbacks/bank
- Duplicate callbacks arriving simultaneously execute double balance deduction or double reservation release: [Pending implementation]
- Callback arrives before payout submit call returns, finding no payout record and failing or hanging: [Pending implementation]
- Bank callback reports FAILED after payout was already marked SUCCEEDED (or vice versa), creating conflicting ledger state: [Pending implementation]
- Callback handler takes longer than 2 seconds, triggering aggressive bank callback retries: [Pending implementation]
- Callback for unknown payout_id does not return 404, preventing bank from properly retrying: [Pending implementation]

## 5.6 POST /mpesa/v1/b2c/disbursements
- Processing 5,000 disbursement rows in a single monolithic transaction holds wallet lock for >60s and blocks P2P transfers: [Pending implementation]
- Row failure (e.g. invalid recipient) aborts the entire batch instead of processing remaining rows independently: [Pending implementation]
- Resubmitting same batch_id re-executes already succeeded rows, paying recipients twice: [Pending implementation]
- Resubmitting same batch_id with altered rows executes silently instead of returning 409 conflict: [Pending implementation]
- Duplicate row_id within the same batch executes multiple times instead of failing with DUPLICATE_ROW: [Pending implementation]

## 5.7 GET /mpesa/v1/wallets/{id}/statement
- Pagination cursor breaks balance chain invariants when concurrent transfers occur between page reads: [Pending implementation]
- Statement query performs full table scan on 1,000,000 entries exceeding 150ms latency budget: [Pending implementation]
- Unconfirmed reservations appear as statement ledger entries before payout actually succeeds: [Pending implementation]
- Off-by-one error or cursor drift returns the same statement entry across adjacent pages: [Pending implementation]
