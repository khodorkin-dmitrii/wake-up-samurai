---
name: android-launcher-icons
description: Create or replace Android launcher icons from an SVG source, including adaptive, monochrome, round, and legacy density resources. Use when icon sharpness, safe-zone sizing, raster generation, or removal of old launcher assets matters; do not use for general-purpose image editing.
---

# Android launcher icons from SVG

Create Android launcher icons from one vector source without accidentally scaling a low-resolution raster into higher-density resources.

## Lessons from the failure

The visibly blurred `xxhdpi` and `xxxhdpi` foreground was caused by the rasterization order, not by PNG itself:

```text
SVG -> implicit low-resolution raster -> resize upward -> blurry high-density PNG
```

With ImageMagick, options that control SVG rasterization must appear **before** the input file. This is the important difference:

```powershell
# Wrong: SVG may already be rasterized at its default density before resize.
magick source.svg -resize 144x144 output.png

# Correct: rasterize the SVG at high density first, then downsample.
magick -background none -density 1200 source.svg `
    -filter Lanczos -resize 144x144 output.png
```

Other issues encountered during the replacement:

- Adding `ic_launcher.png` while retaining `ic_launcher.webp` with the same resource name creates duplicate Android resources. Replace or remove the old files in every density folder.
- Converting the composited icon to WebP produced unreliable alpha edges in the available ImageMagick setup. Lossless PNG is valid for legacy launcher resources and was more predictable here.
- Recoloring only filled SVG paths misses strokes. A monochrome foreground must make fills **and** strokes white while preserving transparency.
- Intermediate PNGs were staged before the final rerender. Always run `git add` again after the last generation pass and review the cached diff.
- A visually correct foreground can still be clipped by adaptive-icon masks. Keep meaningful artwork inside the adaptive safe zone.

## Inspect before editing

1. Read repository instructions and preserve existing naming conventions.
2. Find the icon references in `AndroidManifest.xml` and inspect all existing resources:

   ```powershell
   rg -n "android:icon|android:roundIcon|ic_launcher" app/src/main
   Get-ChildItem app/src/main/res -Recurse -File |
       Where-Object Name -Like 'ic_launcher*'
   ```

3. Inspect the SVG `viewBox`, visible bounds, fills, strokes, and transparency. Do not assume the visible artwork fills the entire viewBox.
4. Record the existing background color and decide whether the foreground must also support themed monochrome icons.

## Resource model

Use one SVG-derived design for all variants, but keep the Android resource roles distinct.

### Android 8.0 and newer: adaptive icon

Provide an adaptive resource in both files:

```text
res/mipmap-anydpi-v26/ic_launcher.xml
res/mipmap-anydpi-v26/ic_launcher_round.xml
```

Recommended structure:

```xml
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

Use a `VectorDrawable` for the foreground. Adaptive layers use a nominal `108dp x 108dp` canvas. Keep critical artwork in the central safe area; the launcher applies the final circle, squircle, or OEM mask.

Do not bake the background or launcher mask into the adaptive foreground.

### Older Android versions: legacy bitmaps

Generate independent PNG resources for each density:

| Density | Launcher size |
|---|---:|
| `mdpi` | 48 x 48 px |
| `hdpi` | 72 x 72 px |
| `xhdpi` | 96 x 96 px |
| `xxhdpi` | 144 x 144 px |
| `xxxhdpi` | 192 x 192 px |

Create both `ic_launcher.png` and `ic_launcher_round.png` when the manifest declares a round icon. Each output must be derived from the vector or a large vector-rendered master, never from the mdpi file.

## Reliable ImageMagick pipeline

Use a temporary directory under `app/build` so generation artifacts remain outside version control. The following PowerShell pattern renders each foreground from the SVG at high source density before reducing it to the required pixel dimensions:

```powershell
$sourceSvg = 'screenshots/library-headphones-v3.svg'
$resRoot = 'app/src/main/res'
$tempRoot = 'app/build/icon-generation'
$background = '#424242'

$variants = @(
    @{ Density = 'mdpi'; Canvas = 48; Glyph = 36 },
    @{ Density = 'hdpi'; Canvas = 72; Glyph = 54 },
    @{ Density = 'xhdpi'; Canvas = 96; Glyph = 72 },
    @{ Density = 'xxhdpi'; Canvas = 144; Glyph = 108 },
    @{ Density = 'xxxhdpi'; Canvas = 192; Glyph = 144 }
)

New-Item -ItemType Directory -Force $tempRoot | Out-Null

foreach ($variant in $variants) {
    $density = $variant.Density
    $canvas = $variant.Canvas
    $glyph = $variant.Glyph
    $radius = [Math]::Round($canvas * 0.22)
    $edge = $canvas - 1
    $center = $edge / 2
    $foreground = Join-Path $tempRoot "foreground-$density.png"
    $square = Join-Path $tempRoot "background-square-$density.png"
    $round = Join-Path $tempRoot "background-round-$density.png"
    $output = Join-Path $resRoot "mipmap-$density"

    magick -background none -density 1200 $sourceSvg `
        -filter Lanczos -resize "${glyph}x${glyph}" `
        -alpha on -channel RGB -fill white -colorize 100 `
        $foreground

    if ($LASTEXITCODE -ne 0) { throw "SVG render failed for $density" }

    magick -size "${canvas}x${canvas}" xc:none `
        -fill $background `
        -draw "roundrectangle 0,0,$edge,$edge,$radius,$radius" `
        $square

    magick -size "${canvas}x${canvas}" xc:none `
        -fill $background `
        -draw "circle $center,$center $center,0" `
        $round

    magick $square $foreground -gravity center -composite `
        (Join-Path $output 'ic_launcher.png')

    magick $round $foreground -gravity center -composite `
        (Join-Path $output 'ic_launcher_round.png')
}
```

The `Glyph` values above use 75% of the legacy canvas and fit this particular artwork. Treat that ratio as a starting point, not a universal rule. Adjust it from the SVG's **visible bounds** and compare all masks visually.

An equally valid pipeline is to render one very large transparent master from the SVG and downsample that master separately for every density. Never upscale a smaller generated asset.

## VectorDrawable conversion

Translate SVG geometry into `res/drawable/ic_launcher_foreground.xml`:

- Map filled paths to `android:pathData` plus `android:fillColor="#FFFFFFFF"`.
- Map SVG strokes to `android:strokeColor="#FFFFFFFF"`, preserving width, line cap, and joins.
- Convert circles or unsupported SVG primitives to path data when required.
- Use groups for source transforms instead of manually changing every coordinate.
- Use a transparent fill for stroke-only paths.
- Choose a viewport and translation that center the **visible artwork**, not merely the SVG viewBox.

Avoid raster foregrounds for adaptive icons when the source is vector and the design can be represented by `VectorDrawable`.

## Verification checklist

### 1. Dimensions

```powershell
Get-ChildItem app/src/main/res -Recurse -Filter 'ic_launcher*.png' |
    Sort-Object FullName |
    ForEach-Object {
        $size = magick identify -format '%wx%h' $_.FullName
        "{0} {1}" -f $_.FullName, $size
    }
```

Confirm the exact 48/72/96/144/192 matrix for both normal and round icons.

### 2. Visual sharpness

Create a contact sheet from the actual output files and inspect it at original size. Pay special attention to:

- curved strokes around the headphones;
- small gaps between adjacent shapes;
- symmetry and optical centering;
- transparent corners;
- clipping under square and circular masks.

Do not accept a high-density file merely because its pixel dimensions are correct. A 192 x 192 file can still contain an upscaled 48 x 48 foreground.

### 3. Alpha

Check corner pixels in both shapes:

```powershell
magick identify -format '%[pixel:p{0,0}]' `
    app/src/main/res/mipmap-xxxhdpi/ic_launcher.png
magick identify -format '%[pixel:p{0,0}]' `
    app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png
```

The corner should be transparent when the chosen legacy shape does not cover it.

### 4. Resource cleanup

Verify that obsolete defaults and duplicate extensions are gone:

```powershell
Get-ChildItem app/src/main/res -Recurse -File |
    Where-Object Name -Like 'ic_launcher*'
rg -n "#3DDC84|@drawable/ic_launcher_background" app/src/main/res
```

The exact search terms depend on the old assets. The goal is one unambiguous resource per name and qualifier.

### 5. Android build

Run resource packaging and lint:

```powershell
.\gradlew.bat assembleDebug lintDebug
```

Device installation is a separate action. Do it only when requested and follow the repository's device-installation rules.

### 6. Git state

```powershell
git diff --check
git status --short
git diff --stat
git diff --cached --stat
```

If any generated file was previously staged, run `git add` again only after the final rerender. Confirm the cached binary sizes changed in the expected direction and that temporary contact sheets are not tracked.

## Completion criteria

The task is complete when:

- adaptive icons reference separate background, vector foreground, and monochrome layers;
- legacy normal and round PNGs exist at every required density;
- every density was rendered from vector-quality input;
- high-density details are visibly sharper than enlarged mdpi output;
- old same-name resources are removed;
- dimensions and alpha are correct;
- `assembleDebug` and `lintDebug` succeed;
- only intended source and resource files remain in the Git diff.
