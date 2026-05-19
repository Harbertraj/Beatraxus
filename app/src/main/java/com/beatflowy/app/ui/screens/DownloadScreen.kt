package com.beatflowy.app.ui.screens

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.beatflowy.app.model.AlbumItem
import com.beatflowy.app.model.DownloadItem
import com.beatflowy.app.model.DownloadQuality
import com.beatflowy.app.model.DownloadStatus
import com.beatflowy.app.viewmodel.QobuzDownloadViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    viewModel: QobuzDownloadViewModel,
    onBack: () -> Unit,
    onPickFolder: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var isPopping by remember { mutableStateOf(false) }
    val cyanBlue = Color(0xFF00F2FF)
    
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Tracks", "Albums")

    var showQualitySheet by remember { mutableStateOf(false) }
    var selectedItemForDownload by remember { mutableStateOf<DownloadItem?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbarMessage()
        }
    }

    androidx.activity.compose.BackHandler(enabled = !isPopping) {
        isPopping = true
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "DOWNLOADS",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (!isPopping) {
                                isPopping = true
                                onBack()
                            }
                        }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(bottom = 40.dp)
            ) {
                // SECTION 0: Folder Picker Banner
                if (uiState.downloadSettings.downloadLocation == null) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFFFC107).copy(alpha = 0.12f))
                                .clickable { onPickFolder() }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = Color(0xFFFFC107)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "No download folder set — tap to choose",
                                color = Color(0xFFFFC107),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // SECTION 1: Search Bar
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = {
                                Text(
                                    "Search by song, artist or album name…",
                                    color = Color.White.copy(0.4f),
                                    fontSize = 14.sp
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
                            shape = RoundedCornerShape(24.dp),
                            leadingIcon = {
                                Icon(Icons.Rounded.Search, null, tint = Color.White.copy(0.6f))
                            },
                            trailingIcon = {
                                if (uiState.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                        Icon(Icons.Rounded.Close, null, tint = Color.White.copy(0.6f))
                                    }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color.White.copy(0.05f),
                                unfocusedContainerColor = Color.White.copy(0.05f),
                                focusedBorderColor = Color.White.copy(0.2f),
                                unfocusedBorderColor = Color.White.copy(0.1f)
                            ),
                            singleLine = true
                        )

                        Button(
                            onClick = { viewModel.searchQobuz(uiState.searchQuery) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = cyanBlue,
                                contentColor = Color.Black
                            )
                        ) {
                            Text("Search / Add", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                item {
                    Column {
                        TabRow(
                            selectedTabIndex = selectedTabIndex,
                            containerColor = Color.Transparent,
                            contentColor = cyanBlue,
                            indicator = { tabPositions ->
                                TabRowDefaults.SecondaryIndicator(
                                    Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                                    color = cyanBlue
                                )
                            },
                            divider = {}
                        ) {
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedTabIndex == index,
                                    onClick = { selectedTabIndex = index },
                                    text = {
                                        Text(
                                            text = title,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    },
                                    selectedContentColor = cyanBlue,
                                    unselectedContentColor = Color.White.copy(alpha = 0.6f)
                                )
                            }
                        }

                        val isSearching = uiState.isSearching
                        val error = uiState.errorMessage
                        val isAutoVerifying = uiState.isAutoVerifying
                        val verificationStep = uiState.verificationStep

                        AnimatedVisibility(
                            visible = isSearching || isAutoVerifying,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = cyanBlue,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    if (isAutoVerifying) verificationStep ?: "Verifying humanity..." else "Searching Qobuz…", 
                                    color = Color.White.copy(0.7f), 
                                    fontSize = 13.sp
                                )
                            }
                        }

                        if (!isSearching && !isAutoVerifying && error != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFFF5252).copy(alpha = 0.12f))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    error,
                                    color = Color(0xFFFF5252),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }

                // SECTION 2.5: Search Results
                if (selectedTabIndex == 0 && uiState.searchResults.isNotEmpty()) {
                    items(uiState.searchResults, key = { "search_${it.id}" }) { item ->
                        SearchResultItemCard(
                            item = item,
                            onDownload = {
                                selectedItemForDownload = item
                                showQualitySheet = true
                            }
                        )
                    }
                } else if (selectedTabIndex == 1 && uiState.albumResults.isNotEmpty()) {
                    items(uiState.albumResults, key = { "album_${it.id}" }) { album ->
                        val isExpanded = uiState.expandedAlbumId == album.id
                        Column {
                            AlbumItemCard(
                                album = album,
                                isExpanded = isExpanded,
                                onToggleExpand = { viewModel.toggleAlbumExpand(album.id) }
                            )
                            
                            if (isExpanded) {
                                if (uiState.isLoadingAlbumTracks) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            color = cyanBlue,
                                            strokeWidth = 2.dp
                                        )
                                    }
                                } else {
                                    val tracks = uiState.albumTracksMap[album.id] ?: emptyList()
                                    Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, bottom = 8.dp)) {
                                        Button(
                                            onClick = { viewModel.addAllToQueue(tracks) },
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = cyanBlue,
                                                contentColor = Color.Black
                                            ),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text("Queue All Tracks", fontWeight = FontWeight.Bold)
                                        }
                                        tracks.forEach { track ->
                                            AlbumTrackRow(
                                                track = track,
                                                onDownload = {
                                                    selectedItemForDownload = track
                                                    showQualitySheet = true
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // SECTION 2: Quality Selector (Global settings - kept as is for context)
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Default Download Quality",
                            color = Color.White.copy(0.6f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        val qualities = listOf(
                            DownloadQuality.HiRes24Bit,
                            DownloadQuality.Lossless
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(end = 20.dp)
                        ) {
                            items(qualities) { quality ->
                                val isSelected = uiState.selectedQuality == quality
                                QualityChip(
                                    label = quality.label.split(" ").first(), // Short label
                                    isSelected = isSelected,
                                    selectedColor = cyanBlue,
                                    onClick = { viewModel.setSelectedQuality(quality) }
                                )
                            }
                        }
                    }
                }

                // SECTION 3: Download Queue
                item {
                    Text(
                        "Queue",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (uiState.downloadQueue.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No downloads queued",
                                color = Color.White.copy(0.4f),
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    items(uiState.downloadQueue, key = { it.id }) { item ->
                        QueueItemCard(
                            item = item,
                            onDelete = { viewModel.removeFromQueue(item.id) },
                            onStart = {
                                viewModel.startDownload(item)
                            }
                        )
                    }
                }

                // SECTION 4: How It Works
                item {
                    InfoCard()
                }
            }
        }

        if (showQualitySheet && selectedItemForDownload != null) {
            DownloadQualityBottomSheet(
                item = selectedItemForDownload!!,
                onDismiss = {
                    showQualitySheet = false
                    selectedItemForDownload = null
                },
                onAddToQueue = { quality ->
                    viewModel.addToQueue(selectedItemForDownload!!.copy(quality = quality))
                    showQualitySheet = false
                    selectedItemForDownload = null
                }
            )
        }

        if (uiState.showCaptchaDialog && uiState.captchaUrl != null) {
            CaptchaDialog(
                url = uiState.captchaUrl!!,
                onDismiss = { viewModel.dismissCaptcha() }
            )
        }

        // Hidden background verification WebView
        if (uiState.isAutoVerifying && uiState.captchaUrl != null) {
            BackgroundVerificationWebView(
                url = uiState.captchaUrl!!,
                searchQuery = uiState.autoVerificationQuery ?: uiState.searchQuery,
                onSuccess = { viewModel.onVerificationSuccess() },
                onStepUpdate = { viewModel.updateVerificationStep(it) },
                onFailure = { viewModel.onVerificationFailed(it) }
            )
        }
    }
}

@Composable
fun BackgroundVerificationWebView(
    url: String,
    searchQuery: String,
    onSuccess: () -> Unit,
    onStepUpdate: (String) -> Unit,
    onFailure: (String) -> Unit
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                // Optimize for background operation
                alpha = 0.01f
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        onStepUpdate("Bypassing security...")
                        view?.evaluateJavascript("""
                            (function() {
                                function trySolve() {
                                    // 1. Try Turnstile/hCaptcha/ReCaptcha checkbox in iframes
                                    const iframes = document.querySelectorAll('iframe');
                                    for (let i = 0; i < iframes.length; i++) {
                                        try {
                                            const innerDoc = iframes[i].contentDocument || iframes[i].contentWindow.document;
                                            const checkbox = innerDoc.querySelector('.recaptcha-checkbox-border, #checkbox, .ctp-checkbox-label, .h-captcha iframe');
                                            if (checkbox) {
                                                checkbox.click();
                                                return true;
                                            }
                                        } catch(e) {}
                                    }
                                    
                                    // 2. Direct search simulation
                                    const searchInput = document.querySelector('input[name="q"], input[type="search"]');
                                    if (searchInput && !document.querySelector('.cf-turnstile') && !document.querySelector('.g-recaptcha')) {
                                        searchInput.value = "$searchQuery";
                                        searchInput.dispatchEvent(new Event('input', { bubbles: true }));
                                        const form = searchInput.closest('form');
                                        if (form) {
                                            form.submit();
                                            return "SEARCHING";
                                        }
                                    }
                                    return false;
                                }

                                let attempts = 0;
                                const interval = setInterval(() => {
                                    attempts++;
                                    const result = trySolve();
                                    if (result === "SEARCHING" || result === true) {
                                        clearInterval(interval);
                                        setTimeout(() => { 
                                            if (window.verification_callback) window.verification_callback.onSuccess(); 
                                        }, 4000);
                                    }
                                    if (attempts > 15) {
                                        clearInterval(interval);
                                        if (window.verification_callback) window.verification_callback.onFailure("Verification timeout");
                                    }
                                }, 2500);
                            })();
                        """.trimIndent(), null)
                    }
                }
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun onSuccess() {
                        post { onSuccess() }
                    }
                    @android.webkit.JavascriptInterface
                    fun onFailure(error: String) {
                        post { onFailure(error) }
                    }
                }, "verification_callback")
                loadUrl(url)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.removeJavascriptInterface("verification_callback")
            webView.removeAllViews()
            webView.destroy()
        },
        modifier = Modifier.size(1.dp).alpha(0f)
    )
}

@Composable
fun CaptchaDialog(url: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.7f)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Focused Header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFAFAFA))
                        .padding(horizontal = 24.dp, vertical = 18.dp)
                ) {
                    Text(
                        "Verify Humanity",
                        color = Color.Black,
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.CenterEnd).size(36.dp)
                    ) {
                        Icon(Icons.Rounded.Close, null, tint = Color.Black.copy(0.4f))
                    }
                }

                var isLoading by remember { mutableStateOf(true) }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        isLoading = true
                                    }
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        isLoading = false
                                        // Selective focus injection: Hide EVERYTHING then only show known captcha containers
                                        // This makes it look like a native captcha popup
                                        view?.evaluateJavascript("""
                                            (function() {
                                                var style = document.createElement('style');
                                                style.innerHTML = `
                                                    * { 
                                                        background-image: none !important;
                                                    }
                                                    header, footer, nav, aside, .navbar, .footer, .header, .nav, .sidebar, 
                                                    .logo, .branding, .site-header, .site-footer, #header, #footer, 
                                                    .top-bar, .bottom-bar, .cookie-banner, .announcement, .ad-unit,
                                                    h1, h2, h3, p:not(.captcha-text), .description, .welcome-text {
                                                        display: none !important;
                                                        visibility: hidden !important;
                                                        height: 0 !important;
                                                        overflow: hidden !important;
                                                    }
                                                    body {
                                                        background: white !important;
                                                        display: flex !important;
                                                        justify-content: center !important;
                                                        align-items: center !important;
                                                        min-height: 100vh !important;
                                                        margin: 0 !important;
                                                        padding: 0 !important;
                                                    }
                                                    /* Ensure captcha elements stay visible */
                                                    .cf-turnstile, #captcha-container, .g-recaptcha, iframe, .h-captcha {
                                                        display: block !important;
                                                        visibility: visible !important;
                                                        margin: 0 auto !important;
                                                    }
                                                `;
                                                document.head.appendChild(style);
                                            })();
                                        """.trimIndent(), null)
                                    }

                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        return false
                                    }
                                }
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                                }
                                // Software layer fixes the potential NPE crash in some system versions
                                setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                                loadUrl(url)
                            }
                        },
                        onRelease = { webView ->
                            webView.stopLoading()
                            webView.clearHistory()
                            webView.removeAllViews()
                            webView.destroy()
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (isLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.Black, strokeWidth = 3.dp)
                        }
                    }
                }

                // Clean Footer
                Surface(
                    color = Color.White,
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Button(
                            onClick = {
                                CookieManager.getInstance().flush()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("I'VE COMPLETED IT", fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQualityBottomSheet(
    item: DownloadItem,
    onDismiss: () -> Unit,
    onAddToQueue: (DownloadQuality) -> Unit
) {
    var selectedQuality by remember { mutableStateOf<DownloadQuality>(DownloadQuality.HiRes24Bit) }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1A2E),
        contentColor = Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(0.2f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Text(
                    item.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    item.artist,
                    color = Color.White.copy(0.6f),
                    fontSize = 14.sp
                )
            }

            HorizontalDivider(color = Color.White.copy(0.1f))

            QualityOption(
                quality = DownloadQuality.HiRes24Bit,
                isSelected = selectedQuality == DownloadQuality.HiRes24Bit,
                onClick = { selectedQuality = DownloadQuality.HiRes24Bit }
            )

            QualityOption(
                quality = DownloadQuality.Lossless,
                isSelected = selectedQuality == DownloadQuality.Lossless,
                onClick = { selectedQuality = DownloadQuality.Lossless }
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { onAddToQueue(selectedQuality) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00F2FF),
                    contentColor = Color.Black
                )
            ) {
                Text("Add to Queue", fontWeight = FontWeight.Bold)
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Cancel", color = Color.White.copy(0.6f))
            }
        }
    }
}

@Composable
fun QualityOption(
    quality: DownloadQuality,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Color.White.copy(alpha = 0.05f) else Color.Transparent)
            .border(
                1.dp,
                if (isSelected) Color(0xFF00F2FF) else Color.White.copy(0.1f),
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = Color(0xFF00F2FF),
                unselectedColor = Color.White.copy(0.3f)
            )
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                quality.label,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                quality.description,
                color = Color.White.copy(0.5f),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun QualityChip(
    label: String,
    isSelected: Boolean,
    selectedColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) selectedColor else Color.Transparent,
        border = if (isSelected) null else BorderStroke(1.dp, Color.White.copy(0.3f)),
        contentColor = if (isSelected) Color.Black else Color.White
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun SearchResultItemCard(
    item: DownloadItem,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A2E)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF252540)),
                contentScale = ContentScale.Crop,
                error = rememberVectorPainter(Icons.Rounded.MusicNote),
                placeholder = rememberVectorPainter(Icons.Rounded.MusicNote)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1
                )
                Text(
                    "${item.artist} · ${item.album}",
                    color = Color.White.copy(0.55f),
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }

            IconButton(onClick = onDownload) {
                Icon(Icons.Rounded.Download, null, tint = Color(0xFF00F2FF))
            }
        }
    }
}

@Composable
fun AlbumItemCard(
    album: AlbumItem,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onToggleExpand() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A2E)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = album.coverUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF252540)),
                contentScale = ContentScale.Crop,
                error = rememberVectorPainter(Icons.Rounded.MusicNote),
                placeholder = rememberVectorPainter(Icons.Rounded.MusicNote)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    album.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1
                )
                Text(
                    "${album.artist} · ${album.tracksCount} tracks",
                    color = Color.White.copy(0.55f),
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }

            Icon(
                if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                null,
                tint = Color.White.copy(0.4f)
            )
        }
    }
}

@Composable
fun AlbumTrackRow(
    track: DownloadItem,
    onDownload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                track.artist,
                color = Color.White.copy(0.5f),
                fontSize = 12.sp,
                maxLines = 1
            )
        }
        IconButton(onClick = onDownload) {
            Icon(
                Icons.Rounded.Download,
                null,
                tint = Color(0xFF00F2FF),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun QueueItemCard(
    item: DownloadItem,
    onDelete: () -> Unit,
    onStart: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A2E)
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF252540)),
                    contentScale = ContentScale.Crop,
                    error = rememberVectorPainter(Icons.Rounded.MusicNote),
                    placeholder = rememberVectorPainter(Icons.Rounded.MusicNote)
                )

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1
                    )
                    Text(
                        "${item.artist} · ${item.quality.label}",
                        color = Color.White.copy(0.55f),
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }

                if (item.status == DownloadStatus.QUEUED || item.status == DownloadStatus.FAILED) {
                    IconButton(onClick = onStart) {
                        Icon(
                            if (item.status == DownloadStatus.FAILED) Icons.Rounded.Refresh else Icons.Rounded.Download, 
                            null, 
                            tint = Color(0xFF00F2FF)
                        )
                    }
                }

                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, null, tint = Color.White.copy(0.6f))
                }
            }

            if (item.status == DownloadStatus.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { item.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = Color(0xFF00F2FF),
                    trackColor = Color.Transparent,
                )
            }

            if (item.status == DownloadStatus.DONE) {
                Text(
                    "✓ Saved to downloads folder",
                    color = Color(0xFF00E676),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            if (item.status == DownloadStatus.FAILED) {
                Text(
                    "Download failed — verifying security...",
                    color = Color(0xFFFF5252),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun InfoCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(0.08f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A2E)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Search Qobuz's full catalog — Hi-Res FLAC and lossless results.\n" +
                "Tap ↓ on any result to add it to the queue, then tap the download icon to start.\n" +
                "Set your download folder in Settings → Downloads first.",
                color = Color.White.copy(0.7f),
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }
    }
}
