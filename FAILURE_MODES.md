# Failure Modes Analysis

## Global & Infrastructure
- Holding database connections during slow bank calls can exhaust the connection pool.
- Blocking HTTP calls can exhaust Tomcat threads.
- Concurrent wallet locks can cause database deadlocks.
- Concurrent requests can bypass idempotency and process the same key twice.
- Reusing a key with a different request body may cause incorrect results instead of a 409 conflict.
- Application crashes can leave payouts pending or funds reserved.
- Database failures can leave clients unsure whether a transaction succeeded.

## 5.1 P2P Transfers
- Concurrent transfers can overdraw a wallet.
- Transfers to the same wallet may cause incorrect results.
- Partial transfers can lose money if debit and credit are not atomic.
- Transfers may complete after a wallet is frozen.
- Ignoring reserved funds can make available balance negative.
- Database errors may expose internal details.

## 5.2 Transfer Reversals
- Concurrent reversals may refund the same transfer twice.
- Reversals can fail if the recipient has insufficient available funds.
- Reversing an incomplete or already reversed transfer can corrupt balances.
- A frozen wallet must still receive returned funds.
- Concurrent transfers and reversals can cause double-spending.

## 5.3 Wallets
- Inconsistent reads can return an incorrect available balance.
- Slow transfers can delay wallet freezing beyond two seconds.
- In-flight transfers may complete after a freeze is confirmed.
- Repeated status updates may cause unnecessary database work.

## 5.4 Payouts
- Slow bank responses can delay the payout API.
- Deducting funds too early can produce incorrect balances.
- Concurrent payouts can reserve more money than available.
- Failed payouts may leave funds reserved.
- Duplicate requests may submit the same payout more than once.
- A bank timeout may be mistaken for failure even when payment succeeded.
- An application crash may leave payouts unfinished.

## 5.5 Bank Callbacks
- Duplicate callbacks may update balances more than once.
- Early callbacks may arrive before the payout is ready to process them.
- Conflicting callbacks may overwrite a final payout status.
- Slow callback handling may trigger repeated bank requests.
- Unknown payouts must return 404 so the bank can retry.

## 5.6 Batch Disbursements
- Large transactions can lock wallets and delay transfers.
- One failed row must not stop the remaining rows.
- Repeated batches may pay recipients twice.
- Reusing a batch ID with different rows must return a conflict.
- Duplicate row IDs must be rejected without stopping other rows.

## 5.7 Wallet Statements
- Concurrent transactions can cause missing or duplicated entries across pages.
- Unindexed queries may be too slow for large statements.
- Pending payouts must not appear as completed ledger entries.
- Incorrect pagination can repeat or skip entries.

**Status:** All failure modes are identified; prevention measures are pending implementation and testing.