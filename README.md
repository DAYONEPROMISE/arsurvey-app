# ARTest2

An Android proof-of-concept that measures the real-world size of a single household
object with a phone — **width / height / depth** in centimeters. Aim at a box, suitcase,
chair, microwave, etc., placed on the floor; the app reconstructs the object in 3D from
ARCore depth + camera pose and estimates its dimensions entirely **on-device**.

> This is a **proof-of-concept, not a product** — the priority is validating the multi-view
> geometry pipeline over squeezing out measurement accuracy.

---

## How dimensions are computed

The current approach is a **capture-first, multi-view reconstruction** workflow. Rather than
treating every camera frame as an independent measurement, the user captures **3 photos of
one object from different viewpoints**, and all observations are fused into a single ARCore
world coordinate system before any dimension is estimated.

```
Capture (×3 viewpoints)
   │  per photo: full-res RGB, 16-bit depth (mm), camera pose (world), intrinsics
   ▼
Object segmentation (YOLO11-Seg)        → mask isolates the single target object
   ▼
Masked depth extraction                 → keep depth only where mask == 1
   ▼
Back-projection to 3D (pinhole inverse) → (X,Y,Z) per pixel in camera space
   ▼
Camera → world transform (ARCore pose)  → every point in one common world frame
   ▼
Multi-view accumulation                 → 3 viewpoints merged into one point cloud
   ▼
Outlier rejection (percentile bounds)   → drop floor/wall bleed-through + depth noise
   ▼
Gravity-aligned geometry                → world Y is vertical (ARCore is gravity-aligned)
   ├─ Height:    robust top − floor (Y percentiles, floor validated)
   └─ Footprint: project points to the X/Z plane
   ▼
Minimum-area oriented rectangle on X/Z  → the two side lengths = Width and Depth
   ▼
Temporal stabilization                  → W / H / D converge, then reported in cm
```

### The two key ideas

1. **Multi-view over single-view.** The central experiment is whether observations of the
   *same* object from 3+ camera viewpoints, transformed into a common ARCore world frame,
   reconstruct consistently and yield more accurate dimensions than a single frame. Capture
   is deliberately "dumb and fast" (image + ARCore metadata only, no processing); the heavy
   geometry runs afterwards on the persisted data.

2. **Gravity + footprint rectangle, not PCA.** Height uses the gravity-aligned world Y axis
   against a validated floor. The horizontal dimensions come from a **minimum-area oriented
   rectangle** fitted to the object's footprint on the X/Z plane — this stays independent of
   the phone's orientation and avoids the footprint inflation you get from an axis-aligned
   box. (PCA-based oriented boxes are kept only for debugging comparison, not as the
   authoritative measurement.)

Dimensions are never taken from raw min/max coordinates without outlier rejection, and no
measurement is computed server-side — the backend only stores the final W/H/D + confidence.

---

## User flow (capture-first)

```
CAPTURE ──(3 photos)──► REVIEW ──► RECONSTRUCT (predicted W×H×D)
   │                       │  └───► DIAGNOSTIC (depth→RGB reprojection test)
   │◄── Catalog ──────► CATALOG ──► DETAIL ──► RECONSTRUCT / DIAGNOSTIC
```

- **Capture** — live camera, a shutter, and an "N of 3" counter. A silent ARCore session
  runs but **no ML/measurement executes during capture**. The shutter is *tracking-gated*:
  it waits for ARCore `TRACKING` (and usable depth) so pose/depth are never read from a bad
  frame. After the 3rd photo it auto-advances to Review.
- **Review** — the 3 saved images with the full ARCore metadata persisted per frame (pose,
  intrinsics, transform matrices, depth availability) for on-device inspection.
- **Reconstruct** — builds the world-space point cloud from the 3 photos and reports the
  **predicted Width × Height × Depth in cm** with an oriented-box overlay.
- **Catalog / Detail** — every captured object persists in a Room database (survives app
  restarts) and can be reopened, reconstructed, or diagnosed later.

---

## Key technical decisions

- **Capture-first, measure-later** — decouples fast on-device capture (RGB + depth + pose)
  from the geometry pipeline, so measurement is reproducible from persisted data and does
  not run on every render frame.
- **ARCore Depth API** for real metric depth; DEPTH16 samples are decoded correctly as
  13-bit millimeters + 3-bit confidence (a masking bug previously inflated depth by ~8 m).
- **YOLO11-Seg (one model)** for detection *and* segmentation in a single pass, via **ONNX
  Runtime Mobile**.
- **SceneView + Jetpack Compose** for AR rendering; **MVVM**, single Gradle module, with
  package boundaries per pipeline stage (`ar/ capture/ reconstruct/ cloud/ measure/ …`).
- **Room** for the object/photo catalog; heavy binaries (JPEG + raw depth) stay as files on
  internal storage, indexed by the DB.
- **Debugging philosophy** — visual overlays as assertions: if the mask, point cloud, or box
  looks wrong on screen, the math is wrong. Always identify the failing pipeline stage
  (segmentation → depth → RGB alignment → intrinsics → pose → world transform →
  accumulation → filtering → floor → footprint) before changing the algorithm.

---

## Requirements

- **Target device:** OnePlus Nord 5 (model `CPH2805`, Android 16) — ARCore-certified and
  Depth-API-supported.
- A physical, ARCore Depth-capable device is required; the emulator will not work. The
  runtime truth check is `session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)`.
- Model weights (`*.onnx`) are **not** committed (gitignored) and must be placed in
  `app/src/main/assets/models/` separately.

## Building

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project docs

- `docs/CAPTURE_FIRST.md` — the capture-first workflow, persistence, and on-device results.
- `docs/measurement-pipeline.md` — the full dimension pipeline (segmentation → footprint).
- `docs/architecture.md`, `docs/scanning-ui.md`, `docs/development-process.md` — design.
- `PLAN.md` / `docs/PROGRESS.md` — roadmap and living status.
</content>
