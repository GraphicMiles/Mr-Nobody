# Navigation Tests

Device-level tests (instrumented) covering:

- URL input → HTTPS upgrade (`example.com` → `https://example.com`).
- Search input → provider query (DuckDuckGo default).
- Back/forward/reload via toolbar.
- Tab create / switch / close / close-all.
- Private tab sets `FLAG_SECURE` and never records history.
- External schemes (mailto, tel) open system apps and do not load in the tab.

The pure URL logic is `MainActivity.toUrl(String)`; port it to a testable helper
class for JVM coverage if desired.
