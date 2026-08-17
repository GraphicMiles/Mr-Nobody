# UI Specification — Mr Nobody V1

The visual source of truth is the interactive prototype at `prototype/index.html`.
Native screens must match its structure and design language.

## Design language

- **Dark-first** (light variant available). Background `#0e0e10`, surface
  `#17171a`, gold accent `#c9a237`, muted green "blocked" `#7f9c78`.
- Type: sans for UI, monospace for addresses/values (IBM Plex in the prototype;
  system sans/mono in the native app to keep the APK small).
- **Visually quiet:** no gradients, no heavy shadows, no gratuitous animation.
  Compact, touch-friendly, fast.

## Screen map (8 screens)

| ID | Screen | Entry point | Notes |
|----|--------|-------------|-------|
| S1 | First Launch | app start (first run) | logo, 3 privacy checks, **Start Browsing** → S2, *Privacy Settings* → S5 |
| S2 | Main Browser | hub | lock icon + address bar, WebView body, toolbar `← + □ ⋮` |
| S3 | Tab Switcher | toolbar `□` | list/grid of tabs, `+` new tab, close tabs |
| S4 | Privacy Dashboard | lock icon | "This page": ads/trackers blocked; History OFF; third-party cookies blocked; "all counts stay on-device" |
| S5 | Settings | toolbar `⋮` → Settings | toggles (history OFF, JS ON, suggestions OFF), Privacy & data section |
| S6 | Permissions | Settings → Permissions (or site prompt) | per-site "wants to access your camera" Block/Allow |
| S7 | Clear Data | Settings → Clear browsing data | History/Cookies/Cache/Site data checkboxes + Cancel/Clear |
| S8 | Downloads | Settings/menu → Downloads | progress rows; "opens with Android file handling" |

## Address bar behavior

- `example.com` → `https://example.com`
- `https://example.com` → as-is
- `best laptops under 500` → search (DuckDuckGo default)

Search is sent directly to the provider; never proxied through us.

## Native implementation notes

- Settings are toggles/rows (built programmatically) matching the prototype.
- The privacy dashboard reads counters straight from `FilterEngine`
  (per-page, reset on navigation).
- The tab switcher is a simple list dialog in V1 (a grid is a V1.x refinement);
  labels mirror the prototype's tab cards, private tabs marked.
