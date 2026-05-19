package com.beatflowy.app.ui.screens

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.*
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.beatflowy.app.ui.theme.*
import kotlinx.coroutines.delay

private data class ActiveDownload(
    val id: Long,
    val title: String,
    var progress: Int = 0,
    var status: String = "Queued"
)

// ── Stealth Browser Script (Theming & Scrubbing Lucida) ──────────────────────
private val STEALTH_SCRIPT = """
(function() {
    const style = document.createElement('style');
    style.innerHTML = `
        @import url('https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;600;700&display=swap');
        
        html, body { 
            background: #07070D !important; 
            color: #F0F0FF !important; 
            font-family: 'Space Grotesk', sans-serif !important;
            scrollbar-width: none !important;
            -webkit-font-smoothing: antialiased;
        }

        /* 1. Eliminate site branding and intrusive elements */
        header, footer, nav, [class*="navbar"], [class*="topbar"], [class*="footer"],
        [class*="logo"], [id*="logo"], .brand, [title*="lucida" i], [alt*="lucida" i],
        [src*="lucida" i], .cookie-notice, .cookie-banner, .announcement-bar, #announcement,
        [class*="sidebar"], #sidebar, .nav-menu, .menu-icon, .user-profile,
        [aria-label*="lucida" i], [data-testid*="lucida" i], .lucida-ad, .site-header, .site-footer {
            display: none !important;
            visibility: hidden !important;
            height: 0 !important;
            opacity: 0 !important;
            pointer-events: none !important;
        }

        /* 2. Redesign cards for a high-end application look */
        .result, .card, .track-item, [class*="item"], [class*="row"], .search-result, .media-card {
            background: linear-gradient(160deg, #13131F, #0D0D18) !important;
            border: 1px solid rgba(255,255,255,0.05) !important;
            border-radius: 20px !important;
            margin-bottom: 16px !important;
            padding: 20px !important;
            box-shadow: 0 10px 30px rgba(0,0,0,0.5) !important;
            transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1) !important;
        }
        
        .result:hover {
            border-color: #0A84FF60 !important;
            transform: translateY(-2px);
        }

        /* 3. Professional Buttons (Glassmorphism) */
        button, .btn, .button, input[type="submit"], [role="button"] {
            background: linear-gradient(135deg, #0A84FF, #5AC8FA) !important;
            color: #FFFFFF !important;
            border-radius: 14px !important;
            font-weight: 700 !important;
            padding: 14px 24px !important;
            border: none !important;
            text-transform: uppercase !important;
            font-size: 12px !important;
            letter-spacing: 0.5px !important;
            box-shadow: 0 8px 20px rgba(10, 132, 255, 0.2) !important;
            cursor: pointer !important;
        }

        /* 4. Professional Inputs */
        input, select, textarea {
            background: #0D0D18 !important;
            border: 1px solid #1E1E35 !important;
            color: #F0F0FF !important;
            border-radius: 12px !important;
            padding: 12px 16px !important;
            outline: none !important;
            width: 100% !important;
            box-sizing: border-box !important;
            font-size: 15px !important;
        }
        input:focus { 
            border-color: #0A84FF !important; 
        }

        ::-webkit-scrollbar { display: none !important; }
    `;
    document.head.appendChild(style);

    function scrub(node) {
        if (node.nodeType === 3) {
            if (node.nodeValue.toLowerCase().includes('lucida')) {
                node.nodeValue = node.nodeValue.replace(/lucida/gi, 'Music Engine');
            }
        } else if (node.nodeType === 1) {
            ['title', 'placeholder', 'aria-label', 'alt', 'data-title'].forEach(attr => {
                if (node.hasAttribute(attr) && node.getAttribute(attr).toLowerCase().includes('lucida')) {
                    node.setAttribute(attr, node.getAttribute(attr).replace(/lucida/gi, 'Music Engine'));
                }
            });
            for (let i = 0; i < node.childNodes.length; i++) scrub(node.childNodes[i]);
        }
    }
    scrub(document.body);
    document.title = document.title.replace(/lucida/gi, 'Beatraxus Engine');

    const observer = new MutationObserver((mutations) => {
        mutations.forEach((m) => m.addedNodes.forEach(scrub));
    });
    observer.observe(document.body, { childList: true, subtree: true });
})();
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var isPageLoading by remember { mutableStateOf(false) }
    var isWebReady by remember { mutableStateOf(false) }
    var loadingProgress by remember { mutableStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var activeDownloads by remember { mutableStateOf(listOf<ActiveDownload>()) }
    var showDownloadPanel by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // ── Download Manager Receiver ────────────────────────────────────────────
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != -1L) {
                    activeDownloads = activeDownloads.map {
                        if (it.id == id) it.copy(progress = 100, status = "Completed") else it
                    }
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    // ── Progress Sync ────────────────────────────────────────────────────────
    LaunchedEffect(activeDownloads) {
        while (activeDownloads.any { it.status == "Downloading" || it.status == "Queued" }) {
            delay(1200)
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            activeDownloads = activeDownloads.map { dl ->
                if (dl.status == "Completed" || dl.status == "Failed") return@map dl
                val cursor = dm.query(DownloadManager.Query().setFilterById(dl.id))
                if (cursor != null && cursor.moveToFirst()) {
                    val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    cursor.close()
                    val prog = if (total > 0) ((bytes * 100) / total).toInt() else 0
                    val stat = when (status) {
                        DownloadManager.STATUS_RUNNING -> "Downloading"
                        DownloadManager.STATUS_SUCCESSFUL -> "Completed"
                        DownloadManager.STATUS_FAILED -> "Failed"
                        else -> "Queued"
                    }
                    dl.copy(progress = prog, status = stat)
                } else { cursor?.close(); dl }
            }
        }
    }

    BackHandler {
        if (webViewRef?.canGoBack() == true) webViewRef?.goBack() else onBack()
    }

    Scaffold(
        containerColor = BgDeep,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth().zIndex(10f),
                color = BgDeep,
                shadowElevation = 0.dp
            ) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { if (webViewRef?.canGoBack() == true) webViewRef?.goBack() else onBack() },
                            modifier = Modifier.background(BgSurface, CircleShape).size(40.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = TextPrimary, modifier = Modifier.size(20.dp))
                        }

                        Spacer(Modifier.width(16.dp))

                        Text(
                            text = "Music Downloader",
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )

                        // ── Download Center Toggle ──
                        Box {
                            IconButton(
                                onClick = { showDownloadPanel = !showDownloadPanel },
                                modifier = Modifier.background(BgSurface, CircleShape).size(40.dp)
                            ) {
                                Icon(Icons.Rounded.Download, null, tint = if (showDownloadPanel) AccentBlue else TextPrimary, modifier = Modifier.size(20.dp))
                            }
                            if (activeDownloads.any { it.status == "Downloading" }) {
                                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(AccentBlue).align(Alignment.TopEnd).border(2.dp, BgDeep, CircleShape))
                            }
                        }
                    }

                    if (isPageLoading) {
                        LinearProgressIndicator(
                            progress = { loadingProgress / 100f },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = AccentBlue,
                            trackColor = Color.Transparent
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(BgDeep)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
                            setSupportZoom(true)
                            builtInZoomControls = false
                        }

                        setDownloadListener { url, userAgent, contentDisp, mime, _ ->
                            try {
                                var fileName = URLUtil.guessFileName(url, contentDisp, mime)
                                fileName = fileName.replace("lucida", "Music", ignoreCase = true)
                                val request = DownloadManager.Request(Uri.parse(url)).apply {
                                    setMimeType(mime)
                                    addRequestHeader("User-Agent", userAgent)
                                    addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                                    setTitle(fileName)
                                    setDescription("Downloading High-Quality Music")
                                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "Beatraxus/$fileName")
                                }
                                val id = (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                                activeDownloads = activeDownloads + ActiveDownload(id, fileName.replace(Regex("\\..*$"), ""))
                                showDownloadPanel = true
                            } catch (e: Exception) { Toast.makeText(ctx, "Download Error", Toast.LENGTH_SHORT).show() }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { 
                                isPageLoading = true
                                isWebReady = false 
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.evaluateJavascript(STEALTH_SCRIPT) {
                                    isWebReady = true 
                                    isPageLoading = false
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() { override fun onProgressChanged(view: WebView?, newProgress: Int) { loadingProgress = newProgress } }
                        loadUrl("https://lucida.to/")
                    }
                },
                modifier = Modifier.fillMaxSize().alpha(if (isWebReady) 1f else 0f)
            )

            // ── Stealth Loading Overlay ──
            if (!isWebReady) {
                Box(modifier = Modifier.fillMaxSize().background(BgDeep), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Optimizing Engine...", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            }

            // ── Side Download Panel ──
            AnimatedVisibility(
                visible = showDownloadPanel,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().width(320.dp).zIndex(20f)
            ) {
                Surface(
                    color = BgBase,
                    shadowElevation = 24.dp,
                    border = BorderStroke(1.dp, Divider),
                    shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Download Queue", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { showDownloadPanel = false }) { Icon(Icons.Rounded.Close, null, tint = TextSecondary) }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = Divider)
                        if (activeDownloads.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text("No active downloads", color = TextMuted, fontSize = 14.sp)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(activeDownloads.reversed()) { dl -> DownloadItemCard(dl) }
                            }
                        }
                        Button(
                            onClick = { activeDownloads = emptyList() },
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BgSurface),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Clear History", color = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadItemCard(dl: ActiveDownload) {
    val isDone = dl.status == "Completed"
    val rotation = rememberInfiniteTransition(label = "").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = ""
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BgSurface)
            .border(1.dp, if (isDone) Divider else AccentBlue.copy(0.3f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isDone) Icons.Rounded.CheckCircle else Icons.Rounded.Sync,
                contentDescription = null,
                tint = if (isDone) Color(0xFF30D158) else AccentBlue,
                modifier = Modifier.size(18.dp).graphicsLayer { if (!isDone) rotationZ = rotation.value }
            )
            Spacer(Modifier.width(12.dp))
            Text(dl.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { dl.progress / 100f },
                modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape),
                color = if (isDone) Color(0xFF30D158) else AccentBlue,
                trackColor = BgElevated
            )
            Spacer(Modifier.width(12.dp))
            Text(if (dl.status == "Downloading") "${dl.progress}%" else dl.status, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}
