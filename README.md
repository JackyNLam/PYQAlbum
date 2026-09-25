# pyqAlbum - 朋友圈相册 (WeChat Moments Album)

An Android album management app with AI-powered photo rating, built with **Kotlin + Jetpack Compose**.

pyqAlbum loads photos from the device's MediaStore, allows browsing by **folder / tag / rating**, and uses **DashScope (Alibaba Cloud Bailian) AI** to automatically score photos (1-100) via `qwen-vl-plus` multimodal Vision-Language Model.

---

## Overview

- **App name**: pyqAlbum
- **Grid background**: Black for all empty areas in image squares
- **View layouts**: Grid / Waterfall / Justified
  - **Grid**: 3 columns, square thumbnails (1:1 aspect ratio)
  - **Waterfall (River)**: 3 columns, Pinterest-style staggered heights
  - **Justified Grid**: 3 images per row, fixed height (120dp), dynamic width proportional to aspect ratio
- **Navigation**: Left-side drawer menu (☰) — replaces old bottom tabs
- **Click thumbnail** → full-screen detail view (safe null handling, no crash)
- **Long-press** → action dialog: assign rating / add tag / select for AI ranking
- **AI Rating flow (streamlined)**:
  1. Long-press any image in Album → choose **Select for AI Ranking**
  2. Or tap toolbar ✨ icon to go directly to AI Rating view
  3. The AI Rating screen shows all selected images in a **square grid** (no separate list view)
  4. **Save Config** button persists API Key + Model name
  5. **Submit to AI Rating** button runs the scoring
  6. Selection persists across app launches (SharedPreferences)
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
| `album` | `AlbumScreen` | Main album browser: 3 browse modes × 3 layouts, left drawer menu |
| `tag` | `TagScreen` | Tag list → images per tag *(can be discovered via drawer, or navigated separately)* |
| `rating` | `RatingScreen` | Sort/filter by user rating or AI score *(can be discovered via drawer, or navigated separately)* |
| `image_detail/{uri}` | `ImageDetailScreen` | Full-screen view, tap to toggle controls |
| `ai_rating` | `AiRatingScreen` | **Direct entry point for AI features**: config + selected image grid + submit |
| `ai_select` | `AiSelectScreen` | **Bridge screen** — automatically forwards to AiRatingScreen |
| `batch_edit/{mode}` | `BatchEditScreen` | Batch tag / rate / resize |

---

## Key UI Interactions

| Action | Result |
|--------|--------|
| **☰ (top-left hamburger)** | Opens the left drawer: Folder / Tag / Rating / AI Rating |
| **Tap thumbnail** | Opens full-screen detail view (safe null handling — never crashes) |
| **Long-press thumbnail** | Action dialog: Assign Rating / Add Tag / Select for AI Ranking |
| **Tap full-screen image** | Toggle visibility of toolbar and controls |
| **Left drawer** | Switch between Folder, Tag, Rating browse modes, or go to AI Rating |
| **Toolbar icons** (Folder mode) | Layout toggle: Grid (▦) / Waterfall (🌊) / Justified (▭) |
| **AI Rating** | Via drawer or toolbar ✨ icon |
| **Multi-select mode** | Batch actions: Tag / Rate / Remove Tags / Select for AI |

### Browse Modes (via Left Drawer)

#### Folder mode (default)
- Shows images organized by device folder
- Horizontal folder chip row at top — tap to filter
- View layout toggles in toolbar: Grid / Waterfall / Justified

#### Tag mode
- Grid of tag cards — tap a tag to see all images with that tag
- Tap **All Tags** back button to return to tag list
- Tags are assigned via long-press → **Add Tag** action
- Tagged images appear immediately in this view

#### Rating mode
- List view showing images sorted by **User Rating** (descending) or **AI Score** (descending)
- Sort toggle in toolbar (↕ icon)
- Each card shows: thumbnail, name, folder, star rating bar, AI score
- Long-press a card to edit rating/tags
- After assigning a rating via long-press dialog, images appear here

---

## Details on Key Features

### Grid View
- **3 columns** of square thumbnails (1:1 aspect ratio)
- Black background, `ContentScale.Fit` keeps original proportions
- `combinedClickable` with click → detail and long-press → action dialog
- Multi-select: long-press enters mode, selected images get overlay with ✓ badge

### Waterfall (River) Grid
- **3 columns**, Pinterest-style staggered heights
- Each column has images of varying heights based on their aspect ratio
- `ContentScale.Fit` preserves original image proportions
- Supports multi-select and click/long-press interactions

### Justified Grid
- **Always 3 images per row**
- Each image width = `(imageRatio / sumRatiosInRow) * availableWidth`
- Fixed `rowHeight = 120.dp`, 2dp gaps between images
- Black background, `ContentScale.Crop` fills each cell
- Last row: if fewer than 3 images, remaining space left empty
- Supports `combinedClickable` (click → detail, long-press → dialog)

### Image Detail Screen
- `.aspectRatio()` pre-calculated via `remember` with safe null handling — uses `item.width` / `item.height` with null check, never `!!`
- Controls visible by default (`showControls = true`), tap to toggle
- Shows: metadata, rating bar (0.5-star increments), AI score, tags, add tag, toggle AI selection

### AI Rating Screen (Redesigned)
- **Single unified view** — no separate selection step
- **API Configuration** card with:
  - DashScope API Key input (encrypted storage via EncryptedSharedPreferences)
  - Model Name input (e.g. `qwen-vl-plus`)
  - **Save Config** button — persists both API Key and Model name
- **Selected images** shown in a **3-column square grid** (not a list view):
  - Each thumbnail has a green border + ✕ overlay
  - Tap any thumbnail to deselect it
  - "Select All" / "Deselect All" toggle
- **Submit to AI Rating** button — runs the full pipeline: resize → API call → save scores
- Progress bar + status text during execution
- Results section: sorted by score descending, shows score + AI reasoning
- "Clear All" action in top bar to reset selection

#### Key Fix: No More AiSelectScreen Intermediate Step
- Tapping the ✨ toolbar icon now **directly** navigates to `AiRatingScreen`
- `AiSelectScreen` acts as a transparent bridge that forwards immediately
- All selection management happens inline on the AI Rating screen

### Tag/Rating Assignment
- **Long-press dialog** assigns rating or tag directly to images
- Rating updates persist correctly to Room DB via `AlbumViewModel.updateRating()`
- Tag creation checks for existing tags before inserting (prevents duplicates)
- Image-tag cross-reference stored in `image_tag_cross_ref` table
- Works reliably from both long-press dialog and full-screen detail view
- **Tag/Rating views are now embedded directly in AlbumScreen** via left drawer — after assigning a tag or rating, switch to the respective mode to see the images

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