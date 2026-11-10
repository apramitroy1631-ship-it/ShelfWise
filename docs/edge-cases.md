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

## Data import (slice 2: suppliers, products, stock)

| What could go wrong | How it's handled | Proof |
|---|---|---|
| A SKU with a trailing space or in lowercase duplicates a product (12 pairs) | Kept as separate products; the variant is stored trimmed and lowercase (D1) | `DataImportTest.variantSkusAreSeparateLowercaseProducts_D1`, `SkuResolverTest` |
| A stock row is attached to the wrong one of a pair | Stock and PO rows match by exact spelling (D1 step 3) | `DataImportTest.variantStockRowsBelongToTheVariantProduct_D1_D4` |
| A messy SKU in an order points at the wrong product | Order lines use the matching ladder, with the sold price as tie-break (D1 steps 2–5) | `SkuResolverTest` (orders are imported in slice 3) |
| A product has no price | Imported with no price and flagged; blocked from sale until an admin sets one (D2) | `DataImportTest.productsWithoutAPriceAreImportedAndFlagged_D2` |
| An archived product loses its stock | Stock and history kept (D3) | `DataImportTest.archivedProductsKeepTheirStatus_D3` |
| Negative stock breaks the "never below 0" rule | Imported as 0, flagged for recount, with a history entry (D5) | `DataImportTest.negativeStockBecomesZeroWithACorrectionInHistory_D5` |
| A date is misread (6 formats, day/month order, 12 AM/PM, epoch) | One shared date reader; unreadable values are rejected, never guessed (D6, D9) | `ImportDatesTest` (16 cases), `DataImportTest.everyCountDateIsReadAsAnExactMoment_D6` |
| No suppliers file exists | Suppliers created from the IDs, named after them (D24) | `DataImportTest.suppliersAreCreatedFromTheirIds_D24` |
| A re-run undoes an admin's price, unarchive or recount | Insert-only: existing rows are never updated; no second correction entry (D28) | `DataImportRerunTest.reRunNeverOverwritesChangesMadeInTheApp_D28` |

## Data import (slice 3: customer orders and purchase orders)

| What could go wrong | How it's handled | Proof |
|---|---|---|
| Revenue is overstated or understated by bad order data | All order decisions applied together; delivered revenue (excluding the review queue) is exactly ₹1,45,38,055 | `DataImportTest.deliveredRevenueMatchesTheDecisions_D8_D10_D11`; also checked in the live database |
| Duplicate orders double-count revenue | Identical copies imported once; copies that differ are rejected for review (D8) | `DataImportTest.identicalDuplicatesAreImportedOnce_D8` |
| Order dates misread (5 formats incl. JSON numbers) | Shared date reader (D9) | `DataImportTest.everyOrderDateFormatIsRead_D9` |
| Negative quantities corrupt revenue | Order kept as received, placed in the admin review queue, excluded from revenue (D10) | `DataImportTest.negativeQuantityOrdersAreKeptAsReceivedAndQueuedForReview_D10` |
| A missing price is guessed | Filled only from evidence: bill total, then one consistent sales-history price within ±30 days equal to the catalogue price; otherwise review (D11) | `DataImportTest.missingPricesAreFilledFromSalesHistory_D11` |
| An unknown product breaks the import or the revenue | Line kept with no product link, counted, no review (D12) | `DataImportTest.unknownProductsAreKeptWithoutALinkAndNotReviewed_D12` |
| A messy SKU matches the wrong product | D1 ladder, with the sold price as tie-break (D13) | `DataImportTest.messySkusInOrdersFindTheirProduct_D13` |
| The same product appears twice in one order | Merged when prices match; never averaged otherwise (D14) | `DataImportTest.repeatedLinesAreMerged_D14` |
| Importing old orders changes stock a second time | Imported orders never move stock (D29) | `DataImportTest.importedOrdersNeverChangeStock_D29` |
| A PO's status contradicts what was received | Receiving status derived from quantities (D21) | `DataImportTest.receivingStatusFollowsTheQuantities_D21` |
| Approval order taken from list position | Ordered by time; same-second approvals noted (D31) | `DataImportTest.approvalOrderComesFromTheTime_D31` |
| A store manager's or creator's approval counts | Never counts (D18, D19) | `DataImportTest.storeManagerAndSelfApprovalsNeverCount_D18_D19` |
| Past rule-breaking approvals are rewritten, or money keeps flowing on them | Flagged, not rewritten; only unstarted or self-approved POs need re-approval (the progress rule) | `DataImportTest.wronglyApprovedPurchaseOrdersAreFlaggedNotRewritten_D17_D18_D19`, `onlyUnstartedOrSelfApprovedPurchaseOrdersNeedReapproval` |
| An inactive approver's past approvals are discarded | They stay valid (D20) | `DataImportTest.approvalsByTheNowInactiveApproverStayValid_D20` |

## Catalogue (M09)

| What could go wrong | How it's handled | Proof |
|---|---|---|
| A store manager or finance user changes products | POST and PUT on `/api/products/**` need the admin role (403 otherwise) | `CatalogueApiTest.storeManagersCannotChangeTheCatalogue` |
| Two admins edit the same product at once and one silently overwrites the other | Every edit carries the version it was loaded at; a stale version gets 409 | `CatalogueApiTest.anEditFromAStaleVersionIsRefused`, `simultaneousEditsLetExactlyOneWin` |
| A new product is created without a price | Rejected (400); only imported products may lack a price (D2) | `CatalogueApiTest.newProductsMustHaveAPriceACleanSkuAndAnActiveSupplier` |
| A new SKU recreates the D1 problem (differs only by case) | Rejected (409); new SKUs must use the clean format | `CatalogueApiTest.aSkuThatDiffersOnlyByCaseIsRefused_D1` |
| An admin removes a product's price | Refused: a price can change but not be removed | `CatalogueApiTest.aPriceCanBeChangedButNotRemoved` |
| Archive or unarchive is clicked twice | The second is 409; nothing changes | `CatalogueApiTest.archiveAndUnarchiveAreAuditedAndCannotBeRepeated_D3` |
| A search for `50%` or `_` matches everything | Wildcards in the search text are escaped | `CatalogueApiTest.searchTreatsPercentAsAPlainCharacter` |
| Sorting by an arbitrary column (SQL injection) | Sort keys come from a fixed list; anything else is 400 | `CatalogueApiTest.rejectsUnknownSortAndOversizedPages` |
| A huge page size overloads the server | Page size capped at 100 | same test |
| `SW-HOM-00033` and `sw-hom-00033` are confused | Lookup is exact and case-sensitive (D1) | `CatalogueApiTest.skuLookupIsCaseSensitive_D1` |
| A change leaves no trace | Every create, edit, price set, archive and unarchive is in the audit log, in the same transaction | `CatalogueApiTest.adminCreatesAProductAndItIsAudited` and others |

## Inventory (M10, M12)

| What could go wrong | How it's handled | Proof |
|---|---|---|
| Overlapping write-offs oversell or lose a change | Each change is one atomic "only if the result stays ≥ 0" update plus its history entry, in one transaction | `InventoryApiTest.overlappingAdjustmentsNeverOversellOrLoseAChange_M12` (10 concurrent on 5 units → 5 succeed, stock 0, history 4,3,2,1,0) |
| An adjustment takes stock below 0 | Refused with 409 showing the current stock; nothing changes | `InventoryApiTest.anAdjustmentThatWouldGoBelowZeroIsRefusedAndChangesNothing` |
| A manager reads or changes another store's stock | Store scope checked on the server for every call (403) | `InventoryApiTest.storeManagerSeesOnlyTheirOwnStore`, `onlyTheStoresManagerOrAnAdminCanAdjust` |
| Finance changes stock | Not allowed (403); admins and the store's managers only | same test |
| A stock change has no explanation | Every change has a reason code; "other" requires a note; all go into the history with who made it | `InventoryApiTest.anOtherAdjustmentNeedsANote`, `damagedStockIsRemovedAndRecordedWithItsReason` |
| An import-corrected row stays flagged forever | A recount sets the counted quantity and clears "recount needed" (D5) | `InventoryApiTest.recountSetsTheCountedQuantityAndClearsTheFlag_D5` |
| Leftover stock of an archived product can't be cleared | Adjustments are allowed on archived products (D3) | `InventoryApiTest.archivedProductsCanStillBeWrittenOff_D3` |

## Web app: sign-in

| What could go wrong | How it's handled | Proof |
|---|---|---|
| The user double-clicks "Sign in" | The button is disabled while the request runs | `App.test.tsx`: "disables the button while signing in" |
| Wrong email or password | The server's message is shown and the password field is cleared | `App.test.tsx`: "shows the server's message…"; also checked live in the browser on 2026-11-10 against the running backend |
| The server is unreachable | A plain explanation is shown instead of a crash | `App.test.tsx`: "explains when the server cannot be reached" |
| Signing out while offline | The local session is cleared anyway; the refresh token expires on its own | `api/auth.ts` `logout`; unit test for the online case |
| Tokens stolen from browser storage | Tokens are kept in memory only, never in localStorage | Code review: `App.tsx`. Trade-off: reloading signs you out (known issue) |
