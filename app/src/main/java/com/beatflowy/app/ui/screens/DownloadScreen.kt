package com.beatflowy.app.ui.screens

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import androidx.compose.ui.text.style.TextAlign
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

private fun getStealthScript(defaultService: String, audioFormat: String): String {
    return """
(function() {
    document.documentElement.style.backgroundColor = '#07070D';
    const style = document.createElement('style');
    style.innerHTML = `
        @import url('https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;600;700&display=swap');
        
        html, body { 
            background: #07070D !important; 
            color: #F0F0FF !important; 
            font-family: 'Space Grotesk', sans-serif !important;
            -webkit-font-smoothing: antialiased;
            margin: 0 !important;
            padding: 0 !important;
            width: 100% !important;
            min-height: 100% !important;
        }

        /* Force full screen content and remove constraints */
        #__next, #root, main, .container, [class*="container"], [class*="wrapper"], [class*="main"], [class*="layout"] {
            width: 100% !important;
            max-width: 100% !important;
            margin: 0 !important;
            padding: 24px !important;
            min-height: 100vh !important;
            background: transparent !important;
            display: flex !important;
            flex-direction: column !important;
            gap: 80px !important;
            box-sizing: border-box !important;
        }

        /* Remove the pink/backgrounds and apply card theme */
        div, section, article, aside {
            background: transparent !important;
            background-color: transparent !important;
            border: none !important;
            box-shadow: none !important;
        }

        .result, .card, .track-item, [class*="item"], [class*="row"], .search-result, .media-card, [class*="Search_card"], [class*="Card_card"], [class*="Home_card"] {
            background: linear-gradient(160deg, #13131F, #0D0D18) !important;
            border: 1px solid rgba(255,255,255,0.05) !important;
            border-radius: 20px !important;
            margin-bottom: 80px !important;
            padding: 30px !important;
            box-shadow: 0 10px 40px rgba(0,0,0,0.6) !important;
            display: block !important;
            width: 100% !important;
            box-sizing: border-box !important;
        }

        header, footer, nav, [class*="navbar"], [class*="topbar"], [class*="footer"],
        [class*="logo"], [id*="logo"], .brand, [title*="lucida" i], [alt*="lucida" i],
        [src*="lucida" i], .cookie-notice, .cookie-banner, .announcement-bar, #announcement,
        [class*="sidebar"], #sidebar, .nav-menu, .menu-icon, .user-profile,
        [aria-label*="lucida" i], [data-testid*="lucida" i], .lucida-ad, .site-header, .site-footer,
        [class*="credits" i], [id*="credits" i], .credits {
            display: none !important;
            visibility: hidden !important;
            height: 0 !important;
            opacity: 0 !important;
            pointer-events: none !important;
        }

        /* Force ALL text elements to be white */
        *, p, h1, h2, h3, h4, h5, h6, span, a, label, li, div, input, select, option {
            color: #F0F0FF !important;
            -webkit-text-fill-color: #F0F0FF !important;
        }

        button, .btn, .button, input[type="submit"], [role="button"] {
            background: linear-gradient(135deg, #0A84FF, #5AC8FA) !important;
            color: #FFFFFF !important;
            -webkit-text-fill-color: #FFFFFF !important;
            border-radius: 14px !important;
            font-weight: 700 !important;
            padding: 14px 24px !important;
            border: none !important;
            text-transform: uppercase !important;
            font-size: 12px !important;
            box-shadow: 0 8px 20px rgba(10, 132, 255, 0.2) !important;
        }

        input, select, textarea {
            background: #0D0D18 !important;
            border: 1px solid #1E1E35 !important;
            color: #F0F0FF !important;
            -webkit-text-fill-color: #F0F0FF !important;
            border-radius: 12px !important;
            padding: 14px 18px !important;
            outline: none !important;
            width: 100% !important;
            box-sizing: border-box !important;
            margin-bottom: 15px !important;
        }
        
        /* Aggressive fix for select elements to stay white after selection */
        select option {
            background-color: #13131F !important;
            color: #F0F0FF !important;
        }
        
        select:focus, select:active, select:hover {
            color: #F0F0FF !important;
            -webkit-text-fill-color: #F0F0FF !important;
        }

        ::-webkit-scrollbar { display: none !important; }
    `;
    document.head.appendChild(style);

    function hideUnwanted() {
        const blacklist = ['f.a.q', 'faq', 'donate', 'stats', 'support', 'about', 'privacy', 'terms', 'discord', 'telegram', 'github', 'twitter', 'contact', 'legal', 'license', 'roadmap', 'community', 'status', 'documentation', 'credits'];
        const allElements = document.querySelectorAll('a, button, span, p, li, h1, h2, h3, h4, h5, div');
        allElements.forEach(el => {
            if (el.children.length > 3 && el.tagName === 'DIV') return;
            const text = el.innerText.toLowerCase().replace(/\s/g, '');
            if (blacklist.some(word => text.includes(word.replace(/\s/g, '')))) {
                if (el.innerText.length < 60) {
                    el.style.setProperty('display', 'none', 'important');
                }
            }
        });
    }

    function applySettings() {
        // Apply default service if on home page and not selected
        if (window.location.pathname === '/' || window.location.pathname === '') {
            const service = '$defaultService'.toLowerCase();
            const serviceBtn = document.querySelector('button[aria-label*="' + service + '" i], img[alt*="' + service + '" i]?.closest("button")');
            if (serviceBtn && !serviceBtn.classList.contains('active')) {
                serviceBtn.click();
            }
        }

        // Apply audio format
        const format = '$audioFormat'.toLowerCase().split('_')[0]; // flac, mp3, etc.
        const formatSelect = document.querySelector('select[name="format"], select[aria-label*="format" i]');
        if (formatSelect) {
            for (let i = 0; i < formatSelect.options.length; i++) {
                if (formatSelect.options[i].value.toLowerCase().includes(format) || formatSelect.options[i].text.toLowerCase().includes(format)) {
                    if (formatSelect.selectedIndex !== i) {
                        formatSelect.selectedIndex = i;
                        formatSelect.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                    break;
                }
            }
        }
    }

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

    function forceWhite() {
        document.querySelectorAll('select, option, input, button, [role="button"], [class*="select"], [id*="select"]').forEach(el => {
             el.style.setProperty('color', '#F0F0FF', 'important');
             el.style.setProperty('-webkit-text-fill-color', '#F0F0FF', 'important');
             if (el.tagName === 'SELECT' || el.tagName === 'OPTION') {
                 el.style.setProperty('background-color', '#0D0D18', 'important');
             }
             
             if (el.tagName === 'SELECT') {
                if (!el.dataset.hooked) {
                    el.dataset.hooked = "true";
                    el.addEventListener('change', function() {
                        this.style.setProperty('color', '#F0F0FF', 'important');
                        this.style.setProperty('-webkit-text-fill-color', '#F0F0FF', 'important');
                    });
                }
                
                el.querySelectorAll('*').forEach(child => {
                    child.style.setProperty('color', '#F0F0FF', 'important');
                    child.style.setProperty('-webkit-text-fill-color', '#F0F0FF', 'important');
                });
             }
        });
        
        document.querySelectorAll('*').forEach(el => {
            if (el.children.length === 0 && el.innerText.trim().length > 0) {
                const color = window.getComputedStyle(el).color;
                if (color === 'rgb(0, 0, 0)' || color === 'black' || color.includes('rgba(0, 0, 0')) {
                    el.style.setProperty('color', '#F0F0FF', 'important');
                    el.style.setProperty('-webkit-text-fill-color', '#F0F0FF', 'important');
                }
            }
        });
    }

    hideUnwanted();
    applySettings();
    scrub(document.body);
    forceWhite();

    const observer = new MutationObserver((mutations) => {
        hideUnwanted();
        mutations.forEach((m) => m.addedNodes.forEach(node => {
            if (node.nodeType === 1 || node.nodeType === 3) {
                scrub(node);
                if (node.nodeType === 1) {
                    hideUnwanted();
                    forceWhite();
                    applySettings();
                }
            }
        }));
    });
    observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class'] });
    
    setInterval(() => {
        forceWhite();
        hideUnwanted();
        applySettings();
    }, 1500);
})();
""".trimIndent()
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(onBack: () -> Unit, onNavigateToSettings: () -> Unit) {
    val context = LocalContext.current
    
    val dlPrefs = remember { context.getSharedPreferences("beatraxus_dl", Context.MODE_PRIVATE) }
    val defaultService = dlPrefs.getString("default_service", "qobuz") ?: "qobuz"
    val audioFormat = dlPrefs.getString("audio_format", "flac") ?: "flac"
    
    val isOnline = remember { mutableStateOf(checkConnectivity(context)) }
    var isWebReady by remember { mutableStateOf(false) }
    var hasConnectionError by remember { mutableStateOf(!isOnline.value) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var activeDownloads by remember { mutableStateOf(listOf<ActiveDownload>()) }
    var showDownloadPanel by remember { mutableStateOf(false) }

    var isLeaving by remember { mutableStateOf(false) }
    val handleBack: () -> Unit = {
        if (webViewRef?.canGoBack() == true) {
            webViewRef?.goBack()
        } else if (!isLeaving) {
            isLeaving = true
            onBack()
        }
    }

    val animatedAlpha by animateFloatAsState(
        targetValue = if (isWebReady && !hasConnectionError) 1f else 0f,
        animationSpec = tween(600),
        label = "webAlpha"
    )

    BackHandler(enabled = true) { handleBack() }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != -1L) {
                    val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val query = DownloadManager.Query().setFilterById(id)
                    val cursor = dm.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                        
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            activeDownloads = activeDownloads.map {
                                if (it.id == id) it.copy(progress = 100, status = "Completed") else it
                            }
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            activeDownloads = activeDownloads.map {
                                if (it.id == id) it.copy(status = "Failed ($reason)") else it
                            }
                            Toast.makeText(ctx, "Download Failed: $title (Error $reason)", Toast.LENGTH_LONG).show()
                        }
                    }
                    cursor?.close()
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

    LaunchedEffect(activeDownloads) {
        while (activeDownloads.any { it.status == "Downloading" || it.status == "Queued" }) {
            delay(1200)
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            activeDownloads = activeDownloads.map { dl ->
                if (dl.status.contains("Completed") || dl.status.contains("Failed")) return@map dl
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

    Scaffold(
        containerColor = BgDeep,
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth().zIndex(10f),
                color = BgDeep,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = handleBack,
                        modifier = Modifier.background(BgSurface, CircleShape).size(40.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = "Music Downloader",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = onNavigateToSettings,
                            modifier = Modifier.background(BgSurface, CircleShape).size(40.dp)
                        ) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = TextPrimary, modifier = Modifier.size(20.dp))
                        }
                        Box {
                            IconButton(
                                onClick = { showDownloadPanel = !showDownloadPanel },
                                modifier = Modifier.background(BgSurface, CircleShape).size(40.dp)
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, tint = if (showDownloadPanel) AccentBlue else TextPrimary, modifier = Modifier.size(20.dp))
                            }
                            if (activeDownloads.any { it.status == "Downloading" }) {
                                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(AccentBlue).align(Alignment.TopEnd).border(2.dp, BgDeep, CircleShape))
                            }
                        }
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
                        setBackgroundColor(android.graphics.Color.parseColor("#07070D"))
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
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
                                    setDescription("Downloading $fileName via Music Engine")
                                    setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "Beatraxus/$fileName")
                                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                        setRequiresCharging(false)
                                        setAllowedOverMetered(true)
                                        setAllowedOverRoaming(true)
                                    }
                                }
                                val id = (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                                activeDownloads = activeDownloads + ActiveDownload(id, fileName.replace(Regex("\\..*$"), ""))
                                showDownloadPanel = true
                                Toast.makeText(ctx, "Download Started: $fileName", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) { 
                                Toast.makeText(ctx, "Download Error: ${e.message}", Toast.LENGTH_SHORT).show() 
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { 
                                isWebReady = false 
                                hasConnectionError = false
                            }
                            
                            override fun onPageCommitVisible(view: WebView?, url: String?) {
                                view?.evaluateJavascript(getStealthScript(defaultService, audioFormat), null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (!hasConnectionError) {
                                    view?.evaluateJavascript(getStealthScript(defaultService, audioFormat)) {
                                        view.postDelayed({
                                            isWebReady = true 
                                        }, 800)
                                    }
                                } else {
                                    isWebReady = true 
                                }
                            }
                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                if (request?.isForMainFrame == true) {
                                    hasConnectionError = true
                                }
                            }
                        }
                        loadUrl("https://lucida.to/")
                    }
                },
                modifier = Modifier.fillMaxSize().alpha(animatedAlpha)
            )

            if (hasConnectionError) {
                Box(modifier = Modifier.fillMaxSize().background(BgDeep), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Surface(shape = CircleShape, color = AccentRed.copy(0.1f), modifier = Modifier.size(80.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Warning, contentDescription = null, tint = AccentRed, modifier = Modifier.size(44.dp))
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(text = "Network Unavailable", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Text(text = "Beatraxus Engine requires an active internet connection.", color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(32.dp))
                        Button(
                            onClick = { 
                                if (checkConnectivity(context)) {
                                    hasConnectionError = false
                                    isWebReady = false
                                    webViewRef?.reload() 
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BgSurface),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.height(52.dp).fillMaxWidth(0.65f)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Retry Connection", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else if (!isWebReady) {
                Box(modifier = Modifier.fillMaxSize().background(BgDeep), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Optimizing Engine...", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            }

            AnimatedVisibility(
                visible = showDownloadPanel,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().width(320.dp).zIndex(20f)
            ) {
                Surface(color = BgBase, shadowElevation = 24.dp, border = BorderStroke(1.dp, Divider), shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Download Queue", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { showDownloadPanel = false }) { Icon(Icons.Rounded.Close, contentDescription = null, tint = TextSecondary) }
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
                        Button(onClick = { activeDownloads = emptyList() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = BgSurface), shape = RoundedCornerShape(12.dp)) {
                            Text("Clear History", color = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

private fun checkConnectivity(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@Composable
private fun DownloadItemCard(dl: ActiveDownload) {
    val isDone = dl.status == "Completed"
    val isFailed = dl.status.contains("Failed")
    val rotation = rememberInfiniteTransition(label = "").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)), label = ""
    )
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(BgSurface)
            .border(1.dp, if (isDone) Divider else if (isFailed) AccentRed.copy(0.3f) else AccentBlue.copy(0.3f), RoundedCornerShape(16.dp)).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when {
                    isDone -> Icons.Rounded.CheckCircle
                    isFailed -> Icons.Rounded.Error
                    else -> Icons.Rounded.Sync
                },
                contentDescription = null, 
                tint = when {
                    isDone -> Color(0xFF30D158)
                    isFailed -> AccentRed
                    else -> AccentBlue
                },
                modifier = Modifier.size(18.dp).graphicsLayer { if (!isDone && !isFailed) rotationZ = rotation.value }
            )
            Spacer(Modifier.width(12.dp))
            Text(dl.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { dl.progress / 100f },
                modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape),
                color = when {
                    isDone -> Color(0xFF30D158)
                    isFailed -> AccentRed
                    else -> AccentBlue
                }, trackColor = BgElevated
            )
            Spacer(Modifier.width(12.dp))
            Text(if (dl.status == "Downloading") "${dl.progress}%" else dl.status, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}
