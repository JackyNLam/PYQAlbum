# pyqAlbum - 朋友圈相册 (WeChat Moments Album)

An Android album management app with AI-powered photo rating, built with **Kotlin + Jetpack Compose**.

pyqAlbum loads photos from the device's MediaStore, allows browsing by **folder / tag / rating**, supports **manual tagging & rating**, and uses **DashScope (Alibaba Cloud Bailian) AI** to automatically score photos (1-100) via `qwen-vl-plus` multimodal Vision-Language Model.

---

## Overview

- **App name**: pyqAlbum
- **Grid background**: Black (instead of white) for empty areas in image squares
- **View layouts**: Grid / Waterfall / Justified — all support click & long-press
- **Click image** → opens full-screen detail (tap image to toggle toolbar/bars)
- **Long-press** → action dialog: assign rating / add tag / select for AI ranking
- **AI Selection flow**:
  1. Tap toolbar **✨** icon to open **AI Select** screen
  2. Tap images to select them (green border + checkmark)
  3. Selected images shown in a horizontal preview strip at top
  4. Tap **Submit & Rate** (bottom bar) to go to AI Rating
  5. AI selected images are persistent (SharedPreferences) and visible in the "AI Sel" section
- **Multi-select mode**: long-press enters multi-select; bottom bar has batch Tag / Rate / Remove Tag / AI Select
- **Justified Grid**: Fixed height, dynamic width — images fill rows proportionally by aspect ratio
- **Full-screen image**: tap image to toggle top bar + bottom nav

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
│           │   ├── db/
│           │   │   ├── AppDatabase.kt    # Room DB singleton
│           │   │   ├── ImageEntity.kt    # Room Entity: content URI primary key
│           │   │   ├── TagEntity.kt      # Room Entity: tag name
│           │   │   ├── ImageTagCrossRef.kt # Many-to-many cross reference
│           │   │   ├── ImageDao.kt       # Image queries
│           │   │   └── TagDao.kt         # Tag CRUD + image link queries
│           │   ├── model/
│           │   │   ├── ImageItem.kt      # Domain model for UI display
│           │   │   └── AiRatingResult.kt # AI response: score + reason
│           │   └── repository/
│           │       └── AlbumRepository.kt # MediaStore scan + Room flows
│           ├── ui/
│           │   ├── component/
│           │   │   ├── ImageThumbnail.kt # Square fit-center, black background, 400px Coil
│           │   │   ├── WaterfallGrid.kt  # Pinterest staggered grid, now with click/long-press
│           │   │   ├── JustifiedGrid.kt  # Fixed-height row-filling grid, now with click/long-press
│           │   │   ├── RatingBar.kt      # 0.5-star increments, Material Icons
│           │   │   ├── TagChip.kt        # Assist/input chip with remove
│           │   │   └── BottomActionBar.kt # Batch selection action bar
│           │   ├── navigation/
│           │   │   └── AppNavGraph.kt    # NavHost with 7 routes
│           │   ├── screen/
│           │   │   ├── AlbumScreen.kt    # Main: 3 browse modes × 3 view layouts, long-press actions
│           │   │   ├── AiSelectScreen.kt # Pick images for AI rating, Submit & Rate button
│           │   │   ├── TagScreen.kt      # Tag list → images per tag
│           │   │   ├── RatingScreen.kt   # Sort by user rating or AI score
│           │   │   ├── ImageDetailScreen.kt # Full-screen image, tap to toggle bars, tags + rating
│           │   │   ├── AiRatingScreen.kt # Config API key/model, select, run, view results
│           │   │   └── BatchEditScreen.kt # Batch tag / batch rate / batch resize
│           │   ├── theme/
│           │   │   └── Theme.kt          # Material3 dynamic color, light/dark
│           │   └── viewmodel/
│           │       └── AlbumViewModel.kt # Screen state management, now with addTagToImage
│           └── util/
│               ├── ImageUtil.kt          # Resize 50%, rescale to square w/ padding
│               ├── BatchProcessor.kt     # Batch add/remove tags, batch rescale
│               └── PermissionHelper.kt   # Runtime permissions helper
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── .github/workflows/
│   └── android-ci.yml
└── README.md
```

---

## 7 Navigation Routes (AppNavGraph.kt)

| Route | Screen | Purpose |
|-------|--------|---------|
| `album` | `AlbumScreen` | Main: browse by Folder/Tag/Rating, 3 view layouts, long-press actions |
| `tag` | `TagScreen` | Tag list → images per tag |
| `rating` | `RatingScreen` | Sort/filter by user rating or AI score |
| `image_detail/{uri}` | `ImageDetailScreen` | Full-screen image, tap to toggle bars, edit tags & rating |
| `ai_rating` | `AiRatingScreen` | API config, select images, run AI scoring |
| `ai_select` | `AiSelectScreen` | Pick images for AI ranking (persistent selection), Submit & Rate button |
| `batch_edit/{mode}` | `BatchEditScreen` | Batch tag / batch rate / batch resize |

---

## Key Technical Decisions

| Aspect | Decision |
|--------|----------|
| **Image loading** | Coil 3 with crossfade, fit-center non-cropping thumbnails, black bg |
| **Grid background** | Black for empty area in square thumbnails |
| **Database** | Room + KSP (compile-time DAO generation) |
| **Media access** | SAF + MediaStore (`READ_MEDIA_IMAGES` for API 33+) |
| **AI API** | OkHttp direct → dashscope-intl.aliyuncs.com/v1/chat/completions |
| **API key storage** | EncryptedSharedPreferences (AES-256-GCM) |
| **Default model** | `qwen-vl-plus` |
| **Batch size** | 10 images per AI request |
| **Image resize** | Max 800px longest side, JPEG Q80, cached in `ai_rating/` |
| **Thumbnails** | 400px decoded size, no cropping |
| **minSdk / targetSdk** | 26 / 35 |
| **View layouts** | Grid / Waterfall / Justified (no list view) |
| **AI selection** | Persistent via SharedPreferences, Submit & Rate button in AiSelectScreen |
| **Long-press** | Opens action dialog: Assign Rating / Add Tag / Select for AI Ranking |
| **Full-screen toggle** | Tap image in ImageDetailScreen to show/hide top bar |

---

## Key UI Interactions

| Action | Result |
|--------|--------|
| **Tap thumbnail** | Open image in full-screen detail view |
| **Long-press thumbnail** | Enter multi-select mode (first tap) OR open action dialog |
| **Tap full-screen image** | Toggle visibility of toolbar and controls |
| **Bottom nav tabs** | Folder / Tag / Rating (AI Sel tab removed) |
| **Toolbar icons** | Grid / Waterfall / Justified layout toggles + ✨ AI Select |
| **AI Select screen** | Select images, see them in a preview strip, tap Submit & Rate |
| **Multi-select bar** | Batch actions: Tag / Rate / Remove Tags / Select for AI |

---

## Setup & Build

### Prerequisites

- **Android Studio** Ladybug (2024.2+) or newer
- **JDK 17** (Temurin recommended)

### GitHub Actions CI

```bash
cd /srv/pyqalbum
git init
git add .
git commit -m "Initial commit"
git remote add origin https://github.com/YOUR_USERNAME/pyqAlbum.git
git branch -M main
git push -u origin main
```

After push, go to **Actions** tab → workflow builds → download APK artifact.

---

## ⚠️ Outstanding Items

1. **pHash duplicate detection** — PHASH_THRESHOLD = 15, PENALTY_FACTOR = 0.1 — not yet integrated into AiRatingService
2. **Retrofit vs OkHttp** — AiRatingService uses raw OkHttp; Retrofit dependency unused
3. **App icon** — mipmap resources missing
4. **ProGuard rules** — proguard-rules.pro file missing
5. **Write external storage** — for batch resize, need proper SAF permissions
6. **Gradle wrapper JAR** — missing locally, GitHub Actions auto-generates it
7. **TagScreen image loading** — LaunchedEffect ordering issue
8. **Tests** — no unit tests or UI tests yet
9. **BottomActionBar** — old file is no longer used (replaced by BatchMultiSelectBar inline in AlbumScreen)

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

## Quick Reference — Key Files

1. **`AlbumScreen.kt`** — Main UI, browse modes, 3 layouts, long-press dialog, multi-select
2. **`AiSelectScreen.kt`** — AI selection with Submit & Rate bottom bar
3. **`AiRatingScreen.kt`** — AI scoring UI
4. **`ImageDetailScreen.kt`** — Full-screen view with tap-to-toggle controls
5. **`JustifiedGrid.kt`** — Fixed-height row-filling grid with click/long-press
6. **`WaterfallGrid.kt`** — Staggered grid with click/long-press
7. **`ImageThumbnail.kt`** — Black-background square thumbnails
8. **`AlbumViewModel.kt`** — State management, now with addTagToImage
9. **`AppNavGraph.kt`** — 7 routes
10. **`.github/workflows/android-ci.yml`** — CI/CD, already configured