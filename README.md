# pyqAlbum - 朋友圈相册 (WeChat Moments Album)

An Android album management app with AI-powered photo rating, built with **Kotlin + Jetpack Compose**.

pyqAlbum loads photos from the device's MediaStore, allows browsing by **folder** or **tag**, and uses **DashScope (Alibaba Cloud Bailian) AI** to automatically score photos (1-100) via `qwen-vl-plus` multimodal Vision-Language Model.

---

## Overview

- **App name**: pyqAlbum
- **Grid background**: White for all empty areas in image squares
- **View layouts**: Grid / Waterfall / Justified (single dropdown button in toolbar)
  - **Grid**: 3 columns, square thumbnails (1:1 aspect ratio)
  - **Waterfall (River)**: 3 columns, Pinterest-style staggered heights
  - **Justified Grid**: 3 images per row, fixed height (120dp), dynamic width proportional to aspect ratio
- **Navigation**: Left-side drawer menu (☰) — Folder, Tag, AI Rating
- **Click thumbnail** → full-screen detail view (safe null handling, no crash)
- **Long-press** → action dialog: assign rating / add tag / select for AI ranking
- **Sort inside a folder**: Use the Sort (↕) button in the toolbar to sort images by User Rating (descending) or AI Score (descending)
- **AI Rating flow (streamlined)**:
  1. Long-press any image in Album → choose **Select for AI Ranking**
  2. Or tap drawer ✨ AI Rating to go directly to AI Rating view
  3. The AI Rating screen shows all selected images in a **square grid** (no separate list view)
  4. **Save Config** button persists API Key + Model name
  5. **Submit to AI Rating** button runs the scoring with real-time progress feedback and a full debug log
  6. Selection persists across app launches (SharedPreferences)
- **Multi-select mode**: long-press enters multi-select; bottom bar has batch Tag / Rate / Remove Tags / AI Select
- **Full-screen image**: Tap image to toggle controls; **swipe down to go back** to thumbnail grid (folder or tag context preserved); **swipe up to reveal** rating, tags, and AI selection panel; **swipe left/right** to navigate to next/previous image in the same folder/tag
- **AI-selected images** are marked with an ✨ AutoAwesome icon badge in all grid layouts (Grid, Waterfall, Justified) and TAG grid view
- **User ratings** are shown as gold-on-black score badges on thumbnails
- **Tag assignment** shows ALL existing tags (from any image) as selectable options before allowing custom tag creation — both from long-press dialog and batch tag mode (always collected regardless of browse mode)
- **Ratings persist** across app restarts — MediaStore refresh preserves existing ratings and AI scores

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
| `album` | `AlbumScreen` | Main album browser: folder grid → image grid with 3 view layouts, left drawer menu |
| `tag` | `TagScreen` | Tag list → images per tag *(can be discovered via drawer, or navigated separately)* |
| `rating` | `RatingScreen` | Sort/filter by user rating or AI score *(still accessible via nav route)* |
| `image_detail/{uri}` | `ImageDetailScreen` | Full-screen view, swipe down to go back, swipe up for controls |
| `ai_rating` | `AiRatingScreen` | **Direct entry point for AI features**: config + selected image grid + submit |
| `ai_select` | `AiSelectScreen` | **Bridge screen** — automatically forwards to AiRatingScreen |
| `batch_edit/{mode}` | `BatchEditScreen` | Batch tag / rate / resize |

---

## Key UI Interactions

| Action | Result |
|--------|--------|
| **☰ (top-left hamburger)** | Opens the left drawer: Folder / Tag / AI Rating |
| **Tap folder card** (Folder mode) | Enter folder to see images in selected layout |
| **Tap thumbnail** | Opens full-screen detail view |
| **Long-press thumbnail** | Action dialog: Assign Rating / Add Tag / Select for AI Ranking |
| **Swipe down on image** (detail view) | Go back to thumbnail grid |
| **Swipe left** (detail view) | Next image in same folder/tag |
| **Swipe right** (detail view) | Previous image in same folder/tag |
| **Swipe up on image** (detail view) | Reveal rating bar, tags, AI selection panel |
| **Toolbar Layout button** (inside folder) | Dropdown: Grid (▦) / Waterfall (🌊) / Justified (▭) |
| **Toolbar Sort button** (inside folder) | Dropdown: Default / User Rating ↓ / AI Score ↓ |
| **Left drawer** | Switch between Folder, Tag browse modes, or go to AI Rating |
| **AI Rating** | Via drawer ✨ |
| **Multi-select mode** | Batch actions: Tag / Rate / Remove Tags / Select for AI |
| **Thumbnail rating badge** | Gold number (e.g. `3.5` or `5.0`) at top-left corner of thumbnails with ratings |
| **AI Selection badge** | ✨ AutoAwesome icon at bottom-right of AI-selected thumbnails |

### Browse Modes (via Left Drawer)

#### Folder mode (default — no drawer tab selected)
- **Step 1**: Shows a 2-column folder grid with folder icons
- **Step 2**: Tap a folder → images displayed in the selected layout (Grid/Waterfall/Justified)
- Layout toggle and sort button appear in toolbar when inside a folder
- Sort options: **Default** (folder order), **User Rating ↓**, **AI Score ↓**

#### Tag mode
- Grid of tag cards — tap a tag to see all images with that tag
- Tap **All Tags** back button to return to tag list
- Tags are assigned via long-press → **Add Tag** action
- Existing tags are shown as selectable options when adding a tag

---

## Details on Key Features

### Folder View
- **Step 1**: 2-column grid of `ElevatedCard`s with folder icons
- **Step 2**: Tap a folder → "← All Folders" back button at top, then image grid
- Sort images inside a folder by rating or AI score via the Sort button

### Grid View
- **3 columns** of square thumbnails (1:1 aspect ratio)
- White background, `ContentScale.Fit` keeps original proportions
- `combinedClickable` with click → detail and long-press → action dialog
- Multi-select: long-press enters mode, selected images get overlay with ✓ badge
- Rating badge: gold number overlay at top-left (only shown for rated images > 0)
- AI selection badge: ✨ AutoAwesome icon at bottom-right

### Waterfall (River) Grid
- **3 columns**, Pinterest-style staggered heights
- Each column has images of varying heights based on their aspect ratio
- `ContentScale.Fit` preserves original image proportions
- White background, rating + AI selection badges

### Justified Grid
- **Always 3 images per row**
- Each image width = `(imageRatio / sumRatiosInRow) * availableWidth`
- Fixed `rowHeight = 120.dp`, 2dp gaps between images
- White background, `ContentScale.Crop` fills each cell
- Rating + AI selection badges

### Image Detail Screen
- **Full-screen image** — no `.aspectRatio()` constraint; uses `fillMaxSize()` + `ContentScale.Fit` inside a `Box` that fills available screen space
- **Swipe down** anywhere on the image → navigates back to thumbnail grid (folder/tag context preserved)
- **Swipe up** → reveals detail panel with rating bar, tags, AI selection, metadata
- **Swipe left** → next image in same folder/tag
- **Swipe right** → previous image in same folder/tag
- Tap on image (when panel is hidden) to toggle top bar
- Swipe down on the detail panel itself to hide it again

### AI Rating Screen
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
- **Real-time progress tracking** — shows:
  - Resizing progress per image (`Resizing (3/10): IMG_123.jpg`)
  - AI batch progress (`AI Rating in progress — batch 2/3 (17/25 images processed)`)
  - Saving status (`Saving 10 scores to database...`)
  - Final completion message with count
  - The button text displays the current status during operation
- **Detailed Debug Log** panel — dark terminal-style card that records every step:
  - Timestamped entries (`[14:23:45] Resizing [3/10]: IMG_123.jpg`)
  - Per-image resize success/failure with file size
  - API endpoint called and batch progress (fixed: shows correct pass count instead of overflowing)
  - Each AI result (name, score, reason)
  - DB save status per image
  - All errors/warnings clearly marked
  - "Clear Debug Log" button to reset
- **Progress bar** with percentage indicator
- Results section: sorted by score descending, shows score + AI reasoning
- "Clear All" action in top bar to reset selection
- Error messages shown via Toast for empty API key, missing images, or API failures

### Tag/Rating Assignment
- **Long-press dialog** shows existing tags as selectable buttons before offering a "create new" option
- Rating updates persist correctly to Room DB via `AlbumViewModel.updateRating()`
- Tag creation checks for existing tags before inserting (prevents duplicates)
- Image-tag cross-reference stored in `image_tag_cross_ref` table
- Works reliably from both long-press dialog and full-screen detail view
- **Tag view** is embedded directly in AlbumScreen via left drawer

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