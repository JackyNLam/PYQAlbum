# pyqAlbum - 朋友圈相册 (WeChat Moments Album)

An Android album management app with AI-powered photo rating, built with **Kotlin + Jetpack Compose**.

pyqAlbum loads photos from the device's MediaStore, allows browsing by **folder / tag / rating**, and uses **DashScope (Alibaba Cloud Bailian) AI** to automatically score photos (1-100) via `qwen-vl-plus` multimodal Vision-Language Model.

---

## Overview

- **App name**: pyqAlbum
- **Grid background**: Black for all empty areas in image squares
- **View layouts**: Grid / Waterfall / Justified
- **Justified Grid**: 3 images per row, fixed height (120dp), dynamic width proportional to aspect ratio
- **Click thumbnail** → full-screen detail view
- **Long-press** → action dialog: assign rating / add tag / select for AI ranking
- **AI Selection flow**:
  1. Long-press any image in Album → choose **Select for AI Ranking**
  2. Or tap toolbar ✨ icon to view/manage AI selections
  3. In AI Select screen, only selected images are shown — tap to deselect
  4. Tap **Submit & Rate** to proceed to AI Rating
  5. Selection persists across app launches (SharedPreferences)
- **Multi-select mode**: long-press enters multi-select; bottom bar has batch Tag / Rate / Remove Tags / AI Select
- **Full-screen image**: controls visible by default; tap image to toggle top bar + controls

---

## Project Structure

```
/srv/pyqalbum/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/values/
│       │   ├── strings.xml
│       │   └── themes.xml
│       └── java/com/pyqcr/
│           ├── PyqCrApp.kt              # Application class + Coil ImageLoaderFactory
│           ├── MainActivity.kt           # Single Activity, edge-to-edge
│           ├── ai/
│           │   ├── AiRatingService.kt    # DashScope API batch scoring (10 img/batch)
│           │   └── ImageResizer.kt       # Resize to 800px max, cache in ai_rating/
│           ├── data/
│           │   ├── db/ ...               # Room DB, entities, DAOs
│           │   ├── model/ ...            # Domain models
│           │   └── repository/ ...       # MediaStore scan + Room flows
│           ├── ui/
│           │   ├── component/ ...        # ImageThumbnail, WaterfallGrid, JustifiedGrid, RatingBar, TagChip
│           │   ├── navigation/ ...       # AppNavGraph with 7 routes
│           │   ├── screen/ ...           # All screens
│           │   ├── theme/ ...            # Material3 dynamic color
│           │   └── viewmodel/ ...        # AlbumViewModel
│           └── util/ ...                 # ImageUtil, BatchProcessor, PermissionHelper
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── .github/workflows/android-ci.yml
└── README.md
```

---

## 7 Navigation Routes

| Route | Screen | Purpose |
|-------|--------|---------|
| `album` | `AlbumScreen` | Main album browser: 3 browse modes × 3 layouts |
| `tag` | `TagScreen` | Tag list → images per tag |
| `rating` | `RatingScreen` | Sort/filter by user rating or AI score |
| `image_detail/{uri}` | `ImageDetailScreen` | Full-screen view, tap to toggle controls |
| `ai_rating` | `AiRatingScreen` | API config, select images, run AI scoring |
| `ai_select` | `AiSelectScreen` | Manage AI selection — only selected images shown, tap to deselect |
| `batch_edit/{mode}` | `BatchEditScreen` | Batch tag / rate / resize |

---

## Key UI Interactions

| Action | Result |
|--------|--------|
| **Tap thumbnail** | Opens full-screen detail view (never crashes — handles null safely) |
| **Long-press thumbnail** | Action dialog: Assign Rating / Add Tag / Select for AI Ranking |
| **Tap full-screen image** | Toggle visibility of toolbar and controls |
| **Bottom nav tabs** | Folder / Tag / Rating (3 tabs) |
| **Toolbar icons** | Layout toggle (Grid / Waterfall / Justified) + ✨ AI Select |
| **AI Select screen** | Shows **only AI-selected images**; tap to deselect with ✕ overlay |
| **Submit & Rate (bottom bar)** | Proceeds to AI Rating only when images are selected |
| **Multi-select mode** | Batch actions: Tag / Rate / Remove Tags / Select for AI |

---

## Details on Key Features

### Justified Grid
- **Always 3 images per row**
- Each image width = `(imageRatio / sumRatiosInRow) * availableWidth`
- Fixed `rowHeight = 120.dp`, 2dp gaps between images
- Black background, `ContentScale.Crop` fills each cell
- Last row: if fewer than 3 images, remaining space left empty
- Supports `combinedClickable` (click → detail, long-press → dialog)

### Image Detail Screen
- `.aspectRatio()` pre-calculated via `remember` — never evaluates `imageItem!!` on null
- Controls visible by default (`showControls = true`), tap to toggle
- Shows: metadata, rating bar (0.5-star increments), AI score, tags, add tag, toggle AI selection

### AI Select Screen
- Loads all AI-selected URIs from SharedPreferences on entry
- Filters `allImages` to only show selected ones in a 3-column grid
- Each thumbnail has a green border + semi-transparent overlay with **✕**
- Tap any thumbnail → removes from selection immediately
- Empty state: shows instructions ("long-press in Album to add images")
- Bottom bar: shows count + **Submit & Rate** button
- Top bar: **Clear All** action to reset selection

---

## Setup & Build

### Prerequisites

- **Android Studio** Ladybug (2024.2+) or newer
- **JDK 17** (Temurin recommended)

### Build

```bash
cd /srv/pyqalbum
./gradlew assembleDebug
```

APK will be at `app/build/outputs/apk/debug/app-debug.apk`

### GitHub Actions CI

Push to `main` branch — workflow builds and uploads APK as artifact.

---

## Dependencies Summary

| Dependency | Version | Purpose |
|-----------|---------|---------|
| Kotlin | 2.0.21 | Language |
| AGP | 8.7.0 | Android Gradle Plugin |
| KSP | 2.0.21-1.0.27 | Room annotation processor |
| Compose BOM | 2024.10.01 | Compose toolkit |
| Coil | 3.0.4 | Image loading |
| Room | 2.6.1 | Local database |
| Retrofit | 2.11.0 | HTTP client (unused) |
| OkHttp | 4.12.0 | HTTP client (used by AI) |
| Navigation Compose | 2.8.2 | Screen navigation |
| Security Crypto | 1.1.0-alpha06 | Encrypted API key storage |
| Gson | 2.11.0 | JSON parsing |

---

## ⚠️ Outstanding Items

1. **pHash duplicate detection** — not yet integrated into AiRatingService
2. **App icon** — mipmap resources for `ic_launcher` missing
3. **ProGuard rules** — `proguard-rules.pro` file missing
4. **Write external storage** — batch resize needs proper SAF permissions
5. **Gradle wrapper JAR** — missing locally (GitHub Actions auto-generates)
6. **TagScreen image loading** — LaunchedEffect ordering issue
7. **Tests** — no unit tests or UI tests yet
8. **BottomActionBar.kt** — old component file, no longer used (replaced by inline BatchMultiSelectBar)