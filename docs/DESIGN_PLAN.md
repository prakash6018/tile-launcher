# Tile Launcher: Design Plan

An Android home-screen launcher modeled on the Windows Phone 8.1 Start experience:
live tiles on a vertically scrolling grid, a swipe-left alphabetical app list, accent
colors, and Metro-style typography and motion.

Working name: **Tile Launcher** (placeholder; see [Legal](#10-legal--branding) for why we avoid "Windows").

---

## 1. Reference: what made the WP 8.1 Start screen distinctive

| Element | WP 8.1 behaviour | Keep / adapt |
|---|---|---|
| **Start screen** | Vertical scroll of square and rectangular tiles. No pages and no dock. Status bar sits at the top. | Keep exactly |
| **Tile sizes** | Small (1×1), Medium (2×2), Wide (4×2) in units of a small cell. Asset sizes were 71, 150 and 310×150 px. Large (4×4) was desktop-only. | Keep S/M/W. Large is optional later. |
| **Grid density** | Default: 2 medium tiles per row (4 small columns). "Show more tiles": 3 medium per row (6 small columns). | Setting: 4 or 6 columns |
| **Live tiles** | Tiles flip or cycle content (photos, counts, next appointment, now playing). They update independently with staggered, randomised timing. | Core feature |
| **Badge counts** | A number on an app tile showing unread items. | Driven by the notification listener |
| **Accent color** | One system accent (20 choices in WP8/8.1) fills all tiles. The light/dark theme changes the background and text. | Keep. Add custom colors and per-tile overrides. |
| **Start background (8.1)** | A background image shows *through* tiles, so tiles act as windows onto one image. It parallax-scrolls as you scroll. | Keep. It is one of the signature looks. |
| **App list** | Swipe left from Start. Search box at top. Alphabetical list grouped under accent-colored letter squares. Tap a letter → jump grid of A–Z (letters with no apps greyed). | Keep exactly |
| **Edit mode** | Long-press a tile. The selected tile lifts and the others recede and dim. Unpin button top-right, resize arrow bottom-right. Drag to reorder and the others reflow. | Keep |
| **Folders (8.1 Update 1)** | Drag a tile onto another to create a folder. Folder tile shows a mini-grid of its children and expands inline, pushing the tiles below it down. | Phase 3 |
| **Motion** | *Turnstile*: tiles swing away around the left edge, staggered, with the tapped tile last. *Tilt*: a pressed tile tilts toward your finger in 3D. Tiles fly in when you return to Start. | Keep. Motion is half the identity. |
| **Typography** | Segoe WP: large light-weight headers, lowercase pivot titles, small labels bottom-left on tiles. | Use Selawik (open Segoe-metric font, OFL) or Open Sans |

### Accent palette (WP8/8.1)

These hex values are the widely circulated set. Lime appears as both `#A4C400` and `#A4CC00`, so check it against screenshots.

```
lime    #A4C400   green   #60A917   emerald #008A00   teal    #00ABA9
cyan    #1BA1E2   cobalt  #0050EF   indigo  #6A00FF   violet  #AA00FF
pink    #F472D0   magenta #D80073   crimson #A20025   red     #E51400
orange  #FA6800   amber   #F0A30A   yellow  #E3C800   brown   #825A2C
olive   #6D8764   steel   #647687   mauve   #76608A   taupe   #87794E
```

Themes: **Dark** = `#000000` background with white text. **Light** = `#FFFFFF` background with black text. Tiles stay accent-colored in both.

---

## 2. Product scope

### MVP (must feel right before anything else)
1. Registers as the Android home app (HOME intent).
2. Start screen with S/M/W tiles, 4-column grid, accent color, dark/light theme.
3. Pin/unpin, resize and drag-reorder with reflow.
4. App list with search, letter headers and a jump grid.
5. Tile icons rendered as white glyphs on the accent color (see §5).
6. Turnstile launch animation, tilt-on-press and fly-in on return.
7. Badge counts from notifications.
8. A few built-in live tiles: Clock/Date, Calendar (next event), Photos (cycling), Music (now playing).

### Later
- 6-column "more tiles" mode
- Start background image showing through tiles, with parallax
- Folders
- Weather tile (Open-Meteo, no API key needed)
- People tile (contact photo mosaic)
- Android widgets inside tiles (AppWidgetHost)
- Icon pack support
- Backup/restore of the layout
- A "Me"/lock-screen-style screen

### Out of scope
- Replacing the system notification shade (Action Center). Android doesn't allow this for launchers.
- The lock screen
- Recreating Microsoft's first-party apps

---

## 3. Tech stack

| Concern | Choice | Why |
|---|---|---|
| Language | Kotlin | Standard |
| UI | Jetpack Compose | Custom layout and animations are much easier than with Views. Good `graphicsLayer` 3D support for flip, tilt and turnstile. |
| Min / target / compile SDK | **26 / 36 / 37** | API 26 gives adaptive icons. Themed monochrome icons light up on 33+. |
| Persistence | JSON file (tile layout) for now. Room if folders make it relational. DataStore for settings. | A flat list of under 100 tiles doesn't need a database yet. |
| DI | Hilt (or manual DI; the app is small) | |
| Background updates | Coroutines + Flow for in-app tickers. WorkManager for periodic fetches (weather). | |
| App enumeration | `LauncherApps` + `UserManager` | Handles work profiles and listens for package add/remove/update events |
| Badges / now playing | `NotificationListenerService` + `MediaSessionManager` | The only reliable cross-app source of unread counts and media state |
| Images | Coil | Photo tile and background image |

---

## 4. Architecture

```
app/
├─ MainActivity              HOME activity; hosts the Compose nav (Start ⇄ AppList pager)
├─ ui/
│  ├─ start/                 StartScreen, TileGrid layout, edit mode, drag & drop
│  ├─ applist/               AppListScreen, JumpGrid, search
│  ├─ tile/                  Tile composable, flip/cycle renderers, badge
│  ├─ motion/                Turnstile, Tilt, FlyIn, Continuum helpers
│  ├─ theme/                 Accent palette, typography (Selawik), Metro tokens
│  └─ settings/              Accent, theme, columns, background, icon style
├─ data/
│  ├─ apps/                  AppRepository (LauncherApps wrapper, icon loading/cache)
│  ├─ layout/                Room: TileEntity, FolderEntity; LayoutRepository
│  └─ settings/              DataStore
├─ livetiles/
│  ├─ LiveTileProvider.kt    interface: fun frames(): Flow<TileFrame>
│  ├─ ClockProvider, CalendarProvider, PhotosProvider,
│  │  MusicProvider, WeatherProvider, PeopleProvider
│  └─ LiveTileScheduler      staggers flips (randomised 5–12s), pauses when off-screen
└─ services/
   └─ BadgeListenerService   NotificationListenerService → StateFlow<Map<pkg, count>>
```

### Data model

```kotlin
enum class TileSize(val w: Int, val h: Int) { SMALL(1,1), MEDIUM(2,2), WIDE(4,2) }

@Entity data class TileEntity(
    @PrimaryKey val id: Long,
    val order: Int,                 // position in flow order; grid placement is derived
    val size: TileSize,
    val kind: TileKind,             // APP, FOLDER, LIVE_BUILTIN, WIDGET
    val component: String?,         // "pkg/.Activity" for APP
    val userSerial: Long?,          // work-profile support
    val liveProvider: String?,      // "clock", "photos", ...
    val folderId: Long?,            // non-null if inside a folder
    val colorOverride: Int?,        // null = use accent
    val liveEnabled: Boolean = true,
)
```

### Grid layout engine

WP Start is a **flow layout with first-fit packing**. Tiles are stored as an ordered
list. Placement is computed, not stored, which makes reflow during drag trivial.

```
cols = 4 (or 6)
occupancy = growable boolean[rows][cols]
for tile in tiles.sortedBy(order):
    find first (row, col) scanning top→bottom, left→right
      where tile.w × tile.h fits and is unoccupied
    place; mark occupied
```

- Cell size: `cell = (screenWidth − 2·margin − (cols−1)·gap) / cols`. Starting values are `margin = 12dp` and `gap = 6dp`; tune them against screenshots.
- Implement it as a custom Compose `Layout` inside a `verticalScroll`, or as `LazyLayout` if pinned counts get large (they rarely exceed ~60, so plain `Layout` is fine to start).
- Wide tiles in an odd column position fall to the next row, just like on WP.
- **Drag reorder:** while dragging, compute the target `order` index from the finger position and re-run packing on the list with the dragged tile moved. Animate every tile to its new slot with `animateIntOffsetAsState`.

---

## 5. Tile icon strategy (the hardest visual problem)

Android icons are full-color. WP tiles are a **white glyph on an accent color**. We try
these sources in order, per app:

1. **Themed monochrome layer** (API 33+): `AdaptiveIconDrawable.monochrome`, tinted white. Many major apps ship one, and it gives the most authentic look.
2. **Icon pack**: support the standard `appfilter.xml` format, since many WP/Metro-style icon packs already exist.
3. **Auto-silhouette**: render the adaptive foreground layer, then turn its alpha into a white mask. Works well for simple logos and poorly for photographic ones.
4. **Full-color fallback**: original icon centered on the accent. The user can also pick a "brand color" tile, where the background is the dominant icon color (via the Palette API), as Facebook/Skype-style tiles did on WP.

The icon style is a per-tile setting with a global default. Cache the rendered bitmaps on disk, keyed by package + version + style.

**Tile anatomy:**
- Glyph centered at about 40% of the tile's short side (Medium/Wide).
- Label bottom-left, 12sp, with a 6dp inset. Small tiles have no label.
- Badge count bottom-right (Medium) or next to the glyph (Wide), in light-weight numerals.

---

## 6. Live tiles

```kotlin
interface LiveTileProvider {
    val id: String
    val supportedSizes: Set<TileSize>
    fun frames(size: TileSize): Flow<TileFrame>   // emits content to cycle through
}

sealed interface TileFrame {
    data class Iconic(val count: Int?, val lines: List<String>) : TileFrame  // big number + text
    data class Image(val uri: Uri, val caption: String?) : TileFrame        // photo/peek
    data class Text(val title: String, val body: List<String>) : TileFrame // calendar, now-playing
}
```

**Animations** (all use `graphicsLayer` with `cameraDistance` set high):
- **Flip:** `rotationX` 0→90° to swap content, then −90→0°. About 250ms each half with an ease-in-out curve.
- **Cycle/peek:** content slides up inside the tile, clipped.

**Scheduler:** each visible tile picks a random interval from 5 to 12 seconds, so tiles never flip in sync. Updates pause when Start isn't visible (`Lifecycle.repeatOnLifecycle(RESUMED)`) or the screen is off.

**Built-in providers:**

| Provider | Data source | Permission |
|---|---|---|
| Clock/Date | System clock | none |
| Calendar | `CalendarContract` next events | `READ_CALENDAR` |
| Photos | `MediaStore` recent images | `READ_MEDIA_IMAGES` (33+) |
| Music | `MediaSessionManager.getActiveSessions` | Notification listener access |
| Weather | Open-Meteo HTTP + coarse location | `ACCESS_COARSE_LOCATION`, `INTERNET` |
| People | `ContactsContract` starred contacts' photos | `READ_CONTACTS` |
| Badges (any app) | Active notifications grouped by package | Notification listener access |

> Avoid `READ_SMS` and `READ_CALL_LOG` for Messaging/Phone counts. Play Store policy blocks
> launchers from using them. Notification counts cover the same need.

---

## 7. Screens and interactions

### Start
- Pressing a tile tilts it toward the touch point (`rotationX/Y` up to about 8°, scale 0.97) and springs back on release.
- Tapping a tile plays the turnstile, then launches the app. On return, tiles fly in.
- Long-pressing a tile enters edit mode:
  - The selected tile stays at scale 1.0. The others scale to 0.9 with alpha 0.6.
  - Pin/unpin button at the top-right corner. Resize arrow at the bottom-right cycles S → M → W.
  - Drag to reorder. Drop onto another tile (hover about 600ms) to create a folder (Phase 3).
  - Tap empty space or press Back to exit edit mode.
- A small "→ All apps" arrow at the bottom of the tile list, as on WP. Swiping left also opens the app list.
- Overscroll at the top or bottom gives a gentle compression effect rather than Android's stretch.

### App list
- `HorizontalPager` with Start as page 0 and the app list as page 1.
- Search field at the top (WP had a magnifier icon there; 8.1 added the box).
- `LazyColumn` with sticky letter headers in accent squares. Rows show the icon (small square in the accent color) and the app name in light 20sp text.
- Tapping a letter header opens a full-screen jump grid: a 4-column A–Z grid plus `#`, with letters that have no apps greyed. Tap a letter to scroll to it.
- Long-pressing an app opens a menu: *pin to start*, *uninstall*, *app info*.
- Newly installed apps appear under a "new" label at the top, as on WP 8.1.

### Settings (a pivot-style screen with lowercase headers)
- *theme*: dark/light/system, accent (20 swatches + custom), tile transparency
- *start*: columns 4/6, background image, parallax on/off, live tiles on/off
- *tiles*: default icon style, icon pack, label visibility
- *permissions*: notification access, calendar, photos, location, each explained

---

## 8. Motion spec

| Animation | Spec |
|---|---|
| **Turnstile out** | Pivot at the left screen edge. Each tile animates `rotationY` 0→−80° and alpha 1→0. Stagger by row from top to bottom, about 30ms per row. Right-column tiles start slightly later. The tapped tile goes last. Total under 350ms. Launch the activity at about 70% progress with `overridePendingTransition(0,0)` so it feels continuous. |
| **Fly-in (return)** | Reverse of turnstile: `rotationY` 80→0° with the same stagger, triggered in `onResume`. |
| **Tilt** | Press point → normalized offset → rotation up to ±8°. Spring back with damping 0.6. |
| **Live flip** | `rotationX` 0→90 (content swap) −90→0. 2 × 250ms. |
| **Edit mode** | Scale/alpha transitions at 200ms with a decelerate curve. |
| **Page swipe Start ⇄ list** | Pager follows the finger. Headers and letters move with a slight parallax offset. |
| **Reduced motion** | When the system animation scale is 0 or reduce-motion is enabled, replace turnstile/flip with fades. |

Metro easing: an exponential ease-out (`CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)`) for most transitions.

---

## 9. Performance and system integration

- Manifest: `<category android:name="android.intent.category.HOME"/>` and `DEFAULT` on the main activity. Use `launchMode="singleTask"`, `stateNotNeeded="true"`, and `excludeFromRecents`.
- Pressing Home while already on Start scrolls to the top, then closes the app list.
- Load icons off the main thread with an LRU memory cache and a disk cache.
- Pre-render live-tile frames. Keep it to one `graphicsLayer` per tile and avoid recomposing the whole grid on each flip (scope state per tile).
- Use Baseline Profiles and a Macrobenchmark for cold start and scroll jank. Target 120Hz-smooth scrolling with 40+ tiles.
- Use an edge-to-edge window with a transparent status bar. Icon color follows the theme.
- Handle work profiles by badging work apps and keying them by `UserHandle`.

---

## 10. Legal / branding

- Do **not** ship Segoe fonts, Windows logos, Microsoft app icons or the name "Windows". These are trademarked or licensed assets. Selawik (Microsoft's open-source OFL font) is fine to bundle.
- The general design language (tiles, accent colors, flat typography) isn't protectable, but keep the name and icon original.
- If you publish on Play, the notification-listener and calendar permissions need clear in-app justification screens.

---

## 11. Roadmap

| Phase | Deliverable | Est. |
|---|---|---|
| **0. Setup** | Gradle project, Compose, HOME intent, theme tokens, Selawik, accent palette | 1–2 days |
| **1. Static Start** | App repository, Room layout, flow-packing grid, tiles with mono icons, launch apps | 1 week |
| **2. App list** | Pager, sticky letters, jump grid, search, pin from list | 3–4 days |
| **3. Edit mode** | Long press, unpin, resize, drag-reorder with reflow animation | 1 week |
| **4. Motion pass** | Turnstile, fly-in, tilt, page parallax | 3–4 days |
| **5. Live tiles** | Provider framework, scheduler, Clock/Calendar/Photos/Music, badge service | 1–1.5 weeks |
| **6. Polish** | Settings pivot, light theme, 6-column mode, background-through-tiles, onboarding/permissions | 1 week |
| **7. Extras** | Folders, weather, people, icon packs, widgets-in-tiles, backup | ongoing |

**Status (2026-10-28):** phases 0–7 are implemented, except the "Me"/lock-screen-style screen and the Start ⇄ app list header parallax. Departures from this plan:
- The layout is stored as JSON rather than in Room.
- Weather refreshes in-app every 30 minutes while visible, not through WorkManager.
- Widget tiles may use a 4×4 size.

**Background-through-tiles technique (Phase 6):** draw the wallpaper once as a full-screen
layer behind the grid with a parallax offset of about 0.3 × scroll. Then draw the grid in
an offscreen layer (`CompositingStrategy.Offscreen`), where each tile paints its rect with
`BlendMode.DstIn`, so the image is visible only inside tile shapes. Paint tile content
(glyph, label) on top.

---

## 12. Open decisions

1. **Default icon style:** monochrome-first (authentic, but some apps look plain) or full-color-on-accent (always recognizable)?
2. **Distribution:** sideload only, or Google Play? Play limits permissions and needs policy screens.
3. **Large 4×4 tile:** add it (Windows 10 Mobile style) or stay strict to 8.1?
4. **Min SDK:** 26 covers about 97% of devices. 29 would simplify some APIs.
