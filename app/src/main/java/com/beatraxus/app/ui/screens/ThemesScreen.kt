package com.beatraxus.app.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.beatraxus.app.model.AppearanceConfig
import com.beatraxus.app.theme.ThemeBackground
import com.beatraxus.app.theme.ThemeCodec
import com.beatraxus.app.theme.ThemeIndexEntry
import com.beatraxus.app.theme.ThemeInbox
import com.beatraxus.app.theme.ThemeSpec
import com.beatraxus.app.theme.ThemeViewModel
import com.beatraxus.app.ui.theme.DspPalette

private val Teal = Color(0xFF00C2A8)
private val CardBg = Color(0xFF111216).copy(alpha = 0.72f)

/**
 * Settings > Appearance > Themes.
 * Browse = official themes from the theme repo, Installed = themes on this phone (work offline),
 * Import = a friend's code/link/file, plus sharing your own look.
 */
@Composable
fun ThemesContent(appearance: AppearanceConfig) {
    val vm: ThemeViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val active = appearance.activeTheme

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.messageShown()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ActiveThemeCard(active, onDetach = vm::detachActive)

        TabPills(listOf("Browse", "Installed", "Import"), tab) { tab = it }

        when (tab) {
            0 -> BrowseTab(
                store = state.store,
                installed = state.installed,
                activeId = active?.id,
                busyId = state.busyId,
                onRefresh = { vm.refreshStore(force = true) },
                onGet = vm::installAndApply,
                onApplyInstalled = vm::apply
            )
            1 -> InstalledTab(
                installed = state.installed,
                activeId = active?.id,
                onApply = vm::apply,
                onRemove = { vm.remove(it, it.id == active?.id) }
            )
            else -> ImportTab(appearance, vm)
        }
    }
}

// ------------------------------------------------------------------ tabs

@Composable
private fun BrowseTab(
    store: com.beatraxus.app.theme.StoreUiState,
    installed: List<ThemeSpec>,
    activeId: String?,
    busyId: String?,
    onRefresh: () -> Unit,
    onGet: (ThemeIndexEntry) -> Unit,
    onApplyInstalled: (ThemeSpec) -> Unit
) {
    val installedById = remember(installed) { installed.associateBy { it.id } }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (store.fromCache) "Showing saved list (offline)" else "Official themes",
                color = Color.White.copy(0.55f), fontSize = 12.sp, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh, enabled = !store.loading) {
                Icon(Icons.Rounded.Refresh, "Refresh", tint = Color.White.copy(0.7f))
            }
        }

        when {
            store.loading && store.entries.isEmpty() -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                CircularProgressIndicator(color = Teal)
            }
            store.error != null && store.entries.isEmpty() -> InfoCard(store.error)
            store.entries.isEmpty() -> InfoCard("No themes in the store yet.")
            else -> store.entries.forEach { entry ->
                val local = installedById[entry.id]
                val needsUpdate = local != null && local.version < entry.version
                ThemeCard(
                    name = entry.name,
                    author = entry.author,
                    swatches = entry.preview.ifEmpty { local?.preview.orEmpty() },
                    note = if (needsUpdate) "Update available" else null
                ) {
                    when {
                        busyId == entry.id -> CircularProgressIndicator(Modifier.size(24.dp), color = Teal, strokeWidth = 2.dp)
                        activeId == entry.id && !needsUpdate -> AppliedChip()
                        needsUpdate -> SmallButton("Update") { onGet(entry) }
                        local != null -> SmallButton("Apply") { onApplyInstalled(local) }
                        else -> SmallButton("Get") { onGet(entry) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledTab(
    installed: List<ThemeSpec>,
    activeId: String?,
    onApply: (ThemeSpec) -> Unit,
    onRemove: (ThemeSpec) -> Unit
) {
    val context = LocalContext.current
    var confirmRemove by remember { mutableStateOf<ThemeSpec?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (installed.isEmpty()) {
            InfoCard("Themes you get or import are saved here and work offline.")
        }
        installed.forEach { spec ->
            ThemeCard(name = spec.name, author = spec.author, swatches = spec.preview, note = null) {
                IconButton(onClick = { shareText(context, spec.name, ThemeCodec.shareLink(spec)) }) {
                    Icon(Icons.Rounded.Share, "Share", tint = Color.White.copy(0.7f))
                }
                IconButton(onClick = { confirmRemove = spec }) {
                    Icon(Icons.Rounded.Delete, "Remove", tint = Color.White.copy(0.7f))
                }
                if (activeId == spec.id) AppliedChip() else SmallButton("Apply") { onApply(spec) }
            }
        }
    }

    confirmRemove?.let { spec ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${spec.name}?") },
            text = { Text("It will be deleted from this phone. Your current look stays as it is.") },
            confirmButton = { TextButton(onClick = { onRemove(spec); confirmRemove = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ImportTab(appearance: AppearanceConfig, vm: ThemeViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var input by rememberSaveable { mutableStateOf("") }
    val parsed = remember(input) { if (input.isBlank()) null else vm.parseImport(input) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { s ->
                // Read at most MAX+1 bytes (InputStream.readNBytes needs API 33, minSdk is 26).
                val buf = ByteArray(ThemeCodec.MAX_THEME_BYTES + 1)
                var n = 0
                while (n < buf.size) {
                    val r = s.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n > ThemeCodec.MAX_THEME_BYTES) error("too large")
                input = String(buf, 0, n, Charsets.UTF_8)
            }
        }.onFailure { Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Paste a theme code or beatraxus://theme link from a friend, or open a theme .json file.",
            color = Color.White.copy(0.55f), fontSize = 12.sp)

        OutlinedTextField(
            value = input,
            onValueChange = { input = it.take(ThemeCodec.MAX_THEME_BYTES) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("BTX1.… or beatraxus://theme?data=…") },
            minLines = 3,
            maxLines = 6
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { input = clipboard.getText()?.text.orEmpty() }) {
                Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Paste")
            }
            OutlinedButton(onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Open file")
            }
        }

        if (parsed != null) {
            val spec = parsed.getOrNull()
            if (spec != null) {
                ThemePreviewCard(spec)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { vm.saveImported(spec, apply = true); input = "" },
                        colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color.Black)
                    ) { Text("Apply") }
                    OutlinedButton(onClick = { vm.saveImported(spec, apply = false); input = "" }) { Text("Save only") }
                }
            } else {
                Text(
                    parsed.exceptionOrNull()?.message ?: "That isn't a valid theme",
                    color = Color(0xFFFF6B6B), fontSize = 13.sp
                )
            }
        }

        ShareMyLookCard(appearance)
    }
}

/** Turns the current Appearance settings into a theme and shares it as a link or code. */
@Composable
private fun ShareMyLookCard(appearance: AppearanceConfig) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var name by rememberSaveable { mutableStateOf("") }
    val spec = remember(appearance, name) { if (name.isBlank()) null else themeFromCurrentLook(appearance, name) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Share my current look", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("Your seekbar, font, DSP colours and backgrounds, as a link or code for a friend.",
            color = Color.White.copy(0.55f), fontSize = 12.sp)
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(40) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), placeholder = { Text("Name your theme") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = spec != null,
                onClick = { spec?.let { shareText(context, it.name, ThemeCodec.shareLink(it)) } },
                colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color.Black)
            ) { Text("Share link") }
            OutlinedButton(enabled = spec != null, onClick = {
                spec?.let {
                    clipboard.setText(AnnotatedString(ThemeCodec.shareCode(it)))
                    Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Copy code") }
        }
    }
}

// ------------------------------------------------------------------ incoming link dialog

/** Shown from anywhere in the app when a beatraxus://theme link is opened. */
@Composable
fun ThemeImportDialog(link: String) {
    val vm: ThemeViewModel = viewModel()
    val parsed = remember(link) { vm.parseImport(link) }
    val spec = parsed.getOrNull()

    AlertDialog(
        onDismissRequest = ThemeInbox::consume,
        title = { Text(if (spec != null) "Import theme?" else "Can't import theme") },
        text = {
            if (spec != null) ThemePreviewCard(spec)
            else Text(parsed.exceptionOrNull()?.message ?: "That isn't a valid theme")
        },
        confirmButton = {
            if (spec != null) TextButton(onClick = { vm.saveImported(spec, apply = true); ThemeInbox.consume() }) { Text("Apply") }
            else TextButton(onClick = ThemeInbox::consume) { Text("OK") }
        },
        dismissButton = {
            if (spec != null) {
                Row {
                    TextButton(onClick = { vm.saveImported(spec, apply = false); ThemeInbox.consume() }) { Text("Save only") }
                    TextButton(onClick = ThemeInbox::consume) { Text("Cancel") }
                }
            }
        }
    )
}

// ------------------------------------------------------------------ pieces

@Composable
private fun ActiveThemeCard(active: ThemeSpec?, onDetach: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (active != null) "Theme: ${active.name}" else "No theme applied",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                if (active != null) "Parts you customize yourself are marked as yours automatically."
                else "Pick one below. Your own settings stay untouched until you do.",
                color = Color.White.copy(0.55f), fontSize = 12.sp
            )
        }
        if (active != null) TextButton(onClick = onDetach) { Text("Detach") }
    }
}

@Composable
private fun TabPills(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White.copy(0.06f)).padding(4.dp)
    ) {
        labels.forEachIndexed { i, label ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50))
                    .background(if (i == selected) Teal else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (i == selected) Color.Black else Color.White.copy(0.8f),
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ThemeCard(
    name: String,
    author: String,
    swatches: List<Int>,
    note: String?,
    actions: @Composable () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Swatches(swatches)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("by $author" + (note?.let { " · $it" } ?: ""), color = Color.White.copy(0.55f), fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            actions()
        }
    }
}

@Composable
private fun ThemePreviewCard(spec: ThemeSpec) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Swatches(spec.preview)
        Text(spec.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("by ${spec.author}", color = Color.White.copy(0.55f), fontSize = 12.sp)
    }
}

/** The gallery preview: coloured stripes, so no images are needed. */
@Composable
private fun Swatches(colors: List<Int>) {
    val list = colors.ifEmpty { listOf(0xFF22222E.toInt(), 0xFF3A3A4A.toInt()) }
    Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))) {
        list.forEach { Box(Modifier.weight(1f).height(44.dp).background(Color(it))) }
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color.Black),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AppliedChip() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CheckCircle, null, tint = Teal, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Applied", color = Teal, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
private fun InfoCard(text: String) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg).padding(18.dp)
    ) { Text(text, color = Color.White.copy(0.65f), fontSize = 13.sp) }
}

// ------------------------------------------------------------------ helpers

private fun shareText(context: android.content.Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Beatraxus theme: $title")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share theme"))
}

/** Builds a shareable theme from the live settings. Backgrounds are taken from the main screen. */
private fun themeFromCurrentLook(a: AppearanceConfig, name: String): ThemeSpec {
    val palette = DspPalette.from(a)
    val accent = a.activeTheme?.accent ?: palette.primary.toArgb()
    val accent2 = a.activeTheme?.accentSecondary ?: palette.secondary.toArgb()
    val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "my-theme" }
    val mode = a.mainBackgroundMode
    return ThemeSpec(
        id = slug,
        name = name.trim().take(40),
        author = "Me",
        version = 1,
        isDark = true,
        accent = accent,
        accentSecondary = accent2,
        preview = listOf(0xFF0B0B14.toInt(), accent, accent2),
        dspPaletteId = a.dspPaletteId,
        dspPrimary = if (a.dspPaletteId == DspPalette.CUSTOM_ID) a.dspCustomPrimary else palette.primary.toArgb(),
        dspSecondary = if (a.dspPaletteId == DspPalette.CUSTOM_ID) a.dspCustomSecondary else palette.secondary.toArgb(),
        seekbarStyle = a.seekbarStyle.name,
        fontId = a.selectedFontId,
        background = ThemeBackground(
            mode = mode,
            blurScale = (a.mainBlurIntensity / 120f).coerceIn(0.25f, 2f),
            blurDarkness = a.mainBlurDarkness,
            solidIntensity = a.mainSolidColorIntensity,
            solidDarkness = a.mainSolidColorDarkness
        )
    )
}

