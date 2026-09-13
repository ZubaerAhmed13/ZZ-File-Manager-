# Step 6 accessibility

- Home/drawer sections expose headings; icon-only top actions have localized descriptions; decorative adjacent icons are silent.
- Settings and View / Sort expose full-row actions. Browser/Home rows have a 48 dp minimum and quick tiles are 82 dp.
- Selection uses icons and containers, while offline, permission, read-only, warning, progress, and unavailable states include text.
- Adaptive grids reflow; names use bounded layouts and details remain available. Light/dark palettes define explicit foreground/container/outline/error roles.
- Credential inputs are obscured and sensitive forms block screenshots only while open.

API-35 tests require semantic navigation through Home, drawer, browser modes, sorting, selection, and prior destination flows. A dedicated state applies system font scale 1.3, recreates the activity, and verifies the three primary top actions remain reachable. Light/dark Home, drawer, and browser states are captured and checked for multi-region rendering.

Known limitations: current visual probes detect blank/structurally missing rendering but are not checked-in byte-for-byte pixel goldens. Automated contrast ratios, exhaustive target scanning, 2.0 font, switch access, RTL, locale expansion, spoken TalkBack order, keyboard, foldable/tablet, and real-display contrast remain Step 7 work.

