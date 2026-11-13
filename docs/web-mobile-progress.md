# Web and mobile progress

What was built in the web app (`web/`) and the mobile app (`mobile/`), in the order it was done. Backend work is not listed here.
Times are commit times in IST. Each entry names its commit.

## Tuesday 10 November 2026

| Time | App | What was done | Commit |
|---|---|---|---|
| 14:32 | Web | Web app set up (React, TypeScript, Vite) with the sign-in page and its own CI workflow | `6cb9483` |
| 15:58 | Web | The web CI workflow can also be started by hand | `b13abad` |
| 20:23 | Web | Orders (with the new-order form), Inventory and Catalogue screens, and one shared client for calling the API | `5a00c32` |

## Wednesday 11 November 2026

| Time | App | What was done | Commit |
|---|---|---|---|
| 10:09 | Web | UI design v3 on every screen, dark mode (remembered on the device), and the Purchase orders screens: list and detail with the approval tracker | `40fdde6` |
| 10:21 | Web | Approval tracker line greys into the current step, as in the design | `3328ffb` |
| 10:29 | Web | Each role lands on its own work after sign-in, with a menu for that role (N1, N2) | `19bda38` |
| 10:44 | Web | Notification bell: unread count, grouped notifications, mark as read | `7d28980` |
| 10:52 | Web | People's names shown instead of user IDs on orders, inventory and purchase orders (N6) | `5301cc5` |
| 11:14 | Web | Dashboard screen (revenue, orders by status, low stock, top products, revenue by day and by store) and CSV export buttons | `00db89a` |
| 14:26 | Mobile | Mobile app started for store managers (Expo SDK 58): sign-in, Stock and Catalogue screens, and its own CI workflow | `f43b1d1` |
| 14:33 | Mobile | Orders screen | `f3b64f4` |
| 15:19 | Mobile | Mobile tests made reliable on a cold CI run | `6dd1f74` |
| 16:46 | Web | Supplier invoices on the purchase order page: attach, view, replace, earlier versions kept; files over 20 MB stopped before upload | `6f4e94c` |
| 16:56 | Mobile | Purchases tab with supplier invoice attach, view and replace | `bad0446` |
| 16:58 | Web, Mobile | Admin Settings screen on the web; maintenance banner on both apps (also on the web sign-in page); both apps re-read settings every 60 seconds and when brought back to the front | `dd9f0d9` |

## Thursday 12 November 2026

| Time | App | What was done | Commit |
|---|---|---|---|
| 10:44 | Mobile | Notification bell | `fd41344` |
| 15:27 | Web, Mobile | Both CI workflows show a test-results summary on every run and keep the full reports | `33d2fef` |
| 17:23 | Web | Review queue screen for admins, with a waiting count in the menu: correct or reject flagged imported orders, with a reason | `f92d7b1` |

## Friday 13 November 2026

| Time | App | What was done | Commit |
|---|---|---|---|
| 16:57 | Web, Mobile | Stuck-work and head-office notifications; opening one shows the orders or purchase orders list with "stuck only" on (a filter card on the web, a switch on mobile) | `43c7465` |
| 16:58 | Web | Development server forwards API calls as same-origin, which fixes browser sign-in getting 403 and lets the app be opened through a tunnel or a network address | `9ea21e1` |
