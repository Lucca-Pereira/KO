package com.lucca.ko.ui.recipes

import android.content.Context
import android.media.RingtoneManager
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.lucca.ko.domain.ResolvedIngredient
import com.lucca.ko.domain.recipe.StepTimes
import com.lucca.ko.ui.common.KoTopBar
import java.util.Locale
import kotlinx.coroutines.delay

/** The step's text with any times in it picked out in the accent colour. */
@Composable
internal fun stepText(text: String): AnnotatedString {
    val color = MaterialTheme.colorScheme.primary
    return remember(text, color) {
        val times = StepTimes.find(text)
        buildAnnotatedString {
            var pos = 0
            times.forEach { t ->
                append(text.substring(pos, t.start))
                withStyle(
                    SpanStyle(color = color, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline),
                ) { append(text.substring(t.start, t.end)) }
                pos = t.end
            }
            append(text.substring(pos))
        }
    }
}

/**
 * One chip per time in the step; if the step has none in its text but a saved duration, one
 * chip for that. Tapping starts a timer.
 */
@Composable
internal fun TimerChips(step: CookStep, onStart: (String, Int) -> Unit) {
    val times = remember(step) {
        val found = StepTimes.find(step.text).map { it.label to it.seconds }
        found.ifEmpty { step.minutes?.takeIf { it > 0 }?.let { listOf("$it min" to it * 60) } ?: emptyList() }
    }
    if (times.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        times.forEach { (label, seconds) ->
            AssistChip(
                onClick = { onStart(label, seconds) },
                label = { Text("Timer · $label") },
                leadingIcon = { Icon(Icons.Filled.Timer, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

/**
 * Running timers, shown under the content. Counts down while the app is open (they are screen
 * state, not alarms), chimes once when one finishes, and keeps it listed until dismissed.
 */
@Composable
fun TimersBar(timers: List<CookTimer>, onCancel: (Int) -> Unit) {
    if (timers.isEmpty()) return
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val chimed = remember { mutableSetOf<Int>() }
    LaunchedEffect(timers) {
        while (true) {
            now = System.currentTimeMillis()
            timers.forEach { if (now >= it.endsAt && chimed.add(it.id)) chime(context) }
            delay(500)
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            timers.forEach { t ->
                val left = ((t.endsAt - now + 999) / 1000).toInt()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Timer, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        t.label,
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (left <= 0) "Done" else StepTimes.clock(left),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (left <= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = { onCancel(t.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss timer")
                    }
                }
            }
        }
    }
}

private fun chime(context: Context) {
    runCatching {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        RingtoneManager.getRingtone(context, uri)?.play()
    }
}

/** Speaks [text] whenever it changes while [enabled]; silent and engine-free otherwise. */
@Composable
private fun ReadAloud(enabled: Boolean, text: String) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.getDefault()
                ready = true
            }
        }
        tts = engine
        onDispose {
            engine?.stop()
            engine?.shutdown()
            tts = null
            ready = false
        }
    }
    LaunchedEffect(enabled, ready, text) {
        if (enabled && ready) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "step")
    }
}

/**
 * Full-screen cooking view: one big step at a time, previous/next, its timers, the ingredient
 * list one tap away, and an optional read-aloud. The caller keeps the screen awake.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookMode(
    title: String,
    steps: List<CookStep>,
    current: Int,
    ingredients: List<ResolvedIngredient>,
    scale: Double,
    crossed: Set<Long>,
    timers: List<CookTimer>,
    onStep: (Int) -> Unit,
    onToggleIngredient: (Long) -> Unit,
    onStartTimer: (String, Int) -> Unit,
    onCancelTimer: (Int) -> Unit,
    onClose: () -> Unit,
) {
    var showIngredients by remember { mutableStateOf(false) }
    var readAloud by remember { mutableStateOf(false) }
    val index = current.coerceIn(0, (steps.size - 1).coerceAtLeast(0))
    val step = steps.getOrNull(index)

    ReadAloud(readAloud, step?.text.orEmpty())

    if (showIngredients) {
        ModalBottomSheet(
            onDismissRequest = { showIngredients = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                item { Text("Ingredients", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }
                items(ingredients, key = { it.dishIngredientId }) { ing ->
                    IngredientRow(
                        ing = ing,
                        scale = scale,
                        crossed = ing.dishIngredientId in crossed,
                        onToggle = { onToggleIngredient(ing.dishIngredientId) },
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            KoTopBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Stop cooking") }
                },
                actions = {
                    IconButton(onClick = { readAloud = !readAloud }) {
                        Icon(
                            if (readAloud) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = if (readAloud) "Stop reading aloud" else "Read aloud",
                            tint = if (readAloud) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showIngredients = true }) {
                        Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = "Ingredients")
                    }
                },
            )
        },
        bottomBar = {
            Column {
                TimersBar(timers, onCancel = onCancelTimer)
                if (steps.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            IconButton(onClick = { onStep(index - 1) }, enabled = index > 0) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous step")
                            }
                            Text(
                                "Step ${index + 1} of ${steps.size}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (index < steps.lastIndex) {
                                IconButton(onClick = { onStep(index + 1) }) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next step")
                                }
                            } else {
                                TextButton(onClick = onClose) { Text("Done") }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (step == null) {
                Text(
                    "This recipe has no directions yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Step ${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(stepText(step.text), style = MaterialTheme.typography.headlineSmall)
                TimerChips(step, onStartTimer)
            }
        }
    }
}
