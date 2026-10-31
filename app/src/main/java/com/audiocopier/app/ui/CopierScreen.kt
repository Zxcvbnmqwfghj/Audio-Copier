package com.audiocopier.app.ui

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audiocopier.app.R
import com.audiocopier.app.copying.CopyRules
import com.audiocopier.app.data.Operation
import com.audiocopier.app.ui.theme.*
import java.text.DateFormat
import java.util.*

@Composable
fun CopierScreen(model: CopierViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val base = LocalContext.current
    val deviceConfiguration = LocalConfiguration.current
    val dark = when (state.settings.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val locale = state.settings.language.takeIf { it.isNotBlank() }?.let(::Locale) ?: deviceConfiguration.locales[0]
    val configuration = remember(deviceConfiguration, locale) { Configuration(deviceConfiguration).apply { setLocale(locale) } }
    val localized = remember(base, configuration) { base.createConfigurationContext(configuration) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) model.visible(true)
            if (event == Lifecycle.Event.ON_STOP) model.visible(false)
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.visible(true)
        onDispose { lifecycle.removeObserver(observer); model.visible(false) }
    }
    SideEffect {
        (base as? Activity)?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = false
        }
    }
    CompositionLocalProvider(
        LocalContext provides localized, LocalConfiguration provides configuration,
        LocalLayoutDirection provides if (configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        AudioCopierTheme(dark) { MainPages(state, model, base) }
    }
}

@Composable
private fun MainPages(state: CopierState, model: CopierViewModel, launchContext: Context) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var help by remember { mutableStateOf<Int?>(null) }
    var delete by remember { mutableStateOf<Operation?>(null) }
    var rename by remember { mutableStateOf<Operation?>(null) }
    var newName by rememberSaveable { mutableStateOf("") }
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { model.selectFolder(it, result.data?.flags ?: 0, true) }
    }
    val destinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { model.selectFolder(it, result.data?.flags ?: 0, false) }
    }
    val powerPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.toggleAutomatic() }
    val backgroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.background(true) }
    fun needsNotification() = Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(launchContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    fun open(intent: Intent) {
        try { launchContext.startActivity(intent) } catch (_: ActivityNotFoundException) { model.message(R.string.error_open) }
        catch (_: SecurityException) { model.message(R.string.error_permission) }
    }
    fun choose(source: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            if (!source) addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val current = if (source) state.settings.folders.source else state.settings.folders.destination
            if (current.isNotBlank()) putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(current))
        }
        try { if (source) sourcePicker.launch(intent) else destinationPicker.launch(intent) }
        catch (_: ActivityNotFoundException) { model.message(R.string.error_picker) }
        catch (_: SecurityException) { model.message(R.string.error_permission) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        bottomBar = { BottomTabs(page) { page = it; if (it == 1 || it == 2) model.refreshStats() } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (page) {
                0 -> PowerPage(state,
                    onPower = { if (Build.VERSION.SDK_INT >= 33 && !state.settings.automatic && state.settings.background && needsNotification()) powerPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else model.toggleAutomatic() },
                    onBackground = { value -> if (Build.VERSION.SDK_INT >= 33 && value && state.settings.automatic && needsNotification()) backgroundPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else model.background(value) },
                    onOld = { if (!state.oldDialog) model.chooseOld() else model.closeOld() },
                    onHelp = { help = it },
                )
                1 -> StatisticsPage(state, onHelp = { help = it }, onCache = model::clearCache,
                    onBattery = { open(Intent("android.intent.action.POWER_USAGE_SUMMARY")) },
                    onFolder = {
                        val folder = state.settings.folders.destination
                        if (folder.isBlank()) model.message(R.string.error_folders)
                        else {
                            val tree = Uri.parse(folder)
                            open(Intent(Intent.ACTION_VIEW).setDataAndType(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), DocumentsContract.Document.MIME_TYPE_DIR).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    },
                )
                2 -> FilesPage(state, onSource = { choose(true) }, onDestination = { choose(false) }, onHelp = { help = it },
                    onPlay = model::play, onSeek = model::seek, onNext = { id, delta -> model.next(delta, id) },
                    onAction = { operation, action -> when (action) {
                        0 -> delete = operation
                        1 -> { rename = operation; newName = operation.outputName }
                        2 -> model.details(operation)
                        3 -> {
                            val uri = Uri.parse(operation.destinationUri)
                            open(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = CopyRules.mime(operation.outputName, "")
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newRawUri(operation.outputName, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, null))
                        }
                    } },
                )
                3 -> SettingsPage(state, model::language, model::theme)
            }
            if (state.busy || state.autoProgress != null) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = Design.Blue)
        }
    }
    help?.let { text ->
        AlertDialog(onDismissRequest = { help = null }, title = { Text(stringResource(R.string.help)) },
            text = { Text(stringResource(text), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { help = null }) { Text(stringResource(R.string.ok)) } })
    }
    state.message?.let { message ->
        AlertDialog(onDismissRequest = model::dismissMessage, text = { Text(stringResource(message)) },
            confirmButton = { TextButton(onClick = model::dismissMessage) { Text(stringResource(R.string.ok)) } })
    }
    delete?.let { operation ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_question, operation.outputName)) },
            confirmButton = { TextButton(onClick = { delete = null; model.deleteCopy(operation) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text(stringResource(R.string.cancel)) } })
    }
    rename?.let { operation ->
        AlertDialog(onDismissRequest = { rename = null }, title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = newName.isNotBlank(), onClick = { rename = null; model.renameCopy(operation, newName) }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text(stringResource(R.string.cancel)) } })
    }
    state.detail?.let { (operation, file) ->
        AlertDialog(onDismissRequest = model::closeDetails, title = { Text(stringResource(R.string.details)) }, text = {
            SelectionContainer { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Literal(file.name)
                Text(stringResource(R.string.file_size, bytes(file.size)))
                Text(file.mime)
                if (file.modified > 0) Text(DateFormat.getDateTimeInstance().format(Date(file.modified)))
                Text(stringResource(R.string.source_title)); Literal(documentPath(operation.sourceUri))
                Text(stringResource(R.string.destination_title)); Literal(documentPath(operation.destinationUri))
            } }
        }, confirmButton = { TextButton(onClick = model::closeDetails) { Text(stringResource(R.string.ok)) } })
    }
    if (state.oldDialog) {
        AlertDialog(onDismissRequest = model::closeOld, title = { Text(stringResource(R.string.choose_old_files)) }, text = {
            LazyColumn(Modifier.heightIn(max = 390.dp)) {
                if (state.oldFiles.isEmpty()) item { Text(stringResource(R.string.no_files)) }
                items(state.oldFiles, key = { it.uri }) { file ->
                    Row(Modifier.fillMaxWidth().clickable { model.selectOld(file.uri) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = file.uri in state.oldSelected, onCheckedChange = { model.selectOld(file.uri) })
                        Column(Modifier.weight(1f)) { Literal(file.name); Text(bytes(file.size), fontSize = 12.sp) }
                    }
                }
            }
        }, confirmButton = {
            TextButton(enabled = state.oldSelected.isNotEmpty(), onClick = model::copyOld) { Text(stringResource(R.string.copy_selected, state.oldSelected.size)) }
        }, dismissButton = { TextButton(onClick = model::closeOld) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
internal fun BottomTabs(selected: Int, onSelect: (Int) -> Unit) {
    val dark = LocalDark.current
    val labels = listOf(R.string.tab_home, R.string.tab_statistics, R.string.tab_files, R.string.tab_settings)
    val glyphs = listOf(Glyph.POWER, Glyph.STATS, Glyph.FOLDER, Glyph.GEAR)
    // The navigation follows the selected language direction.
    CompositionLocalProvider(LocalLayoutDirection provides LocalLayoutDirection.current) {
        Row(Modifier.fillMaxWidth().background(Design.Nav).navigationBarsPadding().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            glyphs.forEachIndexed { index, glyph ->
                val label = stringResource(labels[index])
                val color = if ((index == selected) xor dark) Color.White else Color.Black
                Box(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = label; this.selected = index == selected; role = Role.Tab }
                    .clickable { onSelect(index) }, contentAlignment = Alignment.Center) {
                    DesignIcon(glyph, color, Design.Nav, Modifier.size(44.dp))
                }
            }
        }
    }
}

@Composable
internal fun PowerPage(state: CopierState, onPower: () -> Unit, onBackground: (Boolean) -> Unit, onOld: () -> Unit, onHelp: (Int) -> Unit) {
    val ink = MaterialTheme.colorScheme.onBackground
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth().border(5.dp, ink, RoundedCornerShape(62.dp)).padding(horizontal = 12.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val label = stringResource(if (state.settings.automatic) R.string.stop else R.string.start)
            Box(Modifier.size(140.dp).semantics { contentDescription = label; role = Role.Button }.clickable(enabled = !state.busy, onClick = onPower), contentAlignment = Alignment.Center) {
                DesignIcon(Glyph.POWER, Design.Nav, MaterialTheme.colorScheme.background, Modifier.size(132.dp))
            }
            val status = when {
                state.busy && !state.settings.automatic -> R.string.state_preparing
                state.monitorProblem != null && state.settings.automatic -> R.string.state_attention
                state.settings.automatic && state.settings.background && !state.serviceRunning -> R.string.monitor_periodic
                state.settings.automatic -> R.string.monitor_enabled
                else -> R.string.monitor_disabled
            }
            Text(stringResource(status), color = Design.Red, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onHelp(state.monitorProblem?.let(::problemString) ?: R.string.help_monitor) })
            Text(stringResource(R.string.auto_title), color = Design.Blue, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(stringResource(R.string.auto_description), color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(20.dp))
        SwitchRow(stringResource(R.string.background), state.settings.background, !state.busy, onBackground)
        Spacer(Modifier.height(8.dp))
        SwitchRow(stringResource(R.string.copy_old), state.oldDialog || state.manualProgress != null, !state.busy) { onOld() }
    }
}
@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = checked, onCheckedChange = change, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF51BC73), checkedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF999999), uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent))
        Text(label, color = Design.Blue, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
    }
}
@Composable
private fun BlueButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, textSize: Int = 21) {
    val ink = MaterialTheme.colorScheme.onBackground
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(Brush.horizontalGradient(listOf(Design.ButtonLeft, Design.ButtonRight)))
        .semantics { role = Role.Button; if (!enabled) disabled() }.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = ink, fontSize = textSize.sp, lineHeight = (textSize * 1.35f).sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}
@Composable
private fun HelpButton(onClick: () -> Unit) {
    val label = stringResource(R.string.help)
    Box(Modifier.size(42.dp).semantics { contentDescription = label; role = Role.Button }.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(27.dp).border(1.5.dp, Design.Help, CircleShape), contentAlignment = Alignment.Center) {
            Text("?", color = Design.Help, fontSize = 23.sp, fontWeight = FontWeight.Medium)
        }
    }
}
@Composable
private fun Separator() { HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.onBackground.copy(alpha = .6f), thickness = 1.dp) }
@Composable
private fun Heading(label: String, glyph: Glyph, modifier: Modifier = Modifier, textSize: Int = 30, help: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DesignIcon(glyph, MaterialTheme.colorScheme.onBackground, MaterialTheme.colorScheme.background, Modifier.size(58.dp))
        Text(label, color = Design.Blue, fontSize = textSize.sp, lineHeight = (textSize * 1.3f).sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp), textAlign = TextAlign.Start)
        if (help != null) HelpButton(help)
    }
}
@Composable
internal fun StatisticsPage(state: CopierState, onHelp: (Int) -> Unit, onCache: () -> Unit, onBattery: () -> Unit, onFolder: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Heading(stringResource(R.string.total_new_copies), Glyph.WAVE, textSize = 24)
        Text(state.autoCount.toString(), color = Design.Red, fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), textAlign = TextAlign.End)
        Spacer(Modifier.height(12.dp))
        BlueButton(stringResource(R.string.open_folder), onFolder, Modifier.fillMaxWidth(.88f), textSize = 18)
        Separator()
        Heading(stringResource(R.string.system_usage), Glyph.SHIELD, textSize = 30)
        Spacer(Modifier.height(20.dp))
        UsageRow(stringResource(R.string.energy_usage), stringResource(R.string.unavailable), Glyph.BATTERY) { onHelp(R.string.help_energy) }
        BlueButton(stringResource(R.string.battery_settings), onBattery, Modifier.fillMaxWidth(.88f).padding(top = 10.dp), textSize = 18)
        Spacer(Modifier.height(18.dp))
        UsageRow(stringResource(R.string.storage_usage), state.storageBytes?.let { bytes(it) } ?: stringResource(R.string.unavailable), Glyph.STORAGE) { onHelp(R.string.help_storage) }
        BlueButton(stringResource(R.string.clear_cache), onCache, Modifier.fillMaxWidth(.94f).padding(top = 12.dp), !state.busy, textSize = 18)
        Separator()
    }
}
@Composable
private fun UsageRow(label: String, value: String, glyph: Glyph, onHelp: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DesignIcon(glyph, MaterialTheme.colorScheme.onBackground, MaterialTheme.colorScheme.background, Modifier.size(48.dp))
        Text(label, color = Design.Blue, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(value, color = Design.Red, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 90.dp))
        HelpButton(onHelp)
    }
}
@Composable
internal fun FilesPage(
    state: CopierState, onSource: () -> Unit, onDestination: () -> Unit, onHelp: (Int) -> Unit,
    onPlay: (Operation) -> Unit, onSeek: (Long) -> Unit, onNext: (Long, Int) -> Unit,
    onAction: (Operation, Int) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val copies = state.copies.filter { it.outputName.contains(query, true) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            val ink = MaterialTheme.colorScheme.onBackground
            Row(Modifier.padding(top = 16.dp, bottom = 10.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(40.dp)).border(2.dp, ink, RoundedCornerShape(40.dp)), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(54.dp).fillMaxHeight().background(ink), contentAlignment = Alignment.Center) {
                    DesignIcon(Glyph.SEARCH, MaterialTheme.colorScheme.background, ink, Modifier.size(28.dp))
                }
                BasicTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink, fontSize = 20.sp, textDirection = TextDirection.ContentOrLtr),
                    cursorBrush = SolidColor(ink), modifier = Modifier.weight(1f).padding(horizontal = 12.dp), decorationBox = { field ->
                        if (query.isEmpty()) Text(stringResource(R.string.search_files), color = Color(0xFFAAAAAA), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        field()
                    })
            }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Heading(stringResource(R.string.source_title), Glyph.SOURCE, textSize = 26, help = { onHelp(R.string.help_source) })
                FolderLabel(state.settings.folders.source)
                BlueButton(stringResource(R.string.choose_source), onSource, Modifier.fillMaxWidth(.88f).padding(top = 8.dp), !state.busy)
                Separator()
                Heading(stringResource(R.string.destination_title), Glyph.DESTINATION, textSize = 26, help = { onHelp(R.string.help_destination) })
                FolderLabel(state.settings.folders.destination)
                BlueButton(stringResource(R.string.choose_destination), onDestination, Modifier.fillMaxWidth(.88f).padding(top = 8.dp), !state.busy)
                Separator()
            }
        }
        if (copies.isEmpty()) item { Text(stringResource(R.string.no_copies), textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp)) }
        items(copies, key = { it.id }) { copy ->
            AudioCard(copy, state, onPlay = { onPlay(copy) }, onSeek = onSeek, onNext = { onNext(copy.id, it) }, onAction = { onAction(copy, it) })
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AudioCard(operation: Operation, state: CopierState, onPlay: () -> Unit, onSeek: (Long) -> Unit, onNext: (Int) -> Unit, onAction: (Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme.onBackground
    val paper = MaterialTheme.colorScheme.background
    val actionsLabel = stringResource(R.string.file_actions)
    val previousLabel = stringResource(R.string.previous)
    val nextLabel = stringResource(R.string.next)
    val active = state.playingId == operation.id
    var drag by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth(.86f).border(1.5.dp, ink, RoundedCornerShape(22.dp)).padding(horizontal = 10.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DesignIcon(Glyph.MUSIC, ink, paper, Modifier.size(40.dp))
            Text(operation.outputName, modifier = Modifier.weight(1f).padding(horizontal = 4.dp), fontWeight = FontWeight.Bold, fontSize = 19.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrLtr), textAlign = TextAlign.Center)
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp).semantics { contentDescription = actionsLabel }) { DesignIcon(Glyph.MORE, ink, paper, Modifier.size(26.dp)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf(R.string.delete, R.string.rename, R.string.details, R.string.share).forEachIndexed { i, id ->
                        DropdownMenuItem(text = { Text(stringResource(id)) }, onClick = { menu = false; onAction(i) })
                    }
                }
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            val fraction = if (active && state.duration > 0) state.position.toFloat() / state.duration else 0f
            Slider(value = drag ?: fraction.coerceIn(0f, 1f), onValueChange = { drag = it },
                onValueChangeFinished = { drag?.let { onSeek((it * state.duration).toLong()) }; drag = null },
                enabled = active && state.duration > 0, modifier = Modifier.fillMaxWidth(.82f).height(24.dp),
                thumb = { Canvas(Modifier.size(7.dp)) { drawCircle(ink) } },
                track = { Canvas(Modifier.fillMaxWidth().height(2.dp)) { drawLine(ink, androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx()) } },
                colors = SliderDefaults.colors(thumbColor = ink, activeTrackColor = ink, inactiveTrackColor = ink.copy(alpha = .4f), disabledThumbColor = ink, disabledInactiveTrackColor = ink.copy(alpha = .4f)))
            if (active && state.duration > 0) Text("${time(state.position)} / ${time(state.duration)}", fontSize = 11.sp, color = ink)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = { onNext(-1) }, enabled = state.copies.indexOfFirst { it.id == operation.id } > 0,
                    modifier = Modifier.size(40.dp).semantics { contentDescription = previousLabel }) { DesignIcon(Glyph.PREVIOUS, ink, paper, Modifier.size(24.dp)) }
                val playLabel = stringResource(if (active && state.playing) R.string.pause else R.string.play)
                Box(Modifier.size(36.dp).clip(CircleShape).background(ink).semantics { contentDescription = playLabel; role = Role.Button }.clickable(onClick = onPlay), contentAlignment = Alignment.Center) {
                    DesignIcon(if (active && state.playing) Glyph.PAUSE else Glyph.PLAY, paper, ink, Modifier.size(26.dp))
                }
                IconButton(onClick = { onNext(1) }, enabled = state.copies.indexOfFirst { it.id == operation.id } < state.copies.lastIndex,
                    modifier = Modifier.size(40.dp).semantics { contentDescription = nextLabel }) { DesignIcon(Glyph.NEXT, ink, paper, Modifier.size(24.dp)) }
            }
        }
    }
}
@Composable
internal fun SettingsPage(state: CopierState, onLanguage: (String) -> Unit, onTheme: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 42.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Heading(stringResource(R.string.language), Glyph.LANGUAGE, textSize = 44)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BlueButton("العربية", { onLanguage("ar") }, Modifier.weight(1f), !state.busy)
            BlueButton("English", { onLanguage("en") }, Modifier.weight(1f), !state.busy)
        }
        Separator()
        Spacer(Modifier.height(14.dp))
        Heading(stringResource(R.string.appearance), Glyph.MOON, textSize = 44)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BlueButton(stringResource(R.string.light_mode), { onTheme("light") }, Modifier.weight(1f), !state.busy)
            BlueButton(stringResource(R.string.dark_mode), { onTheme("dark") }, Modifier.weight(1f), !state.busy)
        }
        Separator()
    }
}
@Composable
private fun FolderLabel(uri: String) {
    if (uri.isBlank()) Text(stringResource(R.string.folder_not_selected), modifier = Modifier.padding(vertical = 6.dp), textAlign = TextAlign.Center)
    else Literal(runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uri)) }.getOrDefault(uri))
}
@Composable
private fun Literal(value: String) {
    SelectionContainer {
        Text(value, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrLtr))
    }
}
private fun documentPath(value: String): String = runCatching { DocumentsContract.getDocumentId(Uri.parse(value)) }.getOrDefault(value)
@Composable
private fun bytes(value: Long): String = if (value < 0) stringResource(R.string.unavailable) else android.text.format.Formatter.formatFileSize(LocalContext.current, value)
private fun time(value: Long): String = "%d:%02d".format(Locale.ROOT, value / 60000, (value / 1000) % 60)
