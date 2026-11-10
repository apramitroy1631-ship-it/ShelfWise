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

## Data import (slice 1: stores and users)

| What could go wrong | How it's handled | Proof |
|---|---|---|
| The import is run twice (or the service restarts) | Insert-only by natural key: existing records are skipped (D28) | `StoreAndUserImportTest.runningTheImportAgainAddsNothing_D28` |
| A re-run overwrites a change made in the app | Existing records are never updated (D28) | `StoreAndUserImportTest.reRunNeverOverwritesChangesMadeInTheApp_D28` |
| The import fails half-way | The whole run is one transaction; nothing from a failed run is kept | `DataImportService` (transactional); failure test to add with the larger files |
| The original data is lost by corrections | Every added record is also stored exactly as received | `StoreAndUserImportTest.importsAllFiveStoresAndTwelveUsers` (17 raw records) |
| The inactive user (U12) becomes able to sign in | Imported as inactive and logged (D20, D27) | `StoreAndUserImportTest.inactiveUserStaysInactive_D20_D27`, `importedUsersCanSignInExceptTheInactiveOne` |
| A store's second manager is treated as less than a manager | Both are full managers; the file's manager is a "main contact" label only (D26) | `StoreAndUserImportTest.storesWithTwoManagersKeepTheFileManagerAsMainContact_D26` |
| The demo password is stored readable | Stored as a BCrypt hash; the import refuses to run without `DEMO_PASSWORD` (D25) | `StoreAndUserImportTest.passwordsAreStoredHashedNeverInPlainText_D25` |
| A user row has an unknown role, bad `active` value or a manager without a store | Row rejected and logged with its line number; the rest continues | `StoreAndUserImporter.validateUser`; test with a bad file to add |

## Web app: sign-in

| What could go wrong | How it's handled | Proof |
|---|---|---|
| The user double-clicks "Sign in" | The button is disabled while the request runs | `App.test.tsx`: "disables the button while signing in" |
| Wrong email or password | The server's message is shown and the password field is cleared | `App.test.tsx`: "shows the server's message…"; also checked live in the browser on 2026-11-10 against the running backend |
| The server is unreachable | A plain explanation is shown instead of a crash | `App.test.tsx`: "explains when the server cannot be reached" |
| Signing out while offline | The local session is cleared anyway; the refresh token expires on its own | `api/auth.ts` `logout`; unit test for the online case |
| Tokens stolen from browser storage | Tokens are kept in memory only, never in localStorage | Code review: `App.tsx`. Trade-off: reloading signs you out (known issue) |
