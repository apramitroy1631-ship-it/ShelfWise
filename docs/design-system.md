# Design system (UI v3)

*Locked 2026-11-10 ([DL-11](decision-log.md#dl-11-ui-design-system-v3-light--dark)).* The single source of truth for how the web and mobile apps look. Under DL-08 the two apps share no code, so each app copies these values into its own theme file; if a value changes, it changes here first.

Mockups (static HTML, open in a browser):
- [Light theme](design/ui-v3-light.html)
- [Dark theme](design/ui-v3-dark.html)

The brief does not grade UX polish, so this spec aims for "clean, readable, consistent" and stays small enough to apply with a component library's theme instead of custom CSS.

---

## 1. Principles

1. **Colour carries meaning, not decoration.** The structure is neutral (navy and off-white); royal blue marks only the thing to act on (main button, link, selection, focus). Red is kept for real problems (low stock, reject, errors).
2. **Status is never colour alone.** Every status chip is **icon + word + colour**, so it works for colour-blind users and on dim store screens.
3. **Readable first.** Body text is at least 7:1 contrast; primary buttons and links at least 4.5:1, in both themes.
4. **Dense, scannable tables** on the web; **cards** on mobile.
5. **One theme file per app**, with light and dark defined together. The apps follow the device setting by default, with a manual toggle.

## 2. Colour tokens

| Token | Use | Light | Dark |
|---|---|---|---|
| `nav-bg` | Web sidebar, mobile header and tab bar (dark) | `#0E1A3A` | `#080D1A` |
| `nav-active` | Active menu item, store switcher | `#1C2B57` | `#16224A` |
| `nav-text` | Sidebar text | `#AEB9DB` | `#97A2BE` |
| `canvas` | Page background | `#F6F5F1` | `#0B1020` |
| `surface` | Cards, tables, panels | `#FFFFFF` | `#121A2E` |
| `surface-sub` | Table header, top bar, inputs | `#FAFAF8` | `#0F1628` |
| `border` | Card and control borders | `#E7E5DF` | `#24304D` |
| `divider` | Row dividers | `#EEECE6` | `#1C2640` |
| `text` | Body text | `#111827` | `#E6E9F2` |
| `text-strong` | Titles, KPI numbers | `#111827` | `#F3F5FA` |
| `text-2` | Secondary text, labels | `#4B5563` | `#A3ACC2` |
| `text-muted` | Placeholders, hints | `#6B7280` | `#6F7A94` |
| `primary` | Primary button fill (white text) | `#2F55D4` | `#3B5BDB` |
| `accent` | Links, icons, sparklines, selected-row bar, focus ring | `#2F55D4` | `#7C96F2` |
| `accent-tint` | Info banner, bulk-action bar | `#E9EEFC` / text `#1E3A8A` | `#16224A` / text `#C7D2FE` |
| `selected-row` | Selected table row (plus 3px `accent` left bar) | `#F7F9FF` | `#141F3D` |
| `danger` | Low stock, reject, errors (text) | `#B91C1C` | `#F87171` |
| `danger-border` | Low-stock card edge | `#F3C4C4` | `#5B2328` |
| `danger-solid` | Unread badge, low-stock bar | `#DC2626` | `#EF4444` |
| `trend-up` / `trend-down` | KPI change | `#15803D` / `#B45309` | `#4ADE80` / `#FBBF24` |
| `count-badge` | "Waiting" counts in the menu | `#F59E0B` | `#F59E0B` |

**In dark mode, depth comes from lighter surfaces, not shadows:** `nav-bg` (darkest) → `canvas` → `surface`. The dark `accent` is lighter than the dark `primary` because the light-mode blue is too dark to read as text on navy.

## 3. Status chips

Pill shape, 11px medium text, a 12px icon before the word.

| Status | Icon (Tabler) | Light bg / text | Dark bg / text |
|---|---|---|---|
| Pending | `clock` | `#FEF3C7` / `#92400E` | `#3A2C0C` / `#FCD34D` |
| Packed | `package` | `#E0E7FF` / `#1E3A8A` | `#222C5C` / `#B4C0FF` |
| Shipped | `truck` | `#CCFBF1` / `#115E59` | `#0E3A37` / `#5EEAD4` |
| Delivered | `circle-check` | `#DCFCE7` / `#166534` | `#103A24` / `#86EFAC` |
| Cancelled | `circle-x` | `#F3F4F6` / `#374151` | `#252B38` / `#CBD2DE` |

Cancelled is grey, not red: it is a final state, not an error. Purchase-order states reuse the same families: Draft = grey, Submitted / awaiting approval = amber (`users` icon when two approvers are needed), Approved = green, Rejected = red (`x`), Partly received = teal, Received = green.

## 4. Typography and numbers

- **Inter**, with tabular figures (`font-feature-settings: "tnum"`) so amounts and stock counts line up.
- Weights: 400 body, 500 labels and buttons, 600 page titles and KPI numbers.
- Sizes (web): page title 16–18px, body and table 12.5–13px, labels 11px. Mobile body is at least 13px.
- Money: `₹` with Indian grouping (`₹1,28,400`). Lakh shorthand (`₹18.4L`) is used only on KPI cards.
- Text is sentence case. Buttons start with a verb ("New order", "Export CSV", "Reject with reason").

## 5. Web layout

- **Sidebar** (`nav-bg`, about 132px wide):
  - Store switcher at the top. Admin sees "All 5 stores" with a dropdown; a store manager sees their own store with no dropdown.
  - Menu: Dashboard, Orders, Inventory, Catalogue, Purchase orders (with a waiting count), Suppliers, Settings. Items are shown or hidden by role.
  - User block at the bottom: initials, name, role, theme toggle.
- **Top bar:** global search, and a bell with an unread count.
- **Maintenance banner** (remote config) directly under the top bar, using `accent-tint`. It is not dismissable while the setting is on.
- **List pages** (orders, inventory, catalogue, POs), following the Shopify Polaris index layout, top to bottom:
  1. title and subtitle (scope and date range), with page actions on the right (secondary "Export CSV", primary "New …")
  2. KPI cards where relevant: up to 4, each with a sparkline and "vs previous 30 days". The low-stock card links to inventory filtered to low stock
  3. a toolbar: search, status, store, date range, sort (newest first by default)
  4. a bulk-action bar, shown only when rows are selected
  5. the table: 40px rows, sticky header, row dividers (no zebra stripes), amounts right-aligned, never centred
  6. a pager ("1–20 of N")
- **Selection** is a 3px `accent` left bar plus `selected-row`, so it can't be confused with a status colour.
- **One primary (filled) button per view.** Everything else is outlined.

## 6. Purchase-order approval

- **Header:** PO number, supplier and store; the amount large on the right, with a "Needs 2 approvers" chip when it is above ₹50,000.
- **Step tracker:** Submitted → Approver 1 → Approver 2 (when needed) → Receiving ("x of y lines" for partial receipt). Done steps are green with a check; the current step has an `accent` outline and says "You" when it is the viewer's turn.
- **Context line:** number of lines, the attached invoice, and why the PO was raised (e.g. "4 items below threshold").
- **Actions:** "Reject with reason" (danger outline; the reason is required) and "Approve" (primary). Never "OK" or "Submit".

## 7. Mobile (store managers)

- **Header** (`nav-bg`): store name, screen title, and the bell with a count. A search field sits in the header.
- **Quick filter chips** under the header (e.g. "All", "Low 17").
- **Cards instead of tables.** Each card shows name, SKU and quantity, with a chevron. A low-stock card gets a `danger-border`, a 3px red left bar and a reason ("Below 10"). Tapping a card opens that item's own detail or adjust screen.
- **Bottom tabs:** at most 5, **always with text labels** (currently Home, Stock, Orders, Invoices). The active tab is in `accent`.
- **Touch targets** are at least 44pt (iOS) / 48dp (Android).

## 8. States (every list and form)

- **Loading:** skeleton rows or cards, not spinners over the whole page.
- **Empty:** a short invitation and the next action ("No orders match these filters" + "Clear filters").
- **Error:** what happened and what to do, in plain words; never a raw exception.
- **Focus:** a visible 2px `accent` focus ring on every control (keyboard users).

## 9. Implementation notes

- **Web:** a component library's theme (to be chosen with the web build) holds both colour schemes; components read tokens, never raw hex values.
- **Mobile:** the same tokens in the mobile app's own theme file (React Native Paper or equivalent).
- **Theme choice:** follow the operating-system setting by default, with a manual toggle stored on the device. Dark mode is a stretch goal: build it only after the core features work, because the brief does not grade polish.
- The HTML mockups are references only; they are not shipped and are not app code.

## 10. Research this is based on

- Nielsen Norman Group, [Data tables: four major user tasks](https://nngroup.com/articles/data-tables): find, compare, view or edit, and act on records.
- Pencil & Paper, [Enterprise data tables](https://www.pencilandpaper.io/articles/ux-pattern-analysis-enterprise-data-tables): row density, right-aligned tabular numbers, sticky headers, no zebra stripes, bulk actions on selection.
- Shopify Polaris, [Resource index layout](https://polaris.shopify.com/patterns/resource-index-layout) and [Density](https://polaris.shopify.com/design/layout/density): title and actions, then filters, sort and bulk actions, then the list; dense layouts for comparison.
- IBM Carbon, [Status indicator pattern](https://v10.carbondesignsystem.com/patterns/status-indicator-pattern/): combine icon, shape, colour and text; fixed colour meanings; about 5–6 statuses at most.
- Smashing Magazine, [Bottom navigation](https://smashingmagazine.com/2016/11/the-golden-rules-of-mobile-navigation-design): at most 5 tabs, labelled icons; Apple and Material minimum touch targets (44pt / 48dp).
- Rossul, [warehouse app case study](https://www.rossul.com/portfolio/warehouse-management-system/): high contrast and bold status icons for staff working in glare or dim light.
- ONES and Procurify on approval flows ([ONES](https://ones.com/blog/?p=1123684), [Procurify](https://www.procurify.com/blog/purchase-approval-workflows/)): explicit Approve and Reject, full context on one screen, a step tracker for multi-step approval.
