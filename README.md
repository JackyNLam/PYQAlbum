# PyqCR - 朋友圈相册 (WeChat Moments Album)

An Android album management app with AI-powered photo rating, built with **Kotlin + Jetpack Compose**.

## Overview

PyqCR loads photos from the device's MediaStore, allows browsing by **folder / tag / rating**, supports **manual tagging & rating**, and uses **DashScope (Alibaba Cloud Bailian) AI** to automatically score photos (1-100) via `qwen-vl-plus` multimodal Vision-Language Model.

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
│           │   │   ├── AppDatabase.kt    # Room DB singleton (destructive migration fallback)
│           │   │   ├── ImageEntity.kt    # Room Entity: content URI primary key
│           │   │   ├── TagEntity.kt      # Room Entity: tag name
│           │   │   ├── ImageTagCrossRef.kt # Many-to-many cross reference
│           │   │   ├── ImageDao.kt       # Image queries (folder, tag, rating, AI score)
│           │   │   └── TagDao.kt         # Tag CRUD + image link queries
│           │   ├── model/
│           │   │   ├── ImageItem.kt      # Domain model for UI display
│           │   │   └── AiRatingResult.kt # AI response: score + reason
│           │   └── repository/
│           │       └── AlbumRepository.kt # MediaStore scan + Room flows
│           ├── ui/
│           │   ├── component/
│           │   │   ├── ImageThumbnail.kt # Square fit-center, 400px Coil loading
│           │   │   ├── WaterfallGrid.kt  # Pinterest staggered grid
│           │   │   ├── JustifiedGrid.kt  # Google Photos uniform row height
│           │   │   ├── RatingBar.kt      # 0.5-star increments, Material Icons
│           │   │   ├── TagChip.kt        # Assist/input chip with remove
│           │   │   └── BottomActionBar.kt # Batch selection action bar
│           │   ├── navigation/
│           │   │   └── AppNavGraph.kt    # NavHost with 6 routes
│           │   ├── screen/
│           │   │   ├── AlbumScreen.kt    # Main: 3 browse modes × 4 view layouts
│           │   │   ├── TagScreen.kt      # Tag list → images per tag
│           │   │   ├── RatingScreen.kt   # Sort by user rating or AI score
│           │   │   ├── ImageDetailScreen.kt # Full image + tags + rating editing
│           │   │   ├── AiRatingScreen.kt # Config API key/model, select, run, view results
│           │   │   └── BatchEditScreen.kt # Batch tag / batch rate / batch resize
│           │   ├── theme/
│           │   │   └── Theme.kt          # Material3 dynamic color, light/dark
│           │   └── viewmodel/
│           │       └── AlbumViewModel.kt # Screen state management
│           └── util/
│               ├── ImageUtil.kt          # Resize 50%, rescale to square w/ padding
│               ├── BatchProcessor.kt     # Batch add/remove tags, batch rescale
│               └── PermissionHelper.kt   # Runtime permissions helper
├── build.gradle.kts         # Root build script
├── settings.gradle.kts      # Project settings + repositories
├── gradle.properties         # JVM args, caching
├── gradle/
│   └── wrapper/
│       └── gradle-wrapper.properties  # Gradle 8.9 config
└── .github/workflows/
    └── android-ci.yml       # GitHub Actions CI/CD workflow
```

---

## 6 Navigation Routes (AppNavGraph.kt)

| Route | Screen | Purpose |
|-------|--------|---------|
| `album` | `AlbumScreen` | Main screen: browse by Folder/Tag/Rating, 4 view layouts |
| `tag` | `TagScreen` | Tag list → images per tag |
| `rating` | `RatingScreen` | Sort/filter by user rating or AI score |
| `image_detail/{uri}` | `ImageDetailScreen` | Full image, edit tags & rating |
| `ai_rating` | `AiRatingScreen` | API config, select images, run AI scoring |
| `batch_edit/{mode}` | `BatchEditScreen` | Batch tag / batch rate / batch resize |

---

## Key Technical Decisions

| Aspect | Decision |
|--------|----------|
| **Image loading** | Coil 3 with `CrossfadeImageLoader`, fit-center non-cropping thumbnails |
| **Database** | Room + KSP (compile-time DAO generation) |
| **Media access** | SAF + MediaStore (`READ_MEDIA_IMAGES` for API 33+) |
| **AI API** | OkHttp direct → `https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions` |
| **API key storage** | `EncryptedSharedPreferences` (AES-256-GCM) |
| **Default model** | `qwen-vl-plus` |
| **Batch size** | 10 images per AI request |
| **Image resize** | Max 800px longest side, JPEG Q80, cached in `ai_rating/` |
| **HTTP client** | OkHttp 4.12.0 (Retrofit also declared but unused) |
| **Thumbnails** | 400px decoded size, no cropping |
| **minSdk / targetSdk** | 26 / 35 |

---

## Requirements (pyqcr.md)

1. **Browse** photos by folder, tag, rating with 4 layouts: grid, waterfall, justified, list
2. **Tags**: create, assign, remove for individual images or batch
3. **Rating**: 0.5-star increments, manual + AI-driven
4. **AI Scoring**: DashScope Vision API (`qwen-vl-plus`), base64 images in batches of 10
5. **Batch resize**: 50% resize or pad-to-square via `BitmapFactory`
6. **Multi-select**: long-press to enter selection mode, bottom action bar for operations
7. **Permissions**: runtime request, proper MediaStore query
8. **Encrypted API key**: stored via `EncryptedSharedPreferences`

---

## AI Rating Prompt (from photo_rating.py)

> "你是一位資深攝影編輯，請嚴格依據以下標準給照片評分（1-100）：構圖、光線、色彩、主體清晰度、情感表達。高於80分為優秀，60-79為良好，40-59為一般，低於40為較差。以JSON Array回應，每項包含score和reason。"

Response format (per image): `{"score": 85, "reason": "Composition is well balanced..."}`

---

## Setup & Build

### Prerequisites

- **Android Studio** Ladybug (2024.2+) or newer
- **JDK 17** (Temurin recommended)
- **GitHub account** (for CI/CD)

### Step-by-step (use GitHub Actions — see below)

---

## 🚀 GitHub Actions CI/CD Setup — Step by Step

Since your hardware can't compile Android, we'll let GitHub do it. Here's exactly what to do:

### Step 1: Create a GitHub repository

Go to https://github.com/new and create a new repository (e.g., `PyqCR`). Make it **Public** or **Private** — either works.

### Step 2: Initialize Git and push

Run on this machine:

```bash
cd /srv/pyqalbum
git init
git add .
git commit -m "Initial commit: PyqCR Android app skeleton"
git remote add origin https://github.com/YOUR_USERNAME/PyqCR.git   # replace with your repo URL
git branch -M main
git push -u origin main
```

### Step 3: Watch GitHub Actions build

After pushing, go to your repo on GitHub → **Actions** tab → you'll see "Android CI" workflow running.

The workflow (already configured at `.github/workflows/android-ci.yml`):
1. Checks out code
2. Installs JDK 17
3. Sets up Gradle
4. Runs `lintDebug` (code style check)
5. Runs `testDebugUnitTest` (unit tests)
6. Runs `assembleDebug` (builds the APK)
7. Uploads the APK as a build artifact

### Step 4: Download the APK

After the build succeeds (≈5-8 minutes):

1. Go to the **Actions** tab
2. Click the completed workflow run
3. Scroll to **Artifacts** section
4. Download **app-debug.zip**
5. Extract the `.apk` and install on your Android phone

### Step 5: Install on phone

Transfer the APK to your Android phone and install it. Grant the storage permission when prompted on first launch.

> **Note**: The first time you open the app, tap the floating action button to refresh the image library from MediaStore. This loads all photos into the Room database.

---

## ⚠️ Outstanding Items / What's Not Yet Done

These are features from pyqcr.md that are **not yet implemented** in the codebase:

### 1. pHash duplicate detection (from photo_rating.py)
- `PHASH_THRESHOLD = 15`, `PENALTY_FACTOR = 0.1`
- When AI scoring, detect similar photos and apply score penalty
- Not yet integrated into `AiRatingService.kt`

### 2. Retrofit vs OkHttp unification
- `AiRatingService.kt` uses raw OkHttp calls
- `app/build.gradle.kts` also declares Retrofit (unused)
- Should choose one approach and remove the other dependency

### 3. App icon (mipmap)
- `AndroidManifest.xml` references `@mipmap/ic_launcher`
- No mipmap resource files exist yet
- Need to add at least `ic_launcher.xml` (adaptive icon) or PNG fallbacks

### 4. ProGuard rules
- `app/build.gradle.kts` references `proguard-rules.pro`
- File does not exist yet
- Need at least basic rules for Retrofit/OkHttp/Coil/Gson

### 5. Write external storage for batch resize
- `ImageUtil.kt` modifies images in-place via `ContentResolver`
- Need to ensure proper SAF permissions or use MediaStore `insertImage` for modified files

### 6. Gradle wrapper JAR
- `gradle-wrapper.properties` exists but `gradle-wrapper.jar` is missing
- GitHub Actions autogenerates it via `gradle/actions/setup-gradle@v3`, so CI works
- For local development, run `gradle wrapper --gradle-version=8.9` on a machine with Gradle installed

### 7. TagScreen's image loading
- `LaunchedEffect(selectedTag)` inside the LazyVerticalGrid may have ordering issues
- The image loading for selected tag is inside the composable but after the grid items — Kotlin Compose ordering may cause it to never trigger

### 8. BottomActionBar wiring
- `AlbumScreen.kt` has placeholder onClick actions for batch operations
- They need to navigate to `BatchEditScreen` with the selected URIs
- No ViewModel-level selection state for batch operations yet

### 9. Tests
- No unit tests or UI tests written yet
- `test` directory exists but empty (except JUnit dependency)

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
| Retrofit | 2.11.0 | HTTP client (unused currently) |
| OkHttp | 4.12.0 | HTTP client (used by AI) |
| Navigation Compose | 2.8.2 | Screen navigation |
| Security Crypto | 1.1.0-alpha06 | Encrypted API key storage |
| Gson | 2.11.0 | JSON parsing (AI response) |

---

## Key Config Values

- **API endpoint**: `https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions`
- **Default model**: `qwen-vl-plus`
- **Batch size**: 10 images/request
- **Max image dimension**: 800px (longest side)
- **JPEG quality**: 80
- **Thumbnail decode size**: 400px
- **AI score range**: 1-100
- **PHASH_THRESHOLD**: 15 (not yet implemented)
- **PENALTY_FACTOR**: 0.1 (not yet implemented)

---

## Quick Reference — Key Files to Continue Development

When you want to pick up where I left off, focus on these files (in priority order):

1. **`Ui/screen/AiRatingScreen.kt`** — AI scoring UI is the main value-add feature
2. **`Ai/AiRatingService.kt`** — API call logic, needs pHash dedup integrated
3. **`Ui/screen/AlbumScreen.kt`** — Main screen, browse modes + view layouts
4. **`Data/repository/AlbumRepository.kt`** — MediaStore scanning + Room CRUD
5. **`Ui/component/JustifiedGrid.kt`** — Google Photos-style uniform row layout
6. **`.github/workflows/android-ci.yml`** — CI/CD (already set up, push to GitHub)