# Edge-case register

For each feature: what could go wrong, how ShelfWise handles it, and how that is proved.
Proof is an automated test (run in CI) unless marked otherwise. Data-import cases are listed under D1–D32 in [data-findings.md](data-findings.md) and will get tests with the import.

## Database rules (all features)

| What could go wrong | How it's handled | Proof |
|---|---|---|
| Two orders take the last units of stock at the same moment | Stock is taken with one atomic "take N only if at least N are left" update; the database also forbids stock < 0 | `SchemaRulesTest.concurrentTakesNeverOversell_D29` (10 concurrent takes on 5 units → exactly 5 succeed, stock 0) |
| A bug writes negative stock | `CHECK (quantity >= 0)` in the database | `SchemaRulesTest.stockCannotGoBelowZero_D5_D29` |
| The same finance approver approves a PO twice | Unique (PO, approver) | `SchemaRulesTest.sameApproverCannotApproveTwice_D31` |
| Two approvers both become "1st approval" by clicking at once | Unique valid approval per step | `SchemaRulesTest.onlyOneValidApprovalPerStep_D31` |
| A product is created in the app without a price | Database check (only imported products may lack a price, D2) | `SchemaRulesTest.productCreatedInAppNeedsAPrice_D2` |
| A store manager exists without a store | Database check (D26) | `SchemaRulesTest.storeManagerMustBelongToAStore_D26` |
| Two users with the same email in different case | Unique index on lower(email) | `SchemaRulesTest.emailsAreUniqueIgnoringCase` |

## Sign-in and access

| What could go wrong | How it's handled | Proof |
|---|---|---|
| Someone guesses emails to find which accounts exist | Same 401 message for wrong password, unknown email and inactive user; unknown emails still run a password check (similar timing) | `AuthFlowTest.wrongPasswordAndUnknownEmailGetTheSameAnswer` |
| An inactive user (e.g. U12) signs in | Rejected with the generic message (D20) | `AuthFlowTest.inactiveUserCannotSignIn_D20` |
| A user is deactivated while signed in | Every request re-reads the user, so the next request and the next refresh are rejected (D20) | `AuthFlowTest.deactivatedUserIsCutOffOnTheirNextRequest_D20` |
| A token is forged or edited | Signature, expiry and issuer are checked on every request | `AuthFlowTest.requestsWithoutAValidTokenAreRejected` |
| A refresh token is stolen and used after the owner refreshed | Refresh tokens are single-use; reusing a revoked one ends **all** sessions of that user | `AuthFlowTest.reusingAnOldRefreshTokenEndsAllSessions` |
| Two refreshes race with the same token | Revocation is one atomic update; the loser is treated as reuse | Covered by the same atomic update; dedicated race test to add |
| Sign-out is clicked twice, or with an unknown token | Always 204, nothing breaks | `AuthFlowTest.logoutRevokesTheRefreshTokenAndIsSafeToRepeat` |
| The password hash leaks through the API or logs | API returns only id, name, role, store; `toString()` of users and requests hides secrets | `AuthFlowTest.responsesNeverContainThePasswordHash` |
| Empty or malformed sign-in request | Validation returns 400 | `AuthFlowTest.invalidRequestBodiesAreRejected` |
| Email typed with different capitals | Matched case-insensitively | `AuthFlowTest.emailIsCaseInsensitive` |
| Deployed with the local development signing key | Start-up fails if `JWT_SECRET` is shorter than 32 bytes; deployment must set its own | Start-up check in `AuthProperties`; deployment checklist (to add) |
| Repeated password guessing | **Not handled yet**: rate limiting is listed in known issues | — |
