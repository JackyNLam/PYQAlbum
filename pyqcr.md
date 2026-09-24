# Android Album App — 朋友圈相册 App (PyqCR)

## 项目概述

一个 Android 端相册管理应用，支持按文件夹、标签、评分浏览照片，可进行标签/评分编辑、批量操作、AI 智能评分排序、批量缩放/裁剪等功能。全流程通过 GitHub Actions 实现 CI/CD 自动化编译、构建与测试。

---

## 1. 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Kotlin + Jetpack Compose |
| 构建 | Gradle (Kotlin DSL) |
| CI/CD | GitHub Actions |
| 图片加载 | Coil 3 |
| 本地数据库 | Room |
| 本地存储访问 | Storage Access Framework + MediaStore |
| AI 推理 | 用户自行提供 API Key + Model Name，App 发送 HTTP 请求到 DashScope (阿里云百炼) API |
| 图片处理 (原生端) | Android BitmapFactory + ExifInterface |
| 缩略图 | Coil 的 `CrossfadeImageLoader` + 自定义 `fit-center` 非裁剪缩放 |

---

## 2. 项目结构

```
PyqCR/
├── .github/
│   └── workflows/
│       └── android-ci.yml          # GitHub Actions workflow
├── app/
│   ├── build.gradle.kts
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── java/com/pyqcr/
│           │   ├── MainActivity.kt
│           │   ├── PyqCrApp.kt                      # Application class
│           │   ├── data/
│           │   │   ├── db/
│           │   │   │   ├── AppDatabase.kt            # Room 数据库
│           │   │   │   ├── ImageDao.kt
│           │   │   │   ├── TagDao.kt
│           │   │   │   ├── ImageTagCrossRef.kt
│           │   │   │   ├── ImageEntity.kt            # 图片实体
│           │   │   │   └── TagEntity.kt              # 标签实体
│           │   │   ├── repository/
│           │   │   │   └── AlbumRepository.kt
│           │   │   └── model/
│           │   │       ├── ImageItem.kt
│           │   │       └── AiRatingResult.kt
│           │   ├── ai/
│           │   │   ├── AiRatingService.kt            # 调用阿里云百炼 API
│           │   │   └── ImageResizer.kt               # 缩略图前缩小图尺寸
│           │   ├── ui/
│           │   │   ├── navigation/
│           │   │   │   └── AppNavGraph.kt
│           │   │   ├── screen/
│           │   │   │   ├── AlbumScreen.kt            # 文件夹模式
│           │   │   │   ├── TagScreen.kt              # 标签模式
│           │   │   │   ├── RatingScreen.kt           # 评分排序/筛检模式
│           │   │   │   ├── ImageDetailScreen.kt      # 单张详情
│           │   │   │   ├── AiRatingScreen.kt         # AI 评分配置 + 执行
│           │   │   │   └── BatchEditScreen.kt        # 批量操作(标签/评分)
│           │   │   ├── component/
│           │   │   │   ├── ImageThumbnail.kt         # 方形 fit-center 缩略图
│           │   │   │   ├── WaterfallGrid.kt          # 瀑布流网格（Pinterest 风格）
│           │   │   │   ├── JustifiedGrid.kt          # 均匀网格（每行高度一致，宽度自适应）
│           │   │   │   ├── TagChip.kt
│           │   │   │   ├── RatingBar.kt
│           │   │   │   └── BottomActionBar.kt
│           │   │   └── theme/
│           │   │       └── Theme.kt
│           │   └── util/
│           │       ├── ImageUtil.kt                  # 批量缩放/裁剪/缩小尺寸
│           │       ├── BatchProcessor.kt
│           │       └── PermissionHelper.kt
│           └── res/
│               ├── values/
│               │   ├── strings.xml
│               │   └── themes.xml
│               └── drawable/
├── build.gradle.kts                                 # 根项目
├── settings.gradle.kts
├── gradle.properties
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── gradlew
├── gradlew.bat
└── README.md
```

---

## 3. 功能需求 & 实现要点

### 3.1 图片浏览 — 三种模式

| 模式 | 说明 |
|------|------|
| **📁 文件夹** | 通过 `MediaStore.Images.Media` 按 `BUCKET_DISPLAY_NAME` 分组 |
| **🏷️ 标签** | Room 查询 `ImageDao.getImagesByTag(tagName)` |
| **⭐ 评分** | Room 查询 `ImageDao.getImagesByRating(min, max)` 或排序 |

**三种浏览模式**切换在底部导航栏或顶部 Tab。

**四种视图布局**（在每种浏览模式下均可切换）：

| 视图 | 说明 | 实现 |
|------|------|------|
| **📐 标准网格** | 固定列数（如 3 列），所有格子等宽等高正方形 | `LazyVerticalGrid(columns = Fixed(3))` + `ImageThumbnail` |
| **🌊 瀑布流** | 固定列数，每张缩略图保持原始宽高比（Pinterest 风格），列间高度独立。常用于展示不同裁切比的照片，视觉效果更自然 | `LazyVerticalStaggeredGrid(columns = StaggeredCells.Fixed(2))` 或自定义 `SubcomposeLayout` |
| **📏 均匀网格** | 每行固定高度，图片宽度按原始宽高比自适应填充整行，行末未满则拉伸。适合展示不同横竖比例照片的统一浏览体验 | 自定义 `SubcomposeLayout` + 图片宽高比预取 + 行填充算法 |
| **📋 列表** | 单列文字 + 缩略图横排 | `LazyColumn` |

- 视图切换按钮放在浏览页面的顶部工具栏，图标示意：标准网格 ⬚、瀑布流 🌊、均匀网格 ▭、列表 ☰
- 当前选中视图高亮显示
- 用户切换视图时保留当前浏览模式（文件夹/标签/评分）不变

### 3.2 标签 & 评分系统

**数据库设计 (Room)**:

```kotlin
@Entity(tableName = "images")
data class ImageEntity(
    @PrimaryKey val uri: String,            // content:// URI as PK
    val displayName: String,
    val rating: Float = 0f,                 // 0.0 - 5.0
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val dateAdded: Long,
    val folderName: String,
    val aiScore: Float? = null              // AI 评分 (1-100)，可选
)

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@Entity(
    tableName = "image_tag_cross_ref",
    primaryKeys = ["imageUri", "tagId"]
)
data class ImageTagCrossRef(
    val imageUri: String,
    val tagId: Long
)
```

**UI 操作**:
- 单张图片详情页：显示标签列表（可删除） + 添加标签输入框 + 评分 StarBar（0.5 星步进）
- **批量操作**：长按多选后，底部 ActionBar 出现「批量添加标签」「批量评分」「批量删除标签」按钮

### 3.3 缩略图 & 三种网格视图实现

#### 标准正方形网格（默认）

使用 Compose `Box` + `Modifier.aspectRatio(1f)` + Coil `SubcomposeAsyncImage`，图片 `ContentScale.Fit` 居中，不裁剪：

```kotlin
@Composable
fun ImageThumbnail(
    imageUri: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)          // 强制正方形
            .clipToBounds()           // 超出部分隐藏
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(imageUri)
                .size(400)            // 限制解码尺寸
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center), // 居中
            contentScale = ContentScale.Fit // fit-center 不裁剪
        )
    }
}
```

#### 瀑布流网格 StaggeredGrid

使用 Compose Foundation 的 `LazyVerticalStaggeredGrid`（自 Compose 1.6+ / Material3 引入），每列的图片保持原始宽高比：

```kotlin
@Composable
fun WaterfallGrid(
    images: List<ImageItem>,
    columns: Int = 2,
    modifier: Modifier = Modifier
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredCells.Fixed(columns),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalItemSpacing = 4.dp,
        modifier = modifier
    ) {
        items(images, key = { it.uri }) { image ->
            WaterfallTile(image = image)
        }
    }
}

@Composable
fun WaterfallTile(image: ImageItem) {
    val ratio = image.width.toFloat() / image.height.toFloat()
    // 高度自适应，宽度由列宽决定，aspectRatio 保持原始比例
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(image.uri)
            .size(400)
            .crossfade(true)
            .build(),
        contentDescription = image.displayName,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio),   // 保持原始宽高比
        contentScale = ContentScale.Fit
    )
}
```

**关键点**:
- `LazyVerticalStaggeredGrid` 是 Compose 1.6+ 内置支持，无需额外库
- 每个 item 通过 `aspectRatio(width/height)` 保持图片原始比例
- 列与列之间独立滚动高度，形成自然瀑布流

#### 均匀网格 Justified Grid

均匀网格（justified / uniform grid）要求每行所有图片等高，宽度按原始宽高比分配，尽量填满整行。达到类似 Google Photos 的「均匀网格」效果。

由于 Compose 标准库没有直接支持，可基于 `SubcomposeLayout` 自定义实现：

```kotlin
@Composable
fun JustifiedGrid(
    images: List<ImageItem>,
    rowHeight: Dp = 120.dp,
    spacing: Dp = 4.dp,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    // 1. 预先计算每张图片的宽高比
    val aspectRatios = remember(images) {
        images.map { it.width.toFloat() / it.height.toFloat() }
    }

    // 2. 将图片分成多行，使每行宽度之和最接近屏幕宽度
    val rows = remember(images, rowHeight, spacing) {
        val screenWidthDp = ... // 从 LocalConfiguration 获取
        val spacingPx = with(density) { spacing.toPx() }
        val rowHeightPx = with(density) { rowHeight.toPx() }
        layoutIntoRows(aspectRatios, screenWidthDp, rowHeightPx, spacingPx)
    }

    // 3. 渲染 (使用 LazyColumn 逐行渲染，或 SubcomposeLayout)
    LazyColumn(modifier = modifier) {
        items(rows) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                row.items.forEach { item ->
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(images[item.index].uri)
                            .size(400)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(item.weight),
                        contentScale = ContentScale.Crop // 裁剪适应行高
                    )
                }
            }
        }
    }
}

/**
 * 将图片按宽高比分组，使每行总宽度尽可能接近屏幕宽度
 * 算法: 贪心逐行累加，超宽时结束该行，最后一行拉伸填满
 */
fun layoutIntoRows(
    ratios: List<Float>,
    screenWidthDp: Float,
    rowHeightDp: Float,
    spacingDp: Float
): List<List<JustifiedItem>> {
    val rows = mutableListOf<MutableList<JustifiedItem>>()
    var currentRow = mutableListOf<Pair<Int, Float>>() // (index, ratio)
    var currentSum = 0f

    for ((index, ratio) in ratios.withIndex()) {
        val itemWidth = ratio
        if (currentSum + itemWidth > screenWidthDp && currentRow.isNotEmpty()) {
            // 结束当前行
            rows.add(currentRow.mapIndexed { i, (idx, r) ->
                JustifiedItem(index = idx, weight = r)
            }.toMutableList())
            currentRow = mutableListOf()
            currentSum = 0f
        }
        currentRow.add(index to ratio)
        currentSum += ratio
    }
    // 最后一行
    if (currentRow.isNotEmpty()) {
        rows.add(currentRow.map { (idx, r) ->
            JustifiedItem(index = idx, weight = r)
        }.toMutableList())
    }
    return rows
}

data class JustifiedItem(
    val index: Int,
    val weight: Float  // Compose Row 中 Modifier.weight() 的系数
)
```

**关键点**:
- 均匀网格的实现核心是「行布局算法」：贪心算法或最优填充算法
- 建议将 `JustifiedGrid.kt` 封装为独立可复用的 Composable
- 缩略图使用 `ContentScale.Crop` 保证填满格子（行高固定时不可避免边缘裁剪，但视觉上比留白更整洁）
- 或者使用 `ContentScale.Fit` + 背景色填充（类似 §3.6 的 rescale 逻辑），在行内居中展示原图


### 3.4 AI 评分功能

**参考文件**: `photo_rating.py` 中的 DashScope 多模态评图逻辑 & `Javaexample.txt` 中的 Java SDK 调用方式。

**实现逻辑**:

1. 用户在 **AI 评分页面** 输入 **API Key** 和 **Model Name**（如 `qwen-vl-plus`）
2. App 将 API Key 通过 `EncryptedSharedPreferences` 本地加密存储
3. 用户选择要评分的图片（支持全选 / 按文件夹 / 按标签）
4. 对选中的图片先 **缩小尺寸**（参考 `photo_rating.py` 中 resize 到 800px 逻辑）
   - 调用 `ImageResizer.resizeForAi(imageUri, maxDimension = 800)`
   - 缩小后的图片保存到应用缓存目录，评完后清理
5. 将缩小后的图片与 prompt 组装为 `MultiModalConversation` 请求，分批发送（每批最多 10 张）
   - 使用 Retrofit + OkHttp 向 `https://dashscope-intl.aliyuncs.com/api/v1` 发起请求
   - Header: `Authorization: Bearer {apiKey}`
   - Body: 参考 `Javaexample.txt` 和 `photo_rating.py` 的 message 结构
6. 解析返回的 JSON，提取 `score` (1-100) 和 `reason`
7. 将 AI 评分存入 `ImageEntity.aiScore`，同时可选同步到 `rating` 字段供用户参考
8. 展示评分结果列表，支持 **用户手动修改每个评分** vertically
9. 支持 **按 AI 评分排序**（降序 / 升序）

**API 请求核心代码结构**:

```kotlin
// AiRatingService.kt
suspend fun rateImages(
    apiKey: String,
    modelName: String,
    resizedImagePaths: List<String>
): List<AiRatingResult> {
    val batches = resizedImagePaths.chunked(10)
    val allResults = mutableListOf<AiRatingResult>()
    
    val prompt = "你是一位資深攝影編輯和社交媒體專家。..." // 同 photo_rating.py
    
    for (batch in batches) {
        val content = mutableListOf<Map<String, Any>>()
        batch.forEach { path ->
            val base64 = encodeImageToBase64(path)
            content.add(mapOf(
                "image" to "data:image/jpeg;base64,$base64"
            ))
        }
        content.add(mapOf("text" to prompt))
        
        val request = mapOf(
            "model" to modelName,
            "messages" to listOf(mapOf(
                "role" to "user",
                "content" to content
            ))
        )
        
        val response = httpClient.post("services/aigc/multimodal-generation/generation") {
            header("Authorization", "Bearer $apiKey")
            jsonBody = Json.encodeToJsonElement(request).jsonObject
        }
        // 解析 response...
    }
    return allResults
}
```

**⚠️ 注意**:
- **只有缩减尺寸后的图片（800px 以内）才发送给 AI API**，不得发送原始大图（参考 `photo_rating.py` 中 Step 1 的 resize 逻辑）
- API Key 不得硬编码在代码中，必须由用户输入并加密存储
- 网络请求异步执行，显示进度条

### 3.5 用户可修改评分 & 排序

- 在图片详情页 / 评分模式列表页，点击评分数字或 StarBar 可直接修改
- 支持按 **用户评分** 和 **AI 评分** 两个维度排序
- 排序模式可切换：`sortByRating(asc/desc)`, `sortByAiScore(asc/desc)`
- 保存修改立即写入 Room 数据库

### 3.6 批量操作

| 操作 | 实现 |
|------|------|
| **批量 resize 50%** | 使用 `BitmapFactory.Options.inSampleSize` 解码原图 → 缩小到 50% → 覆盖或另存 |
| **批量 rescale 到方形** | 计算长边/短边 → 在短边侧填充黑白底色 → `Canvas.drawBitmap` + `Paint` 填充背景 |
| **批量添加标签** | 多选 → 选标签 → `ImageDao.insertTagCrossRef(uris, tagId)` |
| **批量评分** | 多选 → 输入分数 → `ImageDao.updateRating(uris, rating)` |

**Rescale 方形实现**:

```kotlin
fun rescaleToSquare(
    sourceUri: Uri,
    outputUri: Uri,
    bgColor: Int = Color.BLACK  // or Color.WHITE
) {
    val srcBitmap = MediaStore.getBitmap(contentResolver, sourceUri)
    val size = maxOf(srcBitmap.width, srcBitmap.height)
    val dstBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(dstBitmap)
    canvas.drawColor(bgColor)
    val left = (size - srcBitmap.width) / 2f
    val top = (size - srcBitmap.height) / 2f
    canvas.drawBitmap(srcBitmap, left, top, null)
    dstBitmap.compress(Bitmap.CompressFormat.JPEG, 90, 
        contentResolver.openOutputStream(outputUri))
}
```

### 3.7 GitHub Actions CI/CD

```yaml
# .github/workflows/android-ci.yml
name: Android CI

on:
  push:
    branches: [ "main" ]
  pull_request:
    branches: [ "main" ]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v3

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Check code style & lint
        run: ./gradlew lintDebug

      - name: Run unit tests
        run: ./gradlew testDebugUnitTest

      - name: Build debug APK
        run: ./gradlew assembleDebug

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/app-debug.apk
```

**配套 `.gitignore`** 核心条目:
```
*.iml
.gradle
/local.properties
/.idea
.DS_Store
/build
/captures
.externalNativeBuild
.cxx
local.properties
```

---

## 4. GitHub 推送指南（单人开发）

```bash
# 1. 在 GitHub 新建仓库 (不要勾选 README/LICENSE/.gitignore)
# 2. 本地
cd /path/to/PyqCR
git init
git add .
git commit -m "feat: initial Android album app with AI rating"

# 3. 添加 remote 并推送到 main
git remote add origin git@github.com:你的用户名/PyqCR.git
git branch -M main
git push -u origin main

# 4. 推送后 GitHub Actions 自动触发编译构建
# 5. 在 Actions 页面下载 APK
```

---

## 5. 关键依赖 (Gradle)

```toml
[versions]
kotlin = "2.0.21"
compose-bom = "2024.10.01"
room = "2.6.1"
coil = "3.0.4"
retrofit = "2.11.0"
okhttp = "4.12.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version = "1.13.1" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version = "2.8.6" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version = "1.9.2" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-navigation = { group = "androidx.navigation", name = "navigation-compose", version = "2.8.2" }
coil-compose = { group = "io.coil-kt.coil3", name = "coil-compose", version.ref = "coil" }
coil-network-okhttp = { group = "io.coil-kt.coil3", name = "coil-network-okhttp", version.ref = "coil" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-converter-kotlinx-json = { group = "com.squareup.retrofit2", name = "converter-kotlinx-serialization-json", version.ref = "retrofit" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
security-crypto = { group = "androidx.security", name = "security-crypto", version = "1.1.0-alpha06" }
```

---

## 6. 权限

在 `AndroidManifest.xml` 中声明：

```xml
<!-- Android 13+ 细粒度媒体权限 -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<!-- Android 12- -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<!-- 保存修改后的图片 -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="29" />
<!-- 网络（AI API 调用） -->
<uses-permission android:name="android.permission.INTERNET" />
```

---

## 7. 分支策略（单人开发）

```
main ─── 生产/稳定版本，每次提交触发 GitHub Actions
  │
  └── feature/* ─── 功能开发分支（可选）
       ├── feature/ai-rating
       ├── feature/batch-edit
       └── feature/tag-system
```

作为单人开发者，可以直接在 `main` 上开发（每次 push 触发 CI）；需要实验性功能时开 `feature/*` 分支。

---

## 8. 实现顺序建议（MVP → 迭代）

| 阶段 | 功能 |
|------|------|
| **MVP (Sprint 1)** | 项目搭建、MediaStore 读取图片、文件夹网格浏览、缩略图 fit 正方形展示 |
| **Sprint 2** | Room 数据库、标签系统（增删改查）、单张图片详情页 |
| **Sprint 3** | 评分系统（用户评分 + 排序）、批量操作（标签/评分） |
| **Sprint 4** | AI 评分功能（API Key 输入、图片缩小、DashScope 调用、结果展示） |
| **Sprint 5** | 瀑布流网格视图 + 均匀网格视图（含行布局算法） |
| **Sprint 6** | 批量 resize 50%、批量 rescale 方形填充 |
| **Sprint 7** | 更多功能打磨 + 大量测试 |

---

## 9. 测试注意事项

- **UI 测试**: Compose UI Test 验证缩略图显示、导航、批量选择
- **网格视图测试**: 验证标准网格 / 瀑布流 / 均匀网格三种视图切换正确、所有图片正常渲染、滚动流畅无空位
- **瀑布流测试**: 验证 `LazyVerticalStaggeredGrid` 在不同列数 (2/3) 下布局正常、每项宽高比保持正确
- **均匀网格测试**: 验证行布局算法正确填满每行宽度、无图片被遗漏、行末拉伸逻辑合理
- **Room 测试**: `@RunWith(AndroidJUnit4::class)` 测试 DAO 增删改查
- **网络测试**: MockWebServer 模拟 DashScope API 返回
- **CI 保证**: 每步都跑 `lintDebug` + `testDebugUnitTest` + `assembleDebug`
- **CI 失败处理**: 如果 `lintDebug` 或 `testDebugUnitTest` 失败, `assembleDebug` 不会执行, 在 GitHub Actions 页面查看具体报错日志; 修复后重新推送即可

---

## 10. 参考文件

| 文件 | 用途 |
|------|------|
| `photo_rating.py` | AI 评分逻辑参考（prompt、分批、结果解析） |
| `Javaexample.txt` | DashScope Java SDK 调用方式参考（message 组装格式） |

---
*CR 版本: v1.1 | 最后更新: 2026-09-23*

*变更: v1.1 — 新增瀑布流网格视图、均匀网格视图（包括行布局算法实现代码）以及对应的组件/测试/实现顺序更新*