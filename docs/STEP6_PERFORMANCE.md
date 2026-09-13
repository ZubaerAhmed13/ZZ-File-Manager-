# Step 6 performance

Home renders its usable storage state before launching independent lightweight media count/byte queries. No composable starts recursive analyzer/provider/network work. Debug StrictMode reports accidental main-thread disk/network calls and leaked closeables.

Providers enumerate on I/O dispatchers and lazy lists use stable IDs. The browser emits its first page and then exponentially spaced partial snapshots, followed by an exact globally sorted result. Tests exercise 100,000 generated entries, bound partial emissions, and sort sizes over 30 GiB using `Long`.

Remote paging, cancellation, retry, resume identity, and operation journaling remain Step 5 behavior. Thumbnails load only for visible items, use dimensioned identity keys, target decode bounds, cancellation, and a bounded memory cache that Settings can clear. Installed-app icons no longer decode during full enumeration. Media roots aggregate only count/byte/path metadata.

The `benchmark` module includes five-iteration cold-start `StartupTimingMetric`/`FrameTimingMetric` measurement plus Baseline Profile generation. The app packages a focused profile for launch, Home, browser, storage, and thumbnails. CI runs these on API 35 and uploads output. Hosted-runner values are not product promises; warm/deep-scroll/media/apps/search, thermal, memory, and jank baselines on representative hardware remain Step 7.

