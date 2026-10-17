package com.beatraxus.app.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.beatraxus.app.addons.YouTubeAddon
import com.beatraxus.app.addons.YtVideo
import com.beatraxus.app.addons.ottCatching
import com.beatraxus.app.viewmodel.PlayerViewModel

private val YtBg = Color(0xFF0F0F0F)
private val YtRed = Color(0xFFFF0000)
private val YtChip = Color(0xFF272727)
private val YtSub = Color(0xFFAAAAAA)

private val ytCategories = listOf(
    "Trending" to null, "Music" to "10", "Gaming" to "20", "Entertainment" to "24",
    "News" to "25", "Sports" to "17", "Tech" to "28"
)

private fun Context.findHostActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Handles the IFrame player's fullscreen button (WebView custom view -> full window, landscape). */
private class FullscreenController(private val activity: Activity?) {
    var active by mutableStateOf(false)
        private set
    private var customView: View? = null
    private var callback: WebChromeClient.CustomViewCallback? = null
    private var oldOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    fun enter(view: View, cb: WebChromeClient.CustomViewCallback) {
        val act = activity
        if (act == null || customView != null) { cb.onCustomViewHidden(); return }
        val decor = act.window.decorView as FrameLayout
        view.setBackgroundColor(android.graphics.Color.BLACK)
        decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        customView = view; callback = cb
        oldOrientation = act.requestedOrientation
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.getInsetsController(act.window, decor).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        active = true
    }

    fun exit() {
        val act = activity ?: return
        val v = customView ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        callback?.onCustomViewHidden()
        customView = null; callback = null
        act.requestedOrientation = oldOrientation
        WindowCompat.getInsetsController(act.window, act.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        active = false
    }
}

@Composable
fun YouTubeHomeScreen(addon: YouTubeAddon, playerViewModel: PlayerViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var category by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf<String?>(null) }
    var videos by remember { mutableStateOf<List<YtVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<YtVideo?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val fullscreen = remember { FullscreenController(context.findHostActivity()) }

    LaunchedEffect(category, submitted, reload) {
        loading = true; error = null
        val r = ottCatching {
            val q = submitted
            if (q != null) addon.searchVideos(q) else addon.trending(ytCategories[category].second)
        }
        videos = r.getOrDefault(emptyList())
        error = r.exceptionOrNull()?.message
        loading = false
    }

    // The music player must not keep playing underneath a YouTube video.
    LaunchedEffect(playing?.id) {
        if (playing != null && playerViewModel.uiState.value.isPlaying) playerViewModel.togglePlayPause()
    }

    BackHandler(enabled = fullscreen.active || playing != null || searching) {
        when {
            fullscreen.active -> fullscreen.exit()
            playing != null -> playing = null
            else -> { searching = false; query = ""; submitted = null }
        }
    }
    DisposableEffect(Unit) { onDispose { fullscreen.exit() } }

    Box(Modifier.fillMaxSize().background(YtBg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(
                searching = searching, query = query,
                onQuery = { query = it },
                onSubmit = { submitted = query.trim().ifBlank { null } },
                onSearchToggle = {
                    if (searching) { searching = false; query = ""; submitted = null } else searching = true
                },
                onBack = onBack
            )
            if (!searching) {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ytCategories.size) { i ->
                        val sel = i == category
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) Color.White else YtChip)
                                .clickable { category = i }.padding(horizontal = 12.dp, vertical = 7.dp)
                        ) { Text(ytCategories[i].first, color = if (sel) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                    }
                }
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = YtRed) }
                error != null -> Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text("Couldn't load YouTube", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(error ?: "", color = YtSub, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                    Text("Check your API key (YouTube Data API v3 enabled) and the daily quota.", color = YtSub, fontSize = 12.sp)
                    TextButton(onClick = { reload++ }) { Text("Retry", color = YtRed) }
                }
                videos.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No videos found", color = YtSub) }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(videos, key = { it.id }) { v -> VideoCard(v) { playing = v } }
                }
            }
        }

        playing?.let { v ->
            WatchPage(v, videos, fullscreen, onPick = { playing = it }, onClose = { playing = null })
        }
    }
}

@Composable
private fun TopBar(
    searching: Boolean, query: String, onQuery: (String) -> Unit, onSubmit: () -> Unit,
    onSearchToggle: () -> Unit, onBack: () -> Unit
) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (searching) onSearchToggle() else onBack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
        }
        if (searching) {
            TextField(
                value = query, onValueChange = onQuery, singleLine = true,
                placeholder = { Text("Search YouTube", color = YtSub) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = YtChip, unfocusedContainerColor = YtChip,
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = YtRed
                ),
                shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f)
            )
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, null, tint = Color.White) }
        } else {
            Icon(Icons.Default.PlayCircle, null, tint = YtRed, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(6.dp))
            Text("YouTube", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onSearchToggle) { Icon(Icons.Default.Search, null, tint = Color.White) }
        }
    }
}

@Composable
private fun VideoCard(v: YtVideo, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(bottom = 16.dp)) {
        Box {
            AsyncImage(v.thumbnail, v.title, Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(YtChip), contentScale = ContentScale.Crop)
            if (v.duration.isNotBlank()) {
                Text(
                    v.duration, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (v.duration == "LIVE") YtRed else Color(0xCC000000)).padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(YtChip), Alignment.Center) {
                Text(v.channel.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(v.title, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(v.channel, v.views, v.age).filter { it.isNotBlank() }.joinToString(" · "), color = YtSub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun WatchPage(v: YtVideo, upNext: List<YtVideo>, fullscreen: FullscreenController, onPick: (YtVideo) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var playerError by remember(v.id) { mutableStateOf<Int?>(null) }
    var expanded by remember(v.id) { mutableStateOf(false) }
    // clickable(no-op) on the root so taps never fall through to the video list underneath
    Column(Modifier.fillMaxSize().background(YtBg).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}.statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            YouTubePlayer(v.id, fullscreen, Modifier.fillMaxSize()) { playerError = it }
            playerError?.let { code ->
                Column(Modifier.fillMaxSize().background(Color(0xE6000000)).padding(16.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text(
                        if (code in listOf(101, 150, 152, 153)) "The owner doesn't allow this video to play outside YouTube."
                        else "This video can't be played here (error $code).",
                        color = Color.White, fontSize = 13.sp
                    )
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${v.id}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Open in YouTube", color = YtRed, fontWeight = FontWeight.Bold) }
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp)) {
                    Text(v.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(listOf(v.views, v.age).filter { it.isNotBlank() }.joinToString(" · "), color = YtSub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Text(v.channel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
                    if (v.description.isNotBlank()) {
                        Text(v.description, color = YtSub, fontSize = 13.sp, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                HorizontalDivider(color = YtChip)
                Text("Up next", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
            }
            items(upNext.filter { it.id != v.id }, key = { it.id }) { n -> VideoCard(n) { onPick(n) } }
        }
    }
}

/** Official YouTube IFrame player inside a WebView. Ads, if any, are served by the official player. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubePlayer(videoId: String, fullscreen: FullscreenController, modifier: Modifier, onError: (Int) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnError by rememberUpdatedState(onError)
    val main = remember { Handler(Looper.getMainLooper()) }
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> webViewHolder[0]?.onPause()
                Lifecycle.Event.ON_RESUME -> webViewHolder[0]?.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            webViewHolder[0]?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
            webViewHolder[0] = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                addJavascriptInterface(YouTubeJsBridge(main) { code -> currentOnError(code) }, "Android")
                webViewClient = object : WebViewClient() {
                    // Never navigate the player away from the embed page.
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) = fullscreen.enter(view, callback)
                    override fun onHideCustomView() = fullscreen.exit()
                    override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                }
                webViewHolder[0] = this
            }
        },
        update = { wv ->
            if (wv.tag != videoId) {
                wv.tag = videoId
                val id = videoId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
                wv.loadDataWithBaseURL("https://www.youtube.com", playerHtml(id), "text/html", "utf-8", null)
            }
        }
    )
}

/** Public (not anonymous) so WebView's JavaScript bridge can reach it. */
class YouTubeJsBridge(private val handler: Handler, private val onErr: (Int) -> Unit) {
    @JavascriptInterface
    fun onError(code: Int) { handler.post { onErr(code) } }
}

private fun playerHtml(id: String) = """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:absolute;top:0;left:0;width:100%;height:100%}</style></head>
<body><div id="p"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
function onYouTubeIframeAPIReady(){
  new YT.Player('p',{width:'100%',height:'100%',videoId:'$id',
    playerVars:{playsinline:1,autoplay:1,rel:0,fs:1,origin:'https://www.youtube.com'},
    events:{onReady:function(e){e.target.playVideo();},onError:function(e){Android.onError(e.data);}}});
}
</script></body></html>
"""
