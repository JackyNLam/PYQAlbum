# pyqAlbum - 朋友圈相册 (WeChat Moments Album)

An Android album management app with AI-powered photo rating, built with **Kotlin + Jetpack Compose**.

pyqAlbum loads photos from the device's MediaStore, allows browsing by **folder** or **tag**, and uses **DashScope (Alibaba Cloud Bailian) AI** to automatically score photos (1-100) via `qwen3.8-omni-flash` multimodal Vision-Language Model.

---

## Overview

- **App name**: pyqAlbum
- **Grid background**: White for all empty areas in image squares
- **View layouts**: Grid / Waterfall / Justified (single dropdown button in toolbar)
  - **Grid**: 3 columns, square thumbnails (1:1 aspect ratio)
  - **Waterfall (River)**: 3 columns, Pinterest-style staggered heights
  - **Justified Grid**: 3 images per row, fixed height (120dp), dynamic width proportional to aspect ratio
- **Navigation**: Left-side drawer menu (☰) — Folder, Tag, AI Edit, AI Rating, Photo Vault, Recycle Bin
- **Click thumbnail** → full-screen detail view (safe null handling, no crash)
- **Long-press** → enters multi-select mode directly (no intermediate dialog); bottom bar shows selected count, action menu (⋮), and Cancel
- **Sort inside a folder**: Use the Sort (↕) button in the toolbar — every criterion offers both directions:
  - **↓ / ↑Modified date** (desc default) — newest / oldest photos first
  - **↑ / ↓Name** — alphabetical / reverse-alphabetical by filename
  - **Rating ↓ / ↑** — highest / lowest user rating first
  - **AI Score ↓ / ↑** — highest / lowest AI rating first
  - **Group by** submenu: None / Day / Month / Year / Rating Score / AI Score — organize thumbnails into sections with a bar header (long-press a bar to batch-select that whole group)
- **AI Rating flow (streamlined)**:
  1. Long-press any image in Album → choose **Select for AI Ranking**
  2. Or tap drawer ✨ AI Rating to go directly to AI Rating view
  3. The AI Rating screen shows all selected images in a **square grid** (no separate list view)
  4. **Save Config** button persists API Key + Model name
  5. **Run AI Rating** button (above the image grid) starts scoring in the **background** (WorkManager) — rating continues even if you leave the screen; a determinate progress bar + "X/Y images done" + status line appear right below the button while it runs
  6. **Submit to AI Rating** button (below the grid) runs the same scoring in the foreground with real-time progress feedback and a full debug log
  7. **Stop button** appears while either run is active — cancels the foreground scoring (per-image, responsive) or the background WorkManager job
  8. No image-count limit — selections of any size are rated (URI list is passed to the background worker via a cache file, sidestepping WorkManager's 10 KiB input-data cap)
  9. Selection persists across app launches (SharedPreferences)
- **Multi-select mode**: long-press enters multi-select; the bottom bar's ⋮ menu has **Select All / Deselect All** (applies to the current folder or tag view), **Collage**, batch Tag / Rate / **Favourite** / Remove Tags / **AI Ranking** (one merged button: queues the images for AI rating AND clears their stale AI info so they get re-ranked), **Copy to…** and **Move to…** (in-app folder browser), **Move to Vault**, and **Delete** — Delete **moves the images to the system recycle bin first** (Android 11+ trash, restorable) instead of deleting permanently; the recycle bin itself is browsable from the left drawer (per-image **Restore** via the system recovery request)
- **Remove Tags** (multi-select ⋮) lists **only the tags actually present on the selected images** — tags that none of the selected images carry are not offered
- **Collage**: select ≥2 images → ⋮ → **Collage** — opens an options dialog first: **background color** for empty space (White / Black / Gray / Beige / Light Blue / Pink), **number of columns** (2–5), and **image order** (▲▼ reorder; images flow left-to-right, top-to-bottom). Images are scaled to fit uniform cells on the chosen background and saved to `Pictures/PYQAlbum/collage_<timestamp>.jpg`; all inputs plus the output are auto-tagged with a unique **`Collage <timestamp>`** tag so the set is easy to find and re-edit under the Tag view
- **Copy**: files are written via ContentResolver stream to the chosen folder (works on all Android versions)
- **Move**: real file move — same-volume moves use `File.renameTo` (instant, preserves the modified date); cross-volume / content-only URIs fall back to copy + restore the modified date + delete the source (API 30+ uses a recoverable delete request)
- **Default destination**: `Pictures/PYQAlbum/` (fallback if external folder can't be resolved)
- **Full-screen image**: Tap image to toggle controls; **swipe down to go back** to thumbnail grid (folder or tag context preserved); **swipe up to reveal** rating, tags, AI toggles and the full batch-action set (Resize / Copy / Move / Move to Vault / Delete — the same operations as the album batch ⋮ menu); **swipe left/right** to navigate to next/previous image in the same folder/tag
- **Zoom & pan** (detail view): pinch (or double-tap) to zoom up to 5x; while zoomed, drag to pan — the image tracks the finger 1:1 at any zoom level and is clamped so it can never be dragged out of view
- **Animated images**: GIF and animated WebP play inline (Coil `coil-gif`); **motion photos** (JPEG with an embedded MP4, e.g. Pixel / Samsung "moving photo") show a ▶ button that plays the embedded video loop
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
│           │   ├── AiEditService.kt      # qwen-image-2.0 AI edit (DashScope)
│           │   ├── AiEditSelectionStore.kt # AI Edit image-selection prefs (shared by album/detail screen/edit screen)
│           │   ├── AiRatingService.kt    # DashScope API batch scoring (10 img/batch)
│           │   └── ImageResizer.kt       # Resize to 800px max, cache in ai_rating/
│           ├── data/
│           │   ├── db/ ...               # Room DB, entities, DAOs
│           │   ├── model/ ...            # Domain models
│           │   └── repository/ ...       # MediaStore scan + Room flows
│           ├── ui/
│           │   ├── component/ ...        # ImageThumbnail, WaterfallGrid, JustifiedGrid, RatingBar, TagChip
│           │   ├── navigation/ ...       # AppNavGraph with 9 routes
│           │   ├── screen/ ...           # All screens (incl. VaultScreen — password vault)
│           │   ├── theme/ ...            # Material3 dynamic color
│           │   └── viewmodel/ ...        # AlbumViewModel
│           └── util/ ...                 # ImageUtil, BatchProcessor, PermissionHelper, MotionPhotoExtractor
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── .github/workflows/android-ci.yml
└── README.md
```

---

## 9 Navigation Routes

| Route | Screen | Purpose |
|-------|--------|---------|
| `album` | `AlbumScreen` | Main album browser: folder grid → image grid with 3 view layouts, left drawer menu |
| `tag` | `TagScreen` | Tag list → images per tag *(can be discovered via drawer, or navigated separately)* |
| `rating` | `RatingScreen` | Sort/filter by user rating or AI score *(still accessible via nav route)* |
| `image_detail/{uri}` | `ImageDetailScreen` | Full-screen view, swipe down to go back, swipe up for controls; zoom/pan, GIF & motion-photo playback, file size, AI Ranking/AI Edit toggles + Favourite |
| `ai_edit` | `AiEditScreen` | **AI Image Editing**: qwen-image-2.0 — first image(s) + area/brush selection + optional second/third reference images + prompt (10 saved slots) |
| `ai_rating` | `AiRatingScreen` | **AI Rating**: config + selected image grid + submit to DashScope; the detail-screen AI Ranking toggle feeds the shared selection queue |
| `ai_select` | `AiSelectScreen` | **Bridge screen** — automatically forwards to AiRatingScreen |
| `batch_edit/{mode}` | `BatchEditScreen` | Batch tag / rate / resize |
| `vault` | `VaultScreen` | **Photo Vault**: private storage hidden from other gallery apps — full-screen folder-filtered **Move from Gallery** (the originals go to the system recycle bin), optional AES-256-GCM encrypt/decrypt with the vault password, move out to the gallery |

---

## Key UI Interactions

| Action | Result |
|--------|--------|
| **☰ (top-left hamburger)** | Opens the left drawer: Folder / Tag / AI Edit / AI Rating / Photo Vault / Recycle Bin |
| **Tap folder card** (Folder mode) | Enter folder to see images in selected layout (folder cards show image count, sortable by modified date / count / name) |
| **Tap thumbnail** | Opens full-screen detail view |
| **Long-press thumbnail** | Action dialog: Assign Rating / Add Tag / Select for AI Ranking (toggle) |
| **Favourite** (♥) | Marks images in **MediaStore `IS_FAVORITE`** (Android 13+), so other apps that read MediaStore (WeChat image picker, Google Photos' Favourites, …) can identify them — batch menu **Favourite**, and a **Mark as Favourite** button in the detail panel; ♥ badge on favourited thumbnails |
| **Swipe down on image** (detail view) | Go back to thumbnail grid |
| **Swipe left** (detail view) | Next image in same folder/tag |
| **Swipe right** (detail view) | Previous image in same folder/tag |
| **Swipe up on image** (detail view) | Reveal rating bar, tags, AI selection panel |
| **Toolbar Layout button** (inside folder) | Dropdown: Grid (▦) / Waterfall (🌊) / Justified (▭) |
| **Toolbar Sort button** (folder list) | Dropdown: ↓Modified date (default) / ↑Modified date / ↓Count / ↑Count / ↑Name / ↓Name |
| **Toolbar Sort button** (inside folder) | Dropdown: ↓Modified date (default) / ↑Modified date / ↑Name / ↓Name / Rating ↓ / Rating ↑ / AI Score ↓ / AI Score ↑ / Group by submenu |
| **Toolbar Sort button** → **Group by submenu** | None / Day / Month / Year / Rating Score / AI Score — group bar headers; **long-press a header to batch-select every image in that group** |
| **Left drawer** | Switch between Folder, Tag browse modes, or go to AI Edit / AI Rating |
| **AI Edit** | Via drawer ✏️ |
| **AI Rating** | Via drawer ✨ |
| **Multi-select mode** | Batch actions (⋮ menu): Select All / Deselect All / Collage / Tag / Rate / **Favourite** / Remove Tags / **AI Ranking** (queues + clears stale AI info) / Copy to… / Move to… / **Move to Vault** / Delete (recycle bin first on Android 11+) |
| **Select All / Deselect All** (⋮ in multi-select) | Toggles selection of every image in the current folder or tag view |
| **Collage** (⋮ in multi-select) | Builds a collage from ≥2 selected images; tags inputs + output with a unique `Collage <timestamp>` tag |
| **Thumbnail rating badge** | Gold number (e.g. `3.5` or `5.0`) at top-left corner of thumbnails with ratings |
| **AI Selection badge** | ✨ AutoAwesome icon at bottom-right of AI-selected thumbnails |

### Browse Modes (via Left Drawer)

#### Folder mode (default — no drawer tab selected)
- **Step 1**: Shows a 2-column folder grid with folder icons, image count per folder
- **Step 1 sort** (↕ button in toolbar): **↓Modified date** (default, newest first) / **↑Modified date** (oldest first) / **↓Count** (most images first) / **↑Count** (fewest first) / **↑Name** (alphabetical) / **↓Name** (reverse alphabetical)
- **Step 2**: Tap a folder → images displayed in the selected layout (Grid/Waterfall/Justified)
- Layout toggle and sort button appear in toolbar when inside a folder
- Default thumbnail sort: **↓Modified date** (newest first)
- Sort options: **↓Modified date** (default) / **↑Modified date** / **↑Name** / **↓Name** / **Rating ↓** / **Rating ↑** / **AI Score ↓** / **AI Score ↑** — with ✓ checkmark on active option
- **Group by**: submenu with None / Day / Month / Year / Rating Score / AI Score — sections thumbnails by header bar; "Rating Score" groups by star rating (highest first, "Unrated" last), "AI Score" groups by rounded score ("AI 87", "No AI score" last)

#### Tag mode
- Grid of tag cards — tap a tag to see all images with that tag
- A **Favourite** card at the top of the tag list opens the MediaStore favourites (Android 13+ `IS_FAVORITE` flag) as a pseudo-tag browse view
- Tap **All Tags** back button to return to tag list
- Tags are assigned via long-press → **Add Tag** action
- Existing tags are shown as selectable options when adding a tag

---

## Details on Key Features

### Folder View
- **Step 1**: 2-column grid of `ElevatedCard`s with folder icons, **image count** shown below folder name
- **Folder list sort** (↕ button in toolbar when on folder grid):
  - **↓Modified date** (default) — newest folder first (by latest image date)
  - **↑Modified date** — oldest folder first
  - **↓Count** — folders with most images first
  - **↑Count** — folders with fewest images first
  - **↑Name** — alphabetical order
  - **↓Name** — reverse alphabetical order
- **Step 2**: Tap a folder → "← All Folders" back button at top, then image grid
- Sort images inside a folder via the Sort button (↕):
  - **↓Modified date** (default) — newest first
  - **↑Modified date** — oldest first
  - **↑Name** — alphabetical by filename
  - **↓Name** — reverse alphabetical
  - **Rating ↓** — highest user rating first
  - **Rating ↑** — lowest user rating first
  - **AI Score ↓** — highest AI score first
  - **AI Score ↑** — lowest AI score first
  - **Group by** submenu: organize thumbnails into sections by **Day**, **Month**, **Year**, **Rating Score** (e.g. `5.0 ★`, "Unrated" last), or **AI Score** (e.g. `AI 87`, "No AI score" last) — each group has a header bar, and **long-pressing a header bar enters multi-select with the whole group selected** (for batch rating / tagging / copying / deleting)

### Grid View
- **3 columns** of square thumbnails (1:1 aspect ratio)
- White background, `ContentScale.Fit` keeps original proportions
- `combinedClickable` with click → detail and long-press → action dialog
- Multi-select: long-press enters mode, selected images get overlay with ✓ badge
- Batch actions (⋮ menu): **Select All / Deselect All**, **Collage**, Tag / Rate / **Favourite** / Remove Tags / **AI Ranking** (one button — adds to the rating queue and clears stale AI info), **Copy to…** / **Move to…** (in-app folder browser), **Move to Vault**, Delete (recycle bin first on Android 11+)
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
- **Swipe up** → reveals detail panel with **name, folder, full path, pixel size, and file size (MB/KB)**, rating bar, tags, and **AI Actions toggles**
- **AI Actions** (below the tag area), following the batch menu's function:
  - **AI Ranking** — toggles this image in/out of the AI Rating selection queue (✓ shows it's queued; run the rating from the drawer ✨ AI Rating)
  - **AI Edit** — opens a role dialog (**As AI Edit source / As AI Edit target**, tap again to remove) that toggles the image in the AI Edit queue, exactly like the batch menu's AI Edit dialog
  - **Mark as Favourite** — sets/unset the Android 13+ MediaStore `IS_FAVORITE` flag so other apps (WeChat, Google Photos) can identify the image
- **More Actions** (below the Favourite button — the rest of the album batch ⋮ menu applied to this single image):
  - **Resize** — the same options dialog as the batch menu (width 500/1000/2000/custom; replace original or save a `_resized` copy)
  - **Copy** / **Move** — in-app folder browser (+ optional SAF picker); a move re-maps the image's tags to the new MediaStore URI and drops the original via the recycle bin
  - **Vault** — copies the image into the photo vault, then sends the original to the recycle bin
  - **Delete** — recycle bin first on Android 11+, permanent on older APIs
- **Swipe left** → next image in same folder/tag
- **Swipe right** → previous image in same folder/tag
- **Tap on image** (when panel is hidden) to toggle top bar
- **Swipe down on the detail panel itself** to hide it again
- **Zoom & pan**: pinch or double-tap to zoom up to 5x; single-finger drag pans and **tracks the finger 1:1 at any zoom** (pointer deltas are multiplied by the layer scale — without this, high-DPI screens moved the image far less than the finger), and the pan is **clamped to the ContentScale.Fit bounds** so the zoomed image can't be flung out of view
- **GIF / animated WebP**: play automatically (Coil `coil-gif` decoder registered in `PyqCrApp.newImageLoader`)
- **Motion photos** (moving photos — JPEG with an embedded MP4, e.g. Google Pixel / Samsung): a ▶ "Motion photo" chip appears; tapping it extracts the embedded video (`MotionPhotoExtractor` validates the MP4 `ftyp` box and probes the result with `MediaMetadataRetriever`, so EXIF false positives are skipped) and plays it as a looping video over the still frame — the player automatically retries once on a transient decode error, with a ✕ Close button

### AI Rating Screen
- **Single unified view** — no separate selection step
- **API Configuration** card with:
  - DashScope API Key input (encrypted storage via EncryptedSharedPreferences)
  - Model Name input (e.g. `qwen3.8-omni-flash`)
  - **Save Config** button — persists both API Key and Model name
- **Run AI Rating** button — sits **above the image grid** and runs the rating in the **background** via WorkManager (keeps running even if you leave the screen)
  - While any rating is in progress, a **determinate progress bar** appears below the button with "**X / Y images done**", a percentage, and a phase status line ("Resizing… / AI Rating — n/m images / Saving scores…") — for both foreground and background runs (background progress is polled via `BackgroundProgress`)
  - Polls the background worker every 2 seconds (`AiRatingWorkManager.isRunning`) and shows the background worker's debug log on-screen in the same terminal-style panel ("Clear Background Debug Log")
  - Disabled while a foreground or background run is active, or when there is no API key or selection
  - A red **Stop** button replaces the progress area controls while a run is active: it cancels the foreground coroutine immediately (mid-batch HTTP calls are aborted via OkHttp call cancellation) or cancels the WorkManager job for background runs
  - Handles **more than 50 images** — the Worker's URI list is passed through a cache file, so arbitrary selection sizes work (WorkManager input data is capped at 10 KiB)
- **Selected images** shown in a **3-column square grid** (not a list view):
  - Each thumbnail has a green border + ✕ overlay
  - Tap any thumbnail to deselect it
  - "Select All" / "Deselect All" toggle
- **Submit to AI Rating** button — runs the full pipeline: resize → API call → save scores
  - Resize is OOM-safe and never drops an image on its own: huge files skip the full byte-array read, decode falls back through bounds/sampled/stream paths, and as a last resort the **original bytes are sent** so the image still reaches the AI API
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
  - Full HTTP response body (status code + raw JSON, up to 3000 chars) — so you see actual API errors
  - Each AI result (name, score, reason)
  - DB save status per image
  - All exceptions with stack traces (timeout, connection errors, etc.)
  - Retry tracking (up to 3 attempts per batch)
  - All errors/warnings clearly marked with ❌ or ⚠️
  - "Clear Debug Log" button to reset
- **Progress bar** with percentage indicator
- Results section: sorted by score descending, shows score + AI reasoning
- "Clear All" action in top bar to reset selection
- Error messages shown via Toast for empty API key, missing images, or API failures

### AI Edit Screen
- **API Configuration** card with:
  - DashScope API Key input (encrypted storage via separate EncryptedSharedPreferences from AI Rating)
  - Model Name input (default `qwen-image-2.0`)
  - **Save Config** button — persists API Key, Model name, **and** the edit prompt in one tap; the prompt is also written into the saved-prompt library (see below)
- **Saved Prompt selector**:
  - Read-only dropdown field above the prompt box showing the active slot (e.g. `Prompt 2 · text…`, or `Prompt 3 (empty)`)
  - The dropdown lists **all 10 slots** — filled ones show their text preview, empty ones show "(empty)"; ✓ marks the active slot, ✕ clears (not removes) a slot so the 1–10 numbering never shifts
  - Picking a **filled** slot loads its text into the prompt box; picking an **empty** slot clears the box and marks that slot as the write target
  - "No saved prompts yet" when every slot is empty; a "N/10 slots used" counter sits under the Save Config button
  - The old separate **Save Prompt** button is gone — **Save Config** now handles both: it writes the prompt box into the selected slot (overwrite), or into the slot that already holds the same text, or into the first free slot ("All 10 slots full — select a slot to overwrite" if none)
- Multi-line `OutlinedTextField` (3–6 lines) for the user's custom edit instruction (e.g. "replace the person with the one from the target image")
- **Source Images** card:
  - Tap to open a **4-column LazyVerticalGrid** gallery picker to select source images
  - Tap an **unselected** thumbnail → opens a drag-to-select **area dialog** (`AreaSelectionDialog`): drag a rectangle over the image to choose the exact region to edit (coordinates mapped through `ContentScale.Fit` letterboxing)
  - Selected thumbnails show a **green border + white crop-rectangle overlay** (`ThumbnailCropOverlay`, letterboxing-corrected) labelled with the selection size in % (e.g. `60×40%`), and a red ✕ to remove
  - Tap an already-selected thumbnail in the picker to deselect it
  - At least one source image with a selected area required to run
- **Target Image** card:
  - Tap to open a **4-column LazyVerticalGrid** gallery picker for single reference/target image
  - Orange border (Color 0xFFFF9800) on the selected target thumbnail
  - Close button (✕) to deselect target
  - Optional — if omitted, the edit operates on source image content alone
- **Third Image** card (reference, like the second):
  - Same single-select picker as the Second Image — a purple border (Color 0xFF9C27B0) marks the selection, ✕ removes it
  - Optional; the request sends first image, then second, then third, then the prompt (both the multimodal-generation endpoint and the chat-compatible fallback)
- **Run AI Edit** button:
  - Validates API key, at least one source area, and non-empty prompt
  - Launches `AiEditService.editImages()` via OkHttp coroutine
  - **LinearProgressIndicator** with "X / Y images done" status
- **Input pipeline** (per selected area): EXIF orientation correction → crop to the selected `CropRect` → resize keeping the selection's aspect ratio into the API-accepted range (min side 512px, max side 2048px, snapped to multiples of 16, no square center-crop) → encode JPEG → send with `size=<sentW>*<sentH>` matching the sent image, so the input is exactly the selected area and the AI result comes back at the same size on the same 16px grid the API generates on
- **Composite & save**: the downloaded AI result is resized back to the **exact pixel size of the original selection** (`scaleToFit`), then drawn onto the original at integer-pixel coordinates (`Rect`-based `drawBitmap`, `isFilterBitmap=false`) and saved to `Pictures/PYQAlbum/` via MediaStore
- **Debug images**: for every cropped edit, three gallery images are saved with a shared base name for side-by-side debugging — `<base>.jpg` (final composite), `<base>_input.jpg` (the exact image sent to the AI, scaled at the selection's aspect ratio), `<base>_result.jpg` (raw AI result before any correction). Used to diagnose the edited area not aligning with the rest of the picture
- **Debug Log** panel — dark terminal-style card (Color 0xFF1E1E2E background + 0xFFCDD6F4 text, monospace font), wrapped in `SelectionContainer` so the log text is **selectable/copyable**:
  - Per-image progress: crop → encode → API send → retry tracking → download → save
  - Full HTTP response status and body preview (up to 2000 chars)
  - AI result dimensions vs expected crop area, plus saved debug image URIs
  - Error classification: quota exhausted, invalid API key, access denied, account arrears
  - Retry logic: up to 3 attempts per image
  - "Clear Debug Log" button
- **Results section**: list of saved file paths for successfully edited images
- Uses **qwen-image-2.0** (DashScope `multimodal-generation/generation` endpoint) with fallback to chat-compatible endpoint
- The service encodes source and target images to base64, composes a multimodal request with the custom prompt, downloads the edited result image, and composites it back onto the original at the selected position

### Photo Vault
- **Private area** reached via the left drawer 🔒 **Photo Vault** — files live in app-private storage (`externalFilesDir/vault`), **invisible to other gallery apps** and to the album's own MediaStore-backed views; no password needed to browse
- **Import from Gallery**: **full-screen** in-app gallery grid picker with a **folder filter** (chips row: "All folders" + each gallery folder) — each imported image is **copied as-is** (its original bytes: JPEG, PNG, GIF, motion photos…) out of MediaStore into the vault; **no auto-encryption** — entries are visible without a password
- **Encrypt** (viewer action): protects one entry with **AES-256-GCM** (per-file random salt, key derived from the vault password via PBKDF2). The first Encrypt asks you to **set the vault password**; afterwards the password is re-entered to view/decrypt encrypted entries. Encrypted entries show a 🔒 tile instead of a preview
- **Decrypt** (viewer action): removes the password protection back to a plaintext entry (requires the vault password)
- **Move out** (viewer action): exports the entry back to the gallery (`Pictures/PYQAlbum` via MediaStore) and removes the vault copy — encrypted entries must be unlocked first
- **View**: tap a plaintext entry to view it directly; GIFs/animated WebP play through Coil and motion photos get a ▶ "Motion photo" chip; tap an encrypted entry to be asked for the vault password first
- **Delete**: removes the entry from the vault (irreversible)
- Summary line shows "N in vault · N encrypted"

### Tag/Rating Assignment
- **Long-press dialog** shows existing tags as selectable buttons before offering a "create new" option
- Rating updates persist correctly to Room DB via `AlbumViewModel.updateRating()`
- Tag names are enforced unique at the database level via Room UNIQUE index on the `name` column
- Batch tag creation reuses existing tags: if a tag name already exists, the insert is silently ignored and the existing ID is used via race-condition-safe re-query
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
| Coil | 3.0.4 | Image loading (incl. `coil-gif` for animated GIF/WebP) |
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
3. **Write external storage** — batch resize needs proper SAF permissions
4. **Gradle wrapper JAR** — missing locally (GitHub Actions auto-generates)
5. **TagScreen image loading** — LaunchedEffect ordering issue
6. **Tests** — no unit tests or UI tests yet
7. **BottomActionBar.kt** — old component file, no longer used (replaced by inline BatchMultiSelectBar)