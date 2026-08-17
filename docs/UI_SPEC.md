# UI Specification — Mr Nobody

The visual source of truth is **`prototype/interactive-wireframe.html`**.
The Flutter app must match its structure, spacing and design language; the
golden tests in `app/test/screens_golden_test.dart` are the gate.

## Design language

- **Monochrome, dark-only.** One accent (white `#ffffff`), near-black surface
  tiers, hairline borders, no gradients, no shadows.

  | Token | Value | Use |
  |---|---|---|
  | `bg` / `s-0` | `#000000` | app + bottom bars |
  | `s-1` | `#101010` | cards, pills, sheets |
  | `s-2` | `#181818` | inset fields, icon tiles, toasts |
  | `s-3` | `#212121` | mock content, filled marks |
  | `line` | white 8% | hairline borders/dividers |
  | `line-strong` | white 16% | emphasised borders |
  | `t-0…t-3` | `#fafafa` `#c4c4c7` `#8a8a8f` `#5c5c61` | text tiers |

- **Type:** Inter for UI, JetBrains Mono for addresses, counters, chips and
  section labels. Both are **bundled** (`app/assets/fonts`) — the app must
  never fetch a font at runtime.
- Radii: 16 cards, 999 pills, 14 tab cards, 10–12 inset fields.
- Cards are inset 16px; section labels are uppercase mono at 10.5px.

## Screen map

Spec IDs (V1 §17) with the wireframe view they correspond to.

| ID | Screen | Wireframe | Flutter |
|----|--------|-----------|---------|
| S1 | First launch | `#v-launch` | `screens/launch_screen.dart` |
| S2 | Agent Home | `#v-newtab` | `screens/home_screen.dart` |
| S2 | Visible browser | `#v-browser` | `screens/browser_screen.dart` |
| S3 | Sessions / Tabs | `#v-tabs` | `screens/tabs_screen.dart` |
| S4 | Privacy dashboard | `#v-privacy` | `screens/privacy_screen.dart` |
| S5 | Tasks | `#v-tasks` | `screens/tasks_screen.dart` |
| S5 | Task detail | `#v-taskdetail` | `screens/task_detail_screen.dart` |
| S6 | Settings | `#v-settings` | `screens/settings_screen.dart` |
| S6 | AI provider | `#v-ai` | `screens/ai_provider_screen.dart` |
| S7 | Clear data | `#v-clear` | `screens/clear_data_screen.dart` |
| S8 | Downloads | `#v-downloads` | `screens/downloads_screen.dart` |

Agent Home is the centre of the product, not the browser.

## Navigation

- Bottom bar (Home · Tabs · **+** · Tasks · Settings) on the four shell
  destinations; the browser has its own bar (Back · Forward · **+** · Tabs ·
  Menu). Both come from `widgets/bottom_nav.dart`.
- The raised **+** must be laid out *inside* the bar's own box (the bar
  reserves the overhang). Anything positioned outside the parent gets clipped
  and stops receiving taps — see `test/bottom_nav_test.dart`.
- The bar hides on scroll-down, returns on scroll-up.
- Drill-in screens (privacy, clear data, downloads, AI provider, task detail)
  are pushed routes with a 34px circular back button.

## Address bar behaviour

- `example.com` → `https://example.com`
- `https://example.com` → as-is
- `best laptops under 500` → search (DuckDuckGo default)
- An instruction typed on **Home** ("find laptops under 500000") goes to the
  agent core, not the browser. The address bar never silently starts a task.

## Data rules

- Screens read from the Java core through `bridge/native_bridge.dart` and show
  `—` or an empty state when it has nothing. **No screen fabricates numbers.**
- Platform failures go to `state/error_log.dart` and surface in the ⓘ overlay
  (count badge + copyable log) — the app has no analytics or crash reporter,
  so that overlay is the user's only reporting channel.

## Keeping parity

```bash
cd app
flutter test                     # fails if a screen drifts from its golden
flutter test --update-goldens    # after an intentional design change
```
