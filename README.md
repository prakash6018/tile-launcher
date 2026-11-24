# Tile Launcher

[![CI](https://github.com/prakash6018/tile-launcher/actions/workflows/ci.yml/badge.svg)](https://github.com/prakash6018/tile-launcher/actions/workflows/ci.yml)

An Android home screen inspired by the Windows Phone 8.1 Start screen. See [docs/DESIGN_PLAN.md](docs/DESIGN_PLAN.md) for the design.

## Features

**Start screen**
- Small, medium and wide tiles in a flow layout. 4 columns, or 6 with "show more tiles".
- Accent color (the 20 WP colors), dark/light/system theme, Selawik font.
- Tilt on press. Turnstile on launch (Start and app list), then the app window grows out of the tapped tile or row.
- Tiles fly in whenever the launcher resumes: Home, back gesture, leaving Recents, unlocking. They stay hidden while away, so the fly-in isn't lost behind the closing app.
- Background picture that shows through the tiles, with optional parallax.

**Edit mode** (long-press a tile)
- The selected tile lifts and the others recede.
- Corner buttons: unpin, resize, and a "…" menu (live tile on/off, rename folder).
- Drag to reorder, with auto-scroll at the screen edges.
- Rest a dragged app on another app or folder for about half a second, then drop, to group them.
- Back or tapping empty space leaves edit mode.

**Folders**
- The folder tile shows miniatures of its contents. Tap it to expand inline.
- Long-press a tile inside an open folder to remove it from the folder or unpin it.
- Tap the folder title to rename it.

**Live tiles**
- Clock: time, date and next alarm.
- Calendar: next three appointments.
- Photos: recent pictures.
- People: a mosaic of contact photos.
- Weather: Open-Meteo (no API key), local units, city name.
- Music: whichever app is playing shows the track, artist and album art.
- Unread counts beside the icon on any app tile.
- Notification previews on any app tile: sender, message and avatar (chat apps read via MessagingStyle), or the notification's picture. Can be turned off in settings.
- Microsoft apps: Outlook adds your next meetings from synced Outlook/Exchange calendars; Teams adds the next meeting with a Teams link. Mail and chat previews come from their notifications.
- Phone tile is labelled with the carrier name, as on WP.
- Tiles flip at random 5–12 s intervals. Flipping pauses when Start isn't visible or during edit mode.

**Shortcuts and extras**
- "Add to home screen" from other apps becomes a tile, after a "pin to start" confirmation. That covers Chrome web apps, chat shortcuts and Maps directions. Apps that ask to pin a widget get a widget tile.
- Long-press an app in the list, or use "…" in edit mode, to see its shortcuts ("New email", "New chat"). Tap one to open it, or tap the pin to make it a tile.
- **Me tile**: greeting, unread count by app, next meeting and weather. Also a "for you" summary from on-device Gemini Nano (ML Kit GenAI) where the phone supports it, then a face for each of the busiest apps. Tap it to flip.
- **Tile glance widget**: clock, weather, next meeting, unread counts and now playing as four tiles. Built for the Galaxy Z Flip cover screen (Flex Window) and also usable on any home screen or as a widget tile.

**Elsewhere**
- App list: swipe left or tap →. Search (Enter opens the top result), letter groups, a–z jump grid. Long-press an app to pin it, open app info or uninstall.
- Long-press empty space on Start to add a weather tile, add an Android widget (shown inside a tile), or open settings.
- Settings pivot with pages for theme, start, tiles, permissions and backup:
  - Icon style: glyph or full color.
  - ADW/Nova-format icon packs.
  - A permissions page explaining each permission.
  - Layout export and import.
- First-run welcome screen: set as home, choose permissions.

## Build and run

Requires JDK 17+ and the Android SDK with platform 37 (Compose BOM 2026.09 needs compileSdk 37).

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd package set-home-activity com.tilelauncher/.MainActivity
```

To switch back to the stock launcher, go to Settings → Apps → Default apps → Home app.

To try the live tiles quickly on an emulator:

```sh
for p in ACCESS_COARSE_LOCATION READ_CALENDAR READ_CONTACTS READ_MEDIA_IMAGES; do adb shell pm grant com.tilelauncher android.permission.$p; done
adb shell cmd notification allow_listener com.tilelauncher/com.tilelauncher.live.NotificationListener
adb emu geo fix -122.084 37.422
```

## Code map

| Area | Files |
|---|---|
| Layout data and edits | `data/Models.kt`, `data/LayoutOps.kt` (unit-tested), `data/LayoutStore.kt` |
| Grid packing | `ui/start/TilePacking.kt` (unit-tested) |
| Start screen, edit mode, drag, folders | `ui/start/StartScreen.kt`, `StartController.kt`, `StartChrome.kt` |
| Turnstile / fly-in | `ui/start/Turnstile.kt` |
| Tile rendering, flips, backdrop | `ui/tile/TileView.kt` |
| Live tile sources | `live/LiveTiles.kt`, `live/WeatherSource.kt`, `live/NotificationListener.kt` |
| Icons and icon packs | `data/IconLoader.kt`, `data/IconPack.kt` |
| Widgets | `widgets/Widgets.kt`, binding flow in `MainActivity.kt` |
| Settings, welcome | `ui/settings/` |

## Notes

- App window close and Recents animations belong to the system (One UI Home / Pixel Launcher own gestures and Recents). Third-party launchers can't animate them; this launcher animates its own content around them.
- Layout is stored as JSON in the app's files directory, covered by Android Auto Backup. Settings use SharedPreferences.
- Widget tiles may use a 4×4 size. App tiles stay at small/medium/wide as on 8.1.
- Selawik is Microsoft's open-source font under the SIL OFL ([SELAWIK_LICENSE.txt](SELAWIK_LICENSE.txt)). No Segoe or other Microsoft assets are bundled.
