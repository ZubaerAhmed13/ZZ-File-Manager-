# Step 6 UI specification

The user-supplied screenshots were used as clean-room interaction/layout references only, and no proprietary assets/code were copied.

ZZ File Manager uses its own teal/charcoal light and dark palettes, compact type and spacing, Material 3 components, and platform iconography. Color is not the only carrier for selection, offline, permission, warning, or error state.

Home places Main Storage, Downloads, Storage Analysis, Images, Audio, Videos, Documents, Apps, New Files, Cloud, Remote, Access From, Recycle Bin, Favorites, and History in an 82 dp three-column grid. Values come from providers; unknown values say “Not available.” The drawer groups Storage, Categories, Accounts / Remote, and Tools, with usage/offline state.

The browser has a compact top bar, horizontal breadcrumbs, stable-key lazy content, operation badge, selection bar, and contextual actions. Folder rows show child count/known aggregate size. Five persisted modes are supported: List, Compact list, Grid, Thumbnail grid, and Detailed list. View / Sort includes thumbnail behavior, Name/Date/Size/Type/Extension, direction, hidden files, and folders-first controls.

Images, Videos, Audio, and Documents first show path groups with real counts/sizes and then reuse the browser. Apps has Downloaded/All tabs, four sort modes, version/package/aggregate APK size, and visible-row icon loading. Analyzer exposes real used/free/capacity values and existing cancellable detail sections. Recycle Bin, Favorites, Recents, History, Search, Remote, Cloud, SD, and USB share state language and surfaces.

The image viewer uses a black canvas, sampled bitmap, zoom/pan/double-tap, display-only rotation, filename and position. Media3 resources remain lifecycle owned. Text, archive, APK, audio, and video flows retain Step 4 safety and now follow the persisted theme and edge-to-edge activity layout.

Adaptive grids reflow by width. Primary rows meet a 48 dp minimum; section labels are headings and icon-only actions are described. API-35 states exercise both themes and 1.3 font scale. Physical TalkBack/foldable/tablet validation remains Step 7.

