# Center Finder
 
**Center Finder** is a native Android computer-vision app. Point the camera at a part, a hole, a coin pile, or a pencil bundle and it will either **outline the object and mark its geometric center**, or **count how many discrete items sit inside a box you draw**.
 
It is built in Kotlin with Jetpack Compose, CameraX, and OpenCV. Processing runs on-device. Nothing is uploaded.
 
| | |
| --- | --- |
| Package | `com.myworkshopy.centerfinder` |
| Min SDK | 24 (Android 7.0) |
| Target / compile SDK | 36 / 37 |
| UI | Jetpack Compose + Material 3 |
| Camera | CameraX 1.4.1 |
| Vision | OpenCV 5 (`org.opencv:opencv:5.0.0.1`) |
 
---
 
## Table of contents
 
- [What it does](#what-it-does)
- [Screens](#screens)
- [How Center Finder works](#how-center-finder-works)
- [How Object Counter works](#how-object-counter-works)
- [Architecture](#architecture)
- [Project layout](#project-layout)
- [Coordinate mapping](#coordinate-mapping)
- [Tech stack](#tech-stack)
- [Getting started](#getting-started)
- [Permissions](#permissions)
- [OpenCV notes](#opencv-notes)
- [Tests](#tests)
- [Known limitations](#known-limitations)
- [Roadmap ideas](#roadmap-ideas)
 
---
 
## What it does
 
The home screen is a small **Vision Tools** dashboard. Pick a tool:
 
### 1. Center Finder
 
Live camera overlay that:
 
1. Finds the **largest object** in the frame (contour area must be at least 1% of the frame).
2. Traces its outline.
3. Classifies a rough shape: triangle, square, rectangle, pentagon, hexagon, octagon, circle, or generic object.
4. Marks the **centroid** with a red crosshair.
 
Typical use: lining up a drill on a hole, finding the middle of a plate, or checking whether a part is roughly circular vs rectangular.
 
### 2. Object Counter
 
Live camera with a **drag-to-select** box:
 
1. Drag a rectangle over a cluster of similar items (pencils, coins, drilled holes, screws, bolts).
2. Tap **Count items**.
3. The app thresholds the region, splits touching objects with a **distance transform**, and reports the count.
4. Each detected center is drawn as a red dot.
 
Plain contour counting would merge touching items into one blob and report `1`. The distance-transform peak method is what lets a bundle of pencils or a pile of coins count as many.
 
---
 
## Screens
 
| Route | Screen | Status |
| --- | --- | --- |
| `dashboard` | Vision Tools home | Implemented |
| `centerFinder` | Live shape + centroid overlay | Implemented |
| `objectCount` | Live region counter | Implemented |
| `profile` | Declared in `Screen` | Not wired in `NavHost` yet |
 
Startup flow:
 
```
App launch
  ├─ OpenCVLoader.initLocal()
  │     └─ fail → “OpenCV failed to initialize”
  ├─ CAMERA permission
  │     └─ deny → “Camera permission is required”
  └─ AppNavigation → Dashboard
```
 
---
 
## How Center Finder works
 
Pipeline lives in `ShapeAnalyzer` (`app/src/main/java/com/myworkshopy/centerfinder/model/ShapeAnalyzer.kt`) and is drawn by `CameraFinderScreen`.
 
```
CameraX ImageAnalysis (YUV_420_888, latest-frame only)
        │        
        ▼
  Y plane only → grayscale Mat  (CV_8UC1)
        │
        ▼
  Rotate to display orientation
  (0 / 90 / 180 / 270 from ImageProxy.imageInfo)
        │
        ▼
  Gaussian blur 5×5
        │
        ▼
  Canny edges (50 / 150) + dilate
        │
        ▼
  findContours (RETR_EXTERNAL, CHAIN_APPROX_SIMPLE)
        │
        ▼
  Keep largest contour with area > 1% of frame
        │
        ├─ Geometry.moments  → centroid
        └─ approxPolyDP (2% of perimeter) → vertex count
                │
                ▼
        Classify shape → DetectionResult
                │
                ▼
        Compose Canvas overlay (FIT_CENTER letterbox)
          • green outline
          • red centroid crosshair
          • shape name at the top
```
 
### Shape classification (live analyzer)
 
| Vertices after `approxPolyDP` | Label |
| --- | --- |
| 3 | Triangle |
| 4, aspect ≈ 1 (±8%) | Square |
| 4, otherwise | Rectangle |
| 5 | Pentagon |
| 6 | Hexagon |
| 7 or 8 | Octagon |
| else, circularity > 0.75 | Circle |
| else | Object |
 
Circularity is \(4\pi A / P^2\). A perfect circle scores 1.0; a square scores about 0.785.
 
### Why only the Y plane?
 
`ImageAnalysis` defaults to `YUV_420_888`. The Y plane is already a full-resolution grayscale image. Edge detection and contours do not need chroma, so this path is cheaper than converting the whole frame to RGBA.
 
### Overlay mapping
 
`PreviewView` uses `ScaleType.FIT_CENTER`. Detection coordinates are **normalized 0..1** in the analysis frame. The overlay letterboxes the same way: it computes the fitted rectangle inside the view, then maps `(nx, ny) → (offsetX + nx * scaledW, offsetY + ny * scaledH)`. If preview and analysis used different scale types, the outline would drift off the object.
 
---
 
## How Object Counter works
 
Pipeline lives in `ObjectCounterScreen.kt`: camera capture + `ClusterCounter`.
 
```
CameraX Preview (FILL_CENTER)
CameraX ImageAnalysis
  • RGBA_8888
  • 1280×720 (closest higher-then-lower fallback)
  • STRATEGY_KEEP_ONLY_LATEST
        │
        ▼
  imageProxyToMat (handles row-stride padding)
        │
        ▼
  Rotate to display orientation
        │
        ▼
  FrameHolder  (latest Mat, cloned on demand, released on dispose)
        │
        ▼
  User drags a box on the Compose overlay
        │
        ▼
  Screen rect → image rect  (inverse FILL_CENTER)
        │
        ▼
  ClusterCounter.count(mat, roi)
        │
        ▼
  Count label + green box + red center dots
```
 
### ClusterCounter algorithm
 
Simple `findContours` fails when objects touch: they become one blob. This path treats each object as a **hill** in a distance map.
 
1. **Crop** the ROI and convert RGBA → gray.
2. **Blur** with a 5×5 Gaussian.
3. **Auto polarity.** Sample the four corners. If corner mean is brighter than the overall mean, the background is bright and objects are dark (holes in a plate, pencils on a light table). Otherwise invert that assumption.
4. **Otsu threshold** (`THRESH_BINARY` or `THRESH_BINARY_INV`).
5. **Morphological open** (ellipse 3×3, 2 iterations) to drop speckle.
6. **Distance transform** (`Geometry.DIST_L2`, mask 5). Each foreground pixel becomes the distance to the nearest background pixel. Object interiors peak; contact points between touching objects stay low.
7. **Peak threshold** at `0.45 × maxVal`. That isolates one small blob per object.
8. **`connectedComponentsWithStats`**. Drop blobs smaller than 10 px. Remaining labels = count. Centroids of those labels are the red markers.
 
Tunable knobs on `ClusterCounter.count`:
 
| Parameter | Default | Effect |
| --- | --- | --- |
| `objectsDarkerThanBackground` | auto | Force polarity instead of corner sampling |
| `peakThresholdRatio` | 0.45 | Lower → more sensitive, more over-split; 0.35–0.55 is the useful range |
| `minComponentArea` | 10 px | Drops noise peaks |
 
### Screen ↔ image mapping (FILL_CENTER)
 
Preview uses `FILL_CENTER` (crop to fill). Mapping is the inverse of that crop:
 
```
scale = max(viewW / imgW, viewH / imgH)
dx    = (viewW - imgW * scale) / 2
dy    = (viewH - imgH * scale) / 2
 
image.x = (screen.x - dx) / scale
screen.x = image.x * scale + dx
```
 
Centers from `ClusterCounter` are ROI-local. They are shifted by the ROI origin, then mapped back to screen pixels so the red dots sit on the live preview.
 
---
 
## Architecture
 
```
MainActivity
  OpenCV init + CAMERA permission + CenterFinderTheme
        │
        ▼
  AppNavigation  (Navigation Compose)
        │
        ├── DashboardScreen
        │     ├── Center Finder card  → CameraFinderScreen
        │     └── Object Counter card → ObjectCounterScreen
        │
        ├── CameraFinderScreen
        │     PreviewView + ShapeAnalyzer → DetectionResult overlay
        │
        └── ObjectCounterScreen
              PreviewView + FrameHolder + ClusterCounter
```
 
### Design choices
 
- **Compose UI, CameraX for capture, OpenCV for pixels.** Compose does not process frames. CameraX owns the camera lifecycle. OpenCV owns Mats, contours, and morphology.
- **Keep analysis off the UI thread.** Center Finder analyzes on the CameraX executor. Object Counter captures on a single-thread executor and runs `ClusterCounter` on `Dispatchers.Default`.
- **Do not put Mats in Compose state.** `FrameHolder` holds the latest frame behind a lock. Compose only stores sizes, drag offsets, counts, and screen-space points.
- **Release native memory.** Every `Mat` is released after use. `DisposableEffect` shuts down the camera executor and the frame holder when the Object Counter leaves composition.
 
### Supporting math (not on the live path yet)
 
`ShapeMath` and `DetectionSmoother` are unit-tested helpers for a stricter classifier and temporal smoothing:
 
- circularity, aspect, quad/hexagon quality scores
- vertex matching so outlines do not rotate between frames
- an aim score that prefers objects near the viewfinder center
- a sliding-window vote so shape names do not flicker
 
The live `ShapeAnalyzer` still uses the simpler vertex-count classifier. These types are ready to plug in without pulling OpenCV into unit tests.
 
---
 
## Project layout
 
```
Center_Finder/
├── app/                                Android application
│   └── src/main/java/com/myworkshopy/centerfinder/
│       ├── MainActivity.kt             OpenCV + permission + dashboard UI
│       ├── Navigation.kt               NavHost routes
│       ├── ObjectCounterScreen.kt      Live counter + ClusterCounter
│       ├── ShapeMath.kt                Pure geometry helpers
│       ├── DetectionSmoother.kt        Temporal label vote
│       ├── cemera_model/
│       │   └── CameraFinderScreen.kt   Live overlay UI
│       ├── model/
│       │   ├── Screen.kt               Route constants
│       │   ├── DetectionResult.kt      Normalized contour + centroid
│       │   └── ShapeAnalyzer.kt        Per-frame OpenCV analyzer
│       └── ui/theme/                   Material 3 theme
├── opencv/                             Local OpenCV 4.12 Android SDK (module)
├── gradle/libs.versions.toml
└── README.md
```
 
`settings.gradle.kts` includes both `:app` and `:opencv`. The app module currently depends on the Maven artifact `org.opencv:opencv:5.0.0.1`, not `project(":opencv")`. See [OpenCV notes](#opencv-notes).
 
---
 
## Coordinate mapping
 
Two screens, two preview scale types, two conventions. Mixing them is the usual source of “the box is in the wrong place.”
 
| Tool | Preview scale | Analysis coords | Overlay |
| --- | --- | --- | --- |
| Center Finder | `FIT_CENTER` (letterbox) | Normalized 0..1 after rotation | Letterbox in the Canvas |
| Object Counter | `FILL_CENTER` (crop) | Pixel coords on the rotated Mat | Inverse crop of the drag box |
 
`DetectionResult` always stores points in **display orientation** (sensor rotation already applied).
 
---
 
## Tech stack
 
| Layer | Library / version |
| --- | --- |
| Language | Kotlin 2.2.10 |
| Android Gradle Plugin | 9.2.1 |
| Gradle | 9.4.1 |
| UI | Compose BOM `2026.02.01`, Material 3, dynamic color on Android 12+ |
| Navigation | `androidx.navigation:navigation-compose:2.9.3` |
| Camera | CameraX core / camera2 / lifecycle / view `1.4.1` |
| Vision | OpenCV 5.0.0.1 (Maven) |
| Lifecycle | `lifecycle-runtime-ktx`, `lifecycle-viewmodel-compose` |
| JVM | Java 11 |
 
OpenCV 5 moved some APIs that used to live on `Imgproc`:
 
- computational geometry (`contourArea`, `moments`, `arcLength`, `approxPolyDP`, `boundingRect`) → `org.opencv.geometry.Geometry`
- `DIST_L2` and related distance types → `Geometry.DIST_L2`
 
---
 
## Getting started
 
### Requirements
 
- Android Studio (current Narwhal / 2025.x line is what this repo is opened with)
- JDK 11+
- An Android device or emulator with a camera (a physical device is strongly preferred)
- Git LFS is **not** required; OpenCV natives come from the Maven AAR
 
### Clone and run
 
```bash
git clone https://github.com/Markhande/Center_Finder.git
cd Center_Finder
```
 
Open the folder in Android Studio, let Gradle sync, then Run on a device.
 
From the command line:
 
```bash
./gradlew :app:assembleDebug
```
 
The debug APK is written under `app/build/outputs/apk/debug/`.
 
### First launch
 
1. Grant **Camera**.
2. Open **Center Finder** and aim at a high-contrast object on a plain background.
3. Open **Object Counter**, drag a box around a cluster, tap **Count items**.
 
Lighting and contrast matter more than resolution. A dark hole in a light plate, or dark pencils on a light desk, is the intended setup.
 
---
 
## Permissions
 
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="true" />
```
 
The app requests the permission at startup. There is no gallery, storage, or network permission. Frames never leave the device.
 
---
 
## OpenCV notes
 
On startup:
 
```kotlin
val openCvReady = OpenCVLoader.initLocal()
```
 
`initLocal()` loads the native library shipped inside the AAR. If that fails, the UI stops at “OpenCV failed to initialize” instead of crashing on the first `Mat`.
 
**Two OpenCV copies exist in this repo:**
 
1. **Used at compile/runtime:** `implementation("org.opencv:opencv:5.0.0.1")` in `app/build.gradle.kts`.
2. **Vendored module:** `:opencv` is the official OpenCV 4.12 Android SDK (`libopencv_java4.so`, Haar/LBP cascades under `opencv/etc/`). It is included in `settings.gradle.kts` but not referenced from the app’s `dependencies { }` block.
 
If you switch the app to `implementation(project(":opencv"))`, you are on 4.x APIs (`Imgproc.DIST_L2`, geometry still on `Imgproc`). The current code is written against 5.x (`Geometry.DIST_L2`, `Geometry.moments`, …).
 
Native Mats are not garbage-collected the way Kotlin objects are. Call `release()` (the code already does this on every analysis path and in `FrameHolder`).
 
---
 
## Tests
 
```bash
./gradlew :app:testDebugUnitTest
```
 
`ShapeMathTest` covers:
 
- square vs circle circularity (a square must not be labeled a circle)
- tight square aspect
- quad / hexagon quality scores
- vertex matching so a rotated point list snaps back to the previous order
- aim score peaking at the viewfinder center
- `DetectionSmoother` returning the live name
 
These tests do not need OpenCV or an emulator.
 
---
 
## Known limitations
 
- **One object for Center Finder.** Only the largest contour is kept. A cluttered bench will lock onto the wrong thing.
- **Vertex-count classifier is coarse.** Perspective, blur, and broken edges change `approxPolyDP` vertex count. Squares can look like circles; circles can look like octagons.
- **Object Counter assumes similar, roughly blob-like items.** Long thin objects that overlap heavily, or mixed sizes in one box, will under- or over-count.
- **Polarity is a corner heuristic.** If the selection box’s corners are not background (for example the box sits entirely on the object), auto polarity can flip and count holes instead of parts, or the reverse.
- **No torch / zoom / lens switch UI.** Back camera only, default zoom.
- **Profile screen** is a route constant only.
- **`ShapeMath` / `DetectionSmoother`** are not yet used by the live analyzer.
- **Preview and analysis can differ in resolution.** Mapping assumes they share aspect after rotation. Unusual crop sensors can still show a small offset.
 
---
 
## Roadmap ideas
 
- Wire `ShapeMath` scores + `DetectionSmoother` into `ShapeAnalyzer` so labels stop flickering.
- Multi-object Center Finder (all contours above a size floor, tap to pin one).
- Torch, zoom, and front/back camera toggle.
- Export count / centroid as a still with overlay (needs a save path and a storage or share sheet).
- Calibrated real-world units (mm) using a reference marker in frame.
- Profile / saved measurements.
 
---
 
## License
 
Not Required for now.
 
OpenCV is licensed under [Apache 2.0](https://opencv.org/license/). The vendored `opencv/` module includes third-party notices under `opencv/etc/licenses/`.

It covers:

• What the app is — on-device vision tools, not a cloud app
• Both tools — Center Finder (outline + centroid) and Object Counter (drag-box count)
• Full pipelines — CameraX → OpenCV steps, including why Object Counter uses a distance transform instead of findContours
• Architecture — Compose UI, CameraX capture, OpenCV pixels, FrameHolder so Mats never sit in Compose state
• Coordinate mapping — FIT_CENTER vs FILL_CENTER so overlays stay lined up
• Project layout — every important Kotlin file
• Stack — Kotlin 2.2, Compose, CameraX 1.4.1, OpenCV 5
• How to clone and run — plus camera permission
• OpenCV 5 vs the local 4.12 module — the app uses the Maven 5.x AAR; Geometry.DIST_L2 is called out
• Tests, limitations, and a short roadmap
