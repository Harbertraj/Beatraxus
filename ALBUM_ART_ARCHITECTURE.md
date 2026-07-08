# Beatraxus Music App - Album Art Architecture

## Executive Summary

Beatraxus uses a multi-layered approach to handle album artwork:
- **Data Model**: Song objects carry optional `albumArtUri` fields
- **Image Loading**: Coil library with disk/memory caching
- **Extraction**: MediaMetadataRetriever for embedded art + FFmpeg fallback
- **Caching**: Local filesystem cache + Coil's automatic caching
- **UI Display**: Compose AsyncImage components with ic_album_placeholder.xml fallback
- **Widgets**: Custom blur effect applied to background album art

---

## 1. MUSIC DATA MODEL

**File:** `app/src/main/java/com/beatraxus/app/model/Song.kt`

### Song Data Class Fields (Relevant to Album Art):
```kotlin
data class Song(
    val id: String,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtUri: Uri? = null,  // ← ALBUM ART REFERENCE
    val albumArtist: String? = null,
    // ... 20+ other fields
)
```

### Data Sources:
1. **Album Art Field**: Nullable URI pointing to image data
   - Can be file:// URI (local file)
   - Can be content:// URI (MediaStore or app cache)
   - Can be http(s):// URI (remote image)

2. **Related Fields**:
   - `album`: Album name string
   - `artist`: Artist name string
   - `albumArtist`: May differ from track artist

---

## 2. ALBUM ART EXTRACTION & CACHING

**Primary File:** `app/src/main/java/com/beatraxus/app/repository/MusicRepository.kt`

### Extraction Flow (scanAudioFiles):

```
MediaStore Query (all audio files)
    ↓
For each song:
  1. Check if lossless format OR full scan requested
  2. MediaMetadataRetriever.embeddedPicture
  3. If WAV → extractEmbeddedArtFromWavFile()
  4. If still no art → FFmpeg extraction
  5. Cache to filesDir/embedded_album_art/[mediaStoreId].jpg
  6. Store cacheUri in Song.albumArtUri
```

### Extraction Methods:

#### 2.1 MediaMetadataRetriever (Primary)
```kotlin
val retriever = MediaMetadataRetriever()
retriever.setDataSource(context, uri)
val artBytes = retriever.embeddedPicture  // byte array or null
```

**Triggers:**
- Always for lossless formats (FLAC, WAV, ALAC, M4A, CAF)
- Always when `fullScan = true`
- When bitrate unknown or genre blank

#### 2.2 WAV File Special Handling
```kotlin
fun extractEmbeddedArtFromWavFile(path: String, ...): Uri?
```
**Reason**: MediaMetadataRetriever often fails for WAV metadata

#### 2.3 FFmpeg Fallback
```kotlin
fun extractEmbeddedArtWithFfmpeg(mediaStoreId: Long, uri: Uri): Uri?
```
**Triggers**: Last resort if other methods return null

### Album Art Caching

**Cache Location**: `context.filesDir/embedded_album_art/`

**File naming**: `{mediaStoreId}.jpg`

**Caching Logic** (cacheEmbeddedAlbumArt function):
```kotlin
private fun cacheEmbeddedAlbumArt(
    mediaStoreId: Long, 
    albumId: Long, 
    bytes: ByteArray, 
    forceRefresh: Boolean = false
): Uri {
    val dir = File(context.filesDir, "embedded_album_art").apply { mkdirs() }
    val f = File(dir, "$mediaStoreId.jpg")
    
    if (!forceRefresh && f.exists() && f.length() > 0) 
        return Uri.fromFile(f)  // Return cached if available
    
    // Compression logic:
    if (!useOriginalQuality && bytes.size > 100 * 1024) {
        // Downsample large images
        // Default: 512x512 max after sampling
    }
    
    f.writeBytes(compressedOrOriginal)
    return Uri.fromFile(f)
}
```

**Preferences Affecting Caching**:
- `use_original_quality_art` (boolean): Skip compression
- `artwork_enrichment_enabled` (boolean): Enable cloud art fetching

---

## 3. DEFAULT PLACEHOLDER IMAGE

**File:** `app/src/main/res/drawable/ic_album_placeholder.xml`

### Definition:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="200dp"
    android:height="200dp"
    android:viewportWidth="200"
    android:viewportHeight="200">
    <path
        android:fillColor="#2A2A35"
        android:pathData="M0,0h200v200h-200z" />
    <!-- Dark background with album icon -->
    <path android:fillColor="#3D4A5C" android:pathData="M100,48c-22,0 -40,18 -40,40v52c0,6 5,11 11,11h58c6,0 11,-5 11,-11V88c0,-22 -18,-40 -40,-40z" />
    <!-- Blue note icon -->
    <path android:fillColor="#6B8CFF" android:pathData="M78,118 L78,142 L122,130 L122,106z" />
</vector>
```

### Style:
- **Background**: Dark gray (#2A2A35)
- **Icon Body**: Medium gray (#3D4A5C)
- **Music Note**: Blue (#6B8CFF)
- **Size**: 200×200 dp (scalable)

### Fallback Triggers:
- Song.albumArtUri is null
- Song.albumArtUri is empty string
- Image fails to load from URI
- Exception during bitmap decoding

---

## 4. COIL IMAGE LOADING LIBRARY

**Dependency**: `coil-compose:2.6.0`

### Usage Pattern in Compose:

```kotlin
AsyncImage(
    model = ImageRequest.Builder(context)
        .data(song.albumArtUri)
        .diskCachePolicy(CachePolicy.ENABLED)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .placeholder(R.drawable.ic_album_placeholder)
        .error(R.drawable.ic_album_placeholder)
        .build(),
    contentDescription = "Album Art",
    modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)),
    contentScale = ContentScale.Crop
)
```

### Caching Strategy:
- **Memory Cache**: LRU in-memory cache (fast)
- **Disk Cache**: System-managed cache (persistent across sessions)
- **Both enabled** for album art loads

---

## 5. UI COMPONENTS FOR ALBUM ART DISPLAY

### 5.1 AlbumArtImage Component (Reusable)

**File:** `app/src/main/java/com/beatraxus/app/ui/components/AlbumArt.kt`

```kotlin
@Composable
fun AlbumArtImage(
    song: Song,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    grayscale: Boolean = false
)
```

**Features**:
- Uses Coil's AsyncImage
- Disk + Memory caching enabled
- Placeholder: ic_album_placeholder
- Error handling: Falls back to placeholder
- Optional grayscale filter (ColorMatrix saturation = 0)
- Rounded corners configurable

**Usage Locations**:
1. **SongListItem.kt**: Song list thumbnail display
2. **SongOptionsSheet.kt**: Options dialog (56dp)
3. **NowPlayingSection.kt**: Compact now-playing view (42dp)

### 5.2 NowPlayingScreen (Full-Size Display)

**File:** `app/src/main/java/com/beatraxus/app/ui/screens/NowPlayingScreen.kt`

**Direct AsyncImage Usage** (not AlbumArtImage component):
```kotlin
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data(song.albumArtUri)
        .diskCachePolicy(CachePolicy.ENABLED)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .build(),
    contentDescription = null,
    placeholder = painterResource(R.drawable.ic_album_placeholder),
    error = painterResource(R.drawable.ic_album_placeholder),
    fallback = painterResource(R.drawable.ic_album_placeholder),  // Triple fallback
    modifier = Modifier.fillMaxSize(),
    contentScale = ContentScale.Crop
)
```

**Additional Effects**:
- Gradient overlay with fade to black
- Fade in/out animation (600ms duration)
- Can display lyrics overlay on top
- Responds to touch gestures for queue display

---

## 6. MUSIC WIDGETS (Small/Medium/Large)

**File:** `app/src/main/java/com/beatraxus/app/widget/MusicWidgets.kt`

### Widget Types:
- **Small**: 48×48 dp album art thumbnail
- **Medium**: 64×64 dp with controls
- **Large**: 100×100 dp with full controls

### Image Processing (getImageProvider function):

```kotlin
private suspend fun getImageProvider(context: Context, uriString: String): ImageProvider {
    if (uriString.isEmpty()) 
        return ImageProvider(R.drawable.ic_album_placeholder)
    
    // Caching check
    if (uriString == lastAlbumArtUri && lastImageProvider != null) {
        return lastImageProvider!!
    }
    
    // Load and process
    try {
        val uri = Uri.parse(uriString)
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        }
        
        if (bitmap != null) {
            // 1. Scale to max 300px
            val scaled = scaleBitmap(bitmap, 300)
            
            // 2. Apply blur effect:
            //    - Downscale to 1/10
            //    - Upscale back (cheap blur)
            //    - Add 45% alpha dark overlay
            val blurred = applyBlurEffect(scaled)
            
            return ImageProvider(blurred)
        }
    } catch (e: Exception) {
        // Fall through to placeholder
    }
    
    return ImageProvider(R.drawable.ic_album_placeholder)
}
```

**Key Features**:
- Caches result in `lastImageProvider` variable
- Blur effect for widget background aesthetic
- Semi-transparent dark overlay (rgba(0,0,0,0.45))
- Handles exceptions gracefully

---

## 7. NOTIFICATION ALBUM ART

**File:** `app/src/main/java/com/beatraxus/app/service/AudioPlaybackService.kt`

### LoadAlbumArt Function:

```kotlin
private suspend fun loadAlbumArt(song: Song?) {
    if (song?.id == currentAlbumArtSongId && currentAlbumArt != null) 
        return  // Cache hit
    
    val loaded = withContext(Dispatchers.IO) {
        val uri = song?.albumArtUri ?: return@withContext null
        try {
            if (uri.scheme?.startsWith("http") == true) {
                // HTTP URI: Use Coil
                val loader = ImageLoader(this@AudioPlaybackService)
                val request = ImageRequest.Builder(this@AudioPlaybackService)
                    .data(uri)
                    .size(500)  // Max 500px
                    .allowHardware(false)
                    .build()
                val result = loader.execute(request)
                if (result is SuccessResult) {
                    (result.drawable as? BitmapDrawable)?.bitmap
                } else null
            } else {
                // Local URI: Use ContentResolver
                contentResolver.openInputStream(uri)?.use { input ->
                    val original = BitmapFactory.decodeStream(input)
                    if (original != null) {
                        // Scale to 500px maintaining aspect ratio
                        scaleBitmap(original, 500)
                    } else null
                }
            }
        } catch (e: Exception) {
            null  // Return null, use default notification
        }
    }
    
    if (loaded != null) {
        currentAlbumArt = loaded
        currentAlbumArtSongId = song?.id
    }
}
```

**Usage**:
- Sets large icon in notification
- Falls back to `R.drawable.ic_album_placeholder` if extraction fails
- Caches in memory during playback

---

## 8. COMPLETE ALBUM ART LOADING CHAIN

```
┌─────────────────────────────────────────────────────────────┐
│ SONG OBJECT (model.Song)                                    │
│ - albumArtUri: Uri? = null                                  │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ├─── NULL? ──→ Use ic_album_placeholder
                     │
                     ├─── HTTP/HTTPS URI?
                     │    └─→ Coil ImageLoader (remote fetch + cache)
                     │
                     ├─── content:// URI?
                     │    ├─→ Try load from cache
                     │    │   (filesDir/embedded_album_art/)
                     │    └─→ Fall back to MediaStore if missing
                     │
                     └─── file:// URI?
                          ├─→ Load from local file
                          └─→ Fall back to placeholder on error

┌─────────────────────────────────────────────────────────────┐
│ UI RENDERING                                                │
├─────────────────────────────────────────────────────────────┤
│ AsyncImage(model = imageUri)                                │
│   - Memory Cache Hit? ──→ Display immediately              │
│   - Disk Cache Hit? ───→ Decode + Display                  │
│   - Load from URI? ────→ Fetch + Cache + Display           │
│   - All failed? ───────→ Display ic_album_placeholder      │
└─────────────────────────────────────────────────────────────┘
```

---

## 9. CACHE LOCATIONS SUMMARY

| Layer | Location | Details |
|-------|----------|---------|
| **Extracted Art** | `filesDir/embedded_album_art/[mediaStoreId].jpg` | Persistent cache |
| **Coil Memory** | RAM (LRU) | Fast but cleared on GC |
| **Coil Disk** | System cache dir | Managed by Coil lib |
| **Notification** | RAM variable `currentAlbumArt` | During playback |
| **Widget Background** | RAM variable `lastImageProvider` | Blurred bitmap |

---

## 10. FALLBACK STRATEGY

**Fallback Priority Order**:

1. **Primary**: `song.albumArtUri` (actual image)
2. **Secondary**: MediaStore system album art (`content://media/external/audio/albumart/{albumId}`)
3. **Tertiary**: Extracted & cached embedded art (from MusicRepository)
4. **Quaternary**: FFmpeg extraction (last resort)
5. **Final**: `ic_album_placeholder.xml` (always available)

Each layer is attempted if the previous fails.

---

## 11. CONFIGURATION & PREFERENCES

**SharedPreferences (key: "beatraxus")**:

| Key | Type | Default | Purpose |
|-----|------|---------|---------|
| `use_original_quality_art` | boolean | false | Disable JPEG compression |
| `artwork_enrichment_enabled` | boolean | true | Fetch cloud art for streaming |
| `data_saver_enabled` | boolean | false | Reduce art downloads on mobile |
| `sync_quality` | string | "MEDIUM" | Cloud art resolution |

---

## 12. KEY FILES TO KNOW

| Purpose | File Path | Key Classes/Functions |
|---------|-----------|----------------------|
| Data Model | `model/Song.kt` | Song.albumArtUri |
| Extraction | `repository/MusicRepository.kt` | scanAudioFiles(), cacheEmbeddedAlbumArt(), extractEmbeddedArtFromWavFile() |
| Placeholder | `res/drawable/ic_album_placeholder.xml` | Vector drawable |
| Main Component | `ui/components/AlbumArt.kt` | AlbumArtImage() |
| Now Playing | `ui/screens/NowPlayingScreen.kt` | AsyncImage display |
| Widgets | `widget/MusicWidgets.kt` | getImageProvider(), blur effect |
| Playback | `service/AudioPlaybackService.kt` | loadAlbumArt(), notification |
| Metadata | `repository/MetadataExtractor.kt` | Cloud art extraction |

---

## 13. ARCHITECTURE DIAGRAM

```
┌─────────────────────────────────────────────────────────────┐
│                      UI LAYER                               │
├─────────────────────────────────────────────────────────────┤
│  AlbumArtImage    NowPlayingScreen    MusicWidgets          │
│  (Reusable)       (Large Display)     (Background Blur)     │
└────────────┬───────────────┬─────────────────┬──────────────┘
             │               │                 │
             └───────────────┼─────────────────┘
                             │
                      AsyncImage (Coil)
                    (Memory + Disk Cache)
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
    HTTP/HTTPS              Local File      MediaStore
     Remote                  (file://)       (content://)
    .fetch()                 .open()           .default
                             Direct           System
                             Load              Fallback
        │                    │                    │
        └────────────────────┼────────────────────┘
                             │
                    URI Points to Art
                  (or null → placeholder)
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
    EXTRACTION          CACHING              DISPLAY
      LAYER              LAYER               LAYER
        │                    │                    │
    Metadata-         filesDir/               Compose
    Retriever       embedded_album_art/       Components
    WAV Parser       + Coil Cache
    FFmpeg           + Ram
        │                    │                    │
        └────────────────────┼────────────────────┘
                             │
                   Fallback: ic_album_placeholder.xml
```

---

## 14. PERFORMANCE CONSIDERATIONS

1. **Extraction**: 
   - Full scan (first time): ~1-5 seconds per 100 songs
   - Quick scan (updates): Uses MediaStore + skips retriever
   - Lossless files always trigger MediaMetadataRetriever

2. **Caching**:
   - Memory cache: ~10-50 images typical for active playlist
   - Disk cache: 100+ MB possible for large library

3. **UI Rendering**:
   - AsyncImage handles off-main-thread loading
   - Coil prevents redundant loads (memory + disk cache)
   - Placeholder shows immediately while loading

4. **Optimization Options**:
   - Disable cloud enrichment for faster local scans
   - Enable data saver for mobile data
   - Keep original quality disabled to save storage

---

## 15. COMMON OPERATIONS

### To Add Album Art to a Song:
1. Extract/provide image bytes
2. Call `cacheEmbeddedAlbumArt(mediaStoreId, albumId, bytes)`
3. Update `Song.albumArtUri` with returned cache URI

### To Update All Album Art:
1. Call `musicRepository.scanAudioFiles(fullScan = true)`
2. Forces re-extraction from all files
3. Re-caches all embedded art

### To Clear Album Art Cache:
```kotlin
File(context.filesDir, "embedded_album_art").deleteRecursively()
// Coil cache cleared on app updates or manual clear
```

### To Display Album Art:
```kotlin
AlbumArtImage(song = song, size = 200.dp)
// or
AsyncImage(model = song.albumArtUri, ...)
```

---

## Summary

Beatraxus implements a robust, multi-layer album art system that:
- ✅ Extracts embedded artwork from local music files
- ✅ Caches artwork locally to prevent re-processing
- ✅ Provides smart fallbacks at each level
- ✅ Uses Coil for efficient network/disk/memory caching
- ✅ Handles special cases (WAV, FLAC, HTTP URIs)
- ✅ Displays placeholders gracefully when art unavailable
- ✅ Applies effects (blur) for widget backgrounds
- ✅ Stores album art in multiple UI contexts (widgets, notifications, screens)
