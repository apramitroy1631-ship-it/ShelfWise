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

## Orders (M11, M12)

| What could go wrong | How it's handled | Proof |
|---|---|---|
| Customers order the last units at the same moment | Stock taken with the atomic "only if enough is left" update per line; refused orders roll back completely | `OrdersApiTest.overlappingOrdersNeverOversell_M12` (5 orders for the last 3 → 3 succeed, stock 0) |
| One line is short and the others are already taken | The whole order is one transaction: nothing saved, no stock moved | `OrdersApiTest.ifAnyLineIsShortNothingIsSavedAndNoStockMoves` |
| An archived, unpriced, unknown product or a quantity of 0 is ordered | Refused by the "can this be sold?" rule (D30) | `OrdersApiTest.onlySellableProductsCanBeOrdered_D30` |
| A double tap or network retry creates two orders | Idempotency key: the retry returns the first order, stock taken once (D8) | `OrdersApiTest.retryingWithTheSameIdempotencyKeyCreatesOneOrder_D8` |
| The same product is added twice in the form | Combined into one line (D14) | `OrdersApiTest.theSameProductTwiceBecomesOneLine_D14` |
| A later price change rewrites old bills | Each line keeps its price at the time of sale (D15) | `OrdersApiTest.creatingAnOrderTakesStockAndSnapshotsPrices_D29_D15` |
| An order skips a step or a shipped order is cancelled | Only the brief's lifecycle is allowed (409 otherwise) | `OrdersApiTest.ordersMoveThroughTheirLifecycleOnly` |
| Cancelling twice returns stock twice | The status change is atomic; the second cancel is 409 | `OrdersApiTest.cancellingGivesTheStockBackOnce_D29` |
| Two people act on the same order from stale screens | Version check, 409 | `OrdersApiTest.aStaleVersionIsRefused` |
| Customer phone numbers leak | Masked in lists and to head office; full only to the store's managers (D16) | `OrdersApiTest.phonesAreMaskedExceptForTheStoresOwnManager_D16` |
| Orders forgotten in "pending" for months | Flagged "stuck" after the remote-config limit (D32) | `OrdersApiTest.oldOpenOrdersAreFlaggedStuck_D32` |
| Admin or finance creates customer orders, or a manager acts on another store | Only the store's managers create; store scope checked on the server | `OrdersApiTest.onlyStoreManagersCreateOrdersInTheirOwnStore`, `phonesAreMasked...` (403 for another store) |

## Purchase orders (M19)

| What could go wrong | How it's handled | Proof |
|---|---|---|
| Two finance approvers click "Approve" at the same moment | Each change first claims the PO by its version; the loser gets 409. The database's unique rules (one approval per person, one per step) are the backstop (D31) | `PurchaseOrdersApiTest.twoApproversAtTheSameMomentCountOnce_D31` (4 clicks at once → 1 approval, 1 event) |
| The same person approves twice to pass the ₹50,000 rule | Refused (409); the second approval must come from someone else (D31) | `PurchaseOrdersApiTest.aboveTheLimitTwoDifferentApproversAreNeeded` |
| The creator approves their own PO | Refused (403, D19) | `PurchaseOrdersApiTest.onlyFinanceDecidesAndNeverOnTheirOwnPo_D19` |
| A manager or admin approves | Only finance decides (403) | same test |
| Head office changes the ₹50,000 limit | Read from remote config on every decision; no redeploy | `PurchaseOrdersApiTest.theSecondApprovalLimitComesFromRemoteConfig` |
| A rejection with no explanation | Reason required (400); stored in the audit log | `PurchaseOrdersApiTest.aRejectionNeedsAReasonAndIsAudited` |
| Goods arrive for an imported PO that was wrongly approved | Blocked until finance re-approves (D17-D19); the compliance flag stays visible | `PurchaseOrdersApiTest.aFlaggedPoMustBeReapprovedBeforeGoodsArrive_D17_D19` |
| Finance rejects the re-approval of a partly received PO | The PO is closed; goods already received stay | `PurchaseOrdersApiTest.rejectingTheReapprovalOfAPartlyReceivedPoClosesIt` |
| More received than ordered, or a product that isn't on the PO | Refused (409 / 400); atomic "only if it stays within the order" update plus a database check; nothing moves | `PurchaseOrdersApiTest.cantReceiveMoreThanOrderedOrAProductNotOnThePo` |
| The same delivery is recorded twice at once | Version claim: one goes in, stock moves once | `PurchaseOrdersApiTest.twoReceiptsAtTheSameMomentCountOnce` |
| Goods arrive for a product the store never stocked | Its stock row is created with the product's reorder level | `PurchaseOrdersApiTest.receivingAProductNeverStockedInTheStoreCreatesItsStockRow` |
| A PO waits for goods for months | Flagged "stuck" after 30 days (remote config); the manager can close it with a reason, audited (D22) | `PurchaseOrdersApiTest.aStuckPoCanBeClosedWithAReason_D22` |
| A draft mixes suppliers, or has archived, unknown or zero-quantity products | Refused; nothing saved. Repeated lines are merged (D14) | `PurchaseOrdersApiTest.aDraftIsForOneSupplierAndActiveKnownProducts` |
| An expired draft is submitted | Refused (409, D22) | `PurchaseOrdersApiTest.anExpiredDraftCantBeSubmitted_D22` |
| A manager sees or acts on another store's POs | Store scope checked on the server (403) | `PurchaseOrdersApiTest.storeScopeAndRolesAreCheckedOnTheServer` |
| A decision is saved but its notification is lost (or the other way round) | The event is written to the outbox in the same transaction | `PurchaseOrdersApiTest.draftSubmitApproveAndReceiveInParts_D21` (checks the events) |
| App PO numbers clash with imported ones | App POs are numbered from PO-10000 (imported: PO-0001 to PO-0300) | `V3__purchase_order_numbers.sql` |

## Supplier invoices (M20)

| What could go wrong | How it's handled | Proof |
|---|---|---|
| A Windows program is renamed `.pdf` (`invoice_renamed.pdf`) | The type is decided from the file's first bytes, never its name or the type the browser claims; anything that isn't a real PDF, PNG or JPEG gets 415 and nothing is stored | `InvoicesApiTest.anExecutableRenamedToPdfIsRefused_byContentNotName` |
| A PDF is cut off part-way (`invoice_truncated.pdf`) | A PDF must end with its `%%EOF` marker (PNG: `IEND` chunk; JPEG: end marker), otherwise 422 "damaged or incomplete" | `InvoicesApiTest.aTruncatedPdfIsRefusedAsCorrupt` |
| A file over 20 MB (the 22 MB sample) | Read with a hard 20 MB cap, so a huge file is never held in memory; refused with 413. The test generates a file just over 20 MB (the sample isn't committed); exactly 20 MB is accepted | `InvoicesApiTest.aFileOver20MbIsRefused_andNothingIsStored`, `aFileExactlyAtTheLimitIsAccepted` |
| An empty file | 422 | `InvoicesApiTest.anEmptyFileIsRefused` |
| The file name is a trap (`..\..\evil/inv.exe`) | Only the bare name is kept and its ending follows the real type (`inv.pdf`); files are stored under a generated key, never the uploaded name | `InvoicesApiTest.folderPartsAndWrongEndingsInTheNameAreCleaned` |
| Replacing an invoice loses the old one | The old file becomes an "earlier version" and stays viewable; the database allows exactly one current invoice per PO | `InvoicesApiTest.replacingKeepsTheOldVersion_andOnlyOneIsCurrent` |
| Another store's manager, or finance, attaches a file; or someone not signed in lists them | Attaching is for that store's managers and admins (403 otherwise); viewing follows the PO's store scope; no token is 401 | `InvoicesApiTest.onlyThatStoresManagersCanAttach_financeCanOnlyView` |
| The file is saved but the database write fails (or the reverse) | File first, then the record in one transaction with its audit entry; if the record fails the stored file is removed. Two uploads at the same moment: the unique "one current" rule lets one win and the other gets 409 | design; `InvoicesApiTest` (happy path) |
| The real storage differs from the test stand-in | The S3 code runs against the same SeaweedFS image docker-compose uses (bucket made on first use; put, get, delete) | `S3ObjectStorageTest` |
| A viewer's browser guesses a different type and runs the file | Served with the type found from its contents and `X-Content-Type-Options: nosniff` | `InvoicesApiTest.attachesAValidPdfAndAnImage_andFinanceCanView` |
| A phone user picks a 22 MB file | The apps check the size first to save a slow upload; the server's check is the real one | web `InvoicePanel.test.tsx`, mobile `Invoices.test.tsx` |

## Dashboard and CSV export (M22)

| What could go wrong | How it's handled | Proof |
|---|---|---|
| The dashboard disagrees with the import decisions | Figures are computed from the orders on every request (nothing stored that could drift); the test runs on the real provided data | `DashboardApiTest.revenueIsDeliveredOrdersOnlyAndMatchesTheImportDecisions` (₹1,45,38,055) |
| Orders that may still be cancelled inflate revenue | Revenue counts delivered orders only (user decision 2026-11-11) | Same test |
| Cancellations disappear from the order count | Order count includes every status, with a split by status | `DashboardApiTest.theOrderCountIncludesEveryStatusAndLeavesOutOrdersWaitingForReview_D10` |
| Orders with wrong quantities (waiting for review) distort figures or the export | Left out until an admin reviews them (D10) | Same test; `theCsvLeavesOutOrdersWaitingForReviewAndRespectsStoreScope_D10` |
| Unknown product codes vanish from top products | Shown separately under their own heading (D12) | `DashboardApiTest.topProductsAreRankedByRevenueWithUnitsAndUnknownProductsSeparate_D12` (₹15,994) |
| Archived products inflate the low-stock count | Left out, same rule as notifications (D3, D7) | `DashboardApiTest.lowStockCountMatchesD7` (1,046 / 190) |
| An order late in the evening IST lands on the wrong day (UTC) | Date ranges and trend days are IST days (D9) | `DashboardApiTest.dateRangesAreIstDays_D9` |
| Store figures don't add up to the chain total | Same rules per store and in total | `DashboardApiTest.storesAndTrendAddUpToTheTotal` |
| A manager sees another store's figures or export | Store scope checked on the server (403) | `DashboardApiTest.managersSeeOnlyTheirOwnStore`, `theCsvLeavesOut...` |
| "to" before "from", or an unknown trend unit | Refused (400) | `DashboardApiTest.badRangesAreRefused` |
| The export leaks customer phone numbers | Phones are always masked in the file, even for the store's own managers (D16) | `DashboardApiTest.theCsvHasEveryFilteredOrderWithMaskedPhones_D16` |
| A customer name like `=HYPERLINK(...)` runs as a formula in Excel (CSV injection) | Text starting with = + - @ is prefixed with an apostrophe | `OrderExportTest.textThatExcelWouldRunAsAFormulaIsMadePlain` |
| Names with commas, quotes or line breaks break the columns | Quoted per RFC 4180 | `OrderExportTest.commasQuotesAndLineBreaksAreQuoted` |
| A large export runs the server out of memory | Rows are streamed from the database as they are read | `OrderExport` (streamed `Flux`); full-chain export of 14,945 orders in `DashboardApiTest` |
| Indian names show as garbled letters in Excel | UTF-8 with a byte-order mark | `OrderController.export` |

## Notifications (M21)

| What could go wrong | How it's handled | Proof |
|---|---|---|
| Day one floods managers with ~1,400 low-stock messages | Grouped per store + type with a live count (D7): 2 notifications per store | `NotificationsTest.aStoresManagersSeeOneGroupedNotificationPerType_D7`; live check 2026-11-11 (S01: 40 out, 144 low; total 1,046 as in D7) |
| A change is saved but its event is lost (crash between save and send) | Transactional outbox: the event is saved in the same transaction; the relay sends it later | Live check 2026-11-11: PO-10000's events waited in the outbox while no relay ran and arrived when it came back |
| A code path changes stock but forgets to send an event | A database trigger writes the marker on every inventory update that crosses a line | `OutboxRelayTest.theDatabaseMarksStockHealthChangesOnlyWhenAnItemCrossesALine` |
| The same event arrives twice (at-least-once) | Event ids are recorded; a repeat changes nothing | `NotificationsTest.repeatedOrLateEventsChangeNothing`, `EventListenerTest.anEventPublishedTwiceIsAppliedOnce` |
| An older count arrives after a newer one | Each snapshot carries `asOf`; older ones are ignored | `NotificationsTest.repeatedOrLateEventsChangeNothing` |
| Two copies of the core service publish the same events | `FOR UPDATE SKIP LOCKED`: each relay takes different rows | `OutboxRelayTest.twoRelaysAtOnceNeverPublishAnEventTwice` |
| Many stock changes in one store send a burst of messages | Markers are merged: one current-count message per store per batch | `OutboxRelayTest.markersBecomeOneMessageWithTheCurrentCounts_D7` |
| A restocked item keeps nagging | Unread again only when a count goes UP; at 0 the notification clears | `NotificationsTest.aGroupIsUnreadAgainOnlyWhenItsCountGoesUp_D7` |
| A manager reads the bell while an increase is on its way, and misses it | "New" is the time the notification service applied the increase, not when it was computed | Same test (it failed before this fix) |
| One of S01's two managers reading hides it from the other | Each person has their own "seen up to" time | `NotificationsTest.twoManagersOfOneStoreEachHaveTheirOwnUnreadState` |
| A first of two approvals tells the store "approved" | Only final outcomes reach the store | `NotificationsTest.theStoreHearsOnlyFinalPoDecisions` |
| Grouping hides how long finance has left POs waiting | The finance text shows the oldest waiting age | `NotificationsTest.financeSeesWaitingPosWithTheOldestAge_D7` |
| A broken message blocks the queue for ever | After 3 retries it goes to the dead-letter queue for a person to look at | `EventListenerTest.aMessageThatCantBeHandledGoesToTheDeadLetterQueue` |
| RabbitMQ is down | The core keeps selling (events wait in the outbox); the notification service keeps serving the bell and reconnects (2 s up to 30 s) | Retry code in `OutboxRelay` and `EventListener`; not yet proved by an automated test (known gap) |
| A forged or foreign token reads notifications | Same signing key and issuer as the core service are required | `NotificationsTest.theBellNeedsAValidTokenFromTheCoreService` |
| A manager marks another store's notification | Only your own audience can be marked (404 otherwise) | `NotificationsTest.nobodyCanMarkAnotherStoresNotification` |
| A newly received product (never stocked in the store) arrives below its reorder level | The trigger only watches updates; the nightly recount (M23) sends the corrected counts | `ScheduledJobsTest.theRecountSendsOneMarkerPerStoreAndKeepsTheCounts` |

## Scheduled jobs (M23)

| What could go wrong | How it's handled | Proof |
|---|---|---|
| Old drafts pile up for months | Drafts untouched for more than 14 days expire nightly, audited (D22) | `ScheduledJobsTest.draftsUntouchedForMoreThan14DaysExpire_D22` |
| Imported drafts look "just updated" (the import sets the update time) so they never expire | Imported drafts count from their creation date; app drafts from their last change | Same test (PO-0011 vs PO-10002) |
| A draft someone just worked on expires | Only drafts untouched for the whole limit; submitted POs are never touched | Same test |
| Two copies of the core service run the job at once and expire or count twice | A Postgres advisory lock: one runs, the other records SKIPPED | `ScheduledJobsTest.twoInstancesAtTheSameMomentRunTheJobOnce` |
| A user acts on a draft that expired in the meantime | Expiry bumps the version, so the stale screen gets 409; an expired draft can't be submitted | Same tests; `PurchaseOrdersApiTest.anExpiredDraftCantBeSubmitted_D22` |
| Head office wants a different expiry limit | Remote config, read on every run | `ScheduledJobsTest.theExpiryLimitComesFromRemoteConfig` |
| A live stock update missed a notification (e.g. first delivery of a product to a store) | The nightly recount sends every store's current counts again | `ScheduledJobsTest.theRecountSendsOneMarkerPerStoreAndKeepsTheCounts` |
| Nobody knows whether the nightly job ran | Every run (done, skipped, failed) is recorded with its details; admins can list them | `ScheduledJobsTest.aSecondRunFindsNothingMore`, `onlyAdminsRunJobsAndSeeRuns` |
| A job crashes half-way | One transaction per run: nothing half-done is saved; the failure is recorded | `ScheduledJobs.run` (rollback + FAILED record) |
| A non-admin runs a job | 403 | `ScheduledJobsTest.onlyAdminsRunJobsAndSeeRuns` |

## Remote configuration (M27)

| What could go wrong | How it's handled | Proof |
|---|---|---|
| A behaviour change needs a redeploy or an app-store release | Settings live in the database; the server reads them per request, apps poll every 60 s | `SettingsApiTest.aChangedLimitIsUsedRightAway`, `anAdminTurnsTheMaintenanceBannerOnAndOff` |
| The banner can't show on the login page (no token yet) | `/api/config` needs no sign-in and returns public settings only | `SettingsApiTest.theAppsReadPublicSettingsWithoutSigningIn`; web `SettingsPage.test.tsx` |
| Internal limits leak to anyone | Only `ui.*` and `features.*` are public | Same test |
| An admin types a nonsense value (negative limit, text in a number, 2,000-character banner) | Each setting has a rule; refused with the rule in the message; nothing saved | `SettingsApiTest.valuesMustFitTheSettingsRule`; web shows the message |
| Someone creates a setting nobody reads | Only known settings exist; unknown keys are 404 | Same test |
| Two admins overwrite each other | Version check, 409 | `SettingsApiTest.aStaleVersionIsRefused` |
| Nobody knows who switched the banner on | Every change is audited (who, old → new) | `SettingsApiTest.anAdminTurnsTheMaintenanceBannerOnAndOff` |
| A non-admin changes settings | 403 | `SettingsApiTest.onlyAdminsSeeAndChangeAllSettings` |
| The config server answers something unexpected and the app breaks | Apps treat it as "no banner" and keep the last good values | web `App.test.tsx` (other answers on /api/config), `useConfig.ts` |
| A phone was in the background when the banner changed | Mobile reloads config whenever the app comes back to the foreground | `mobile/src/useConfig.ts` (AppState); mobile `App.test.tsx` banner test |

## Web app: sign-in

| What could go wrong | How it's handled | Proof |
|---|---|---|
| The user double-clicks "Sign in" | The button is disabled while the request runs | `App.test.tsx`: "disables the button while signing in" |
| Wrong email or password | The server's message is shown and the password field is cleared | `App.test.tsx`: "shows the server's message…"; also checked live in the browser on 2026-11-10 against the running backend |
| The server is unreachable | A plain explanation is shown instead of a crash | `App.test.tsx`: "explains when the server cannot be reached" |
| Signing out while offline | The local session is cleared anyway; the refresh token expires on its own | `api/auth.ts` `logout`; unit test for the online case |
| Tokens stolen from browser storage | Tokens are kept in memory only, never in localStorage | Code review: `App.tsx`. Trade-off: reloading signs you out (known issue) |
