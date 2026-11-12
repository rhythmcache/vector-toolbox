package io.github.rhythmcache.vector.link

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val vm: VectorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Sync from kernel continuously while the app is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.pollLoop() }
        }
        setContent { VectorTheme { App(vm) } }
    }
}

@Composable
fun VectorTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(ctx) else darkColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(vm: VectorViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (s.gate) {
                Gate.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                Gate.NoRoot -> Message(
                    "Root access required",
                    "Grant root to this app in your root manager (KernelSU / Magisk), then retry.",
                    vm::retry,
                )
                Gate.NoNodes -> Message(
                    "Vector kernel not detected",
                    "${VectorNodes.DIR} does not exist. This app needs a Vector kernel that exposes these nodes.",
                    vm::retry,
                )
                Gate.Ready -> s.snap?.let { Dashboard(it, s.error, vm) }
            }
        }
    }
}

@Composable
fun Message(title: String, body: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Text(body, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
fun Dashboard(snap: Snapshot, error: String?, vm: VectorViewModel) {
    val n = snap.nodes
    fun has(k: String) = k in n
    fun on(k: String) = (n[k] ?: 0) != 0

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(snap) }

        if (error != null) item {
            Text(error, color = MaterialTheme.colorScheme.error)
        }

        // ---------- Charge limiter ----------
        if (has(VectorNodes.STOP)) item {
            Section("Charge limiter") {
                val stop = n[VectorNodes.STOP] ?: 0
                val resume = n[VectorNodes.RESUME] ?: 0
                ToggleRow("Enable charge limit", "Stop charging at a set level (0 = disabled)", stop > 0) {
                    vm.set(VectorNodes.STOP, if (it) "80 75" else "0")
                }
                if (stop > 0) {
                    SliderRow("Stop at", stop, 50..100, { "$it%" }) { s ->
                        val r = if (resume in 1 until s) resume else (s - 5).coerceAtLeast(1)
                        vm.set(VectorNodes.STOP, "$s $r")
                    }
                    SliderRow("Resume at", resume.coerceIn(1, stop - 1), 1..(stop - 1), { "$it%" }) { r ->
                        vm.set(VectorNodes.STOP, "$stop $r")
                    }
                }
                if (has(VectorNodes.LIMIT_STATE)) {
                    val label = when (n[VectorNodes.LIMIT_STATE]) {
                        0 -> "Idle"; 1 -> "Charging"; 2 -> "Holding (waiting to resume)"; else -> "Unknown"
                    }
                    Text("Limiter state: $label", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ---------- Charger ----------
        if (has(VectorNodes.BYPASS) || has(VectorNodes.ICL)) item {
            Section("Charger") {
                if (has(VectorNodes.BYPASS)) ToggleRow(
                    "Bypass charging",
                    "Power the phone from USB and keep the battery idle. Auto-clears on unplug or below 10%.",
                    on(VectorNodes.BYPASS),
                ) { vm.set(VectorNodes.BYPASS, it) }
                if (has(VectorNodes.ICL)) SliderRow(
                    "USB input current override", n[VectorNodes.ICL] ?: 0, 0..3000,
                    { if (it == 0) "Stock" else "$it mA" },
                ) { vm.set(VectorNodes.ICL, it) }
            }
        }

        // ---------- Charging thermal ----------
        if (has(VectorNodes.IGN_CHG)) item {
            Section("Charging thermal") {
                ToggleRow(
                    "Ignore vendor charging thermal",
                    "Bypass mi_thermald current mitigation while the battery is below the trip temperature",
                    on(VectorNodes.IGN_CHG),
                ) { vm.set(VectorNodes.IGN_CHG, it) }
                if (has(VectorNodes.VENDOR_MAX)) SliderRow(
                    "Max mitigation level (override)", n[VectorNodes.VENDOR_MAX] ?: 18, 0..24,
                    { if (it == 0) "0 (ignore all)" else "$it" },
                ) { vm.set(VectorNodes.VENDOR_MAX, it) }
            }
        }

        // ---------- CPU / GPU / display ----------
        item {
            Section("CPU / GPU / Display") {
                if (has(VectorNodes.IGN_CPU)) ToggleRow(
                    "Ignore vendor CPU thermal", "Stop mi_thermald from throttling CPU clocks",
                    on(VectorNodes.IGN_CPU),
                ) { vm.set(VectorNodes.IGN_CPU, it) }
                if (has(VectorNodes.CPU_LITTLE)) IntFieldRow(
                    "Little cluster max cooling state", "0 = no cap", n[VectorNodes.CPU_LITTLE] ?: 0,
                ) { vm.set(VectorNodes.CPU_LITTLE, it) }
                if (has(VectorNodes.CPU_BIG)) IntFieldRow(
                    "Big cluster max cooling state", "0 = no cap", n[VectorNodes.CPU_BIG] ?: 0,
                ) { vm.set(VectorNodes.CPU_BIG, it) }

                if (has(VectorNodes.IGN_GPU)) ToggleRow(
                    "Ignore vendor GPU thermal", "Stop mi_thermald from downclocking the GPU",
                    on(VectorNodes.IGN_GPU),
                ) { vm.set(VectorNodes.IGN_GPU, it) }
                if (has(VectorNodes.GPU_MAX)) IntFieldRow(
                    "GPU max cooling state", "0 = no cap", n[VectorNodes.GPU_MAX] ?: 0,
                ) { vm.set(VectorNodes.GPU_MAX, it) }

                if (has(VectorNodes.IGN_BL)) ToggleRow(
                    "Ignore vendor backlight thermal", "Stop mi_thermald from dimming the display",
                    on(VectorNodes.IGN_BL),
                ) { vm.set(VectorNodes.IGN_BL, it) }
            }
        }

        // ---------- Guardrails ----------
        if (has(VectorNodes.TRIP) || has(VectorNodes.SKIN)) item {
            Section("Safety guardrails") {
                val trip = n[VectorNodes.TRIP] ?: 450
                if (has(VectorNodes.TRIP)) SliderRow(
                    "Battery trip temperature", trip, 400..470, { "%.1f °C".format(it / 10f) },
                ) { vm.set(VectorNodes.TRIP, it) }
                if (has(VectorNodes.CLEAR)) {
                    val max = trip - 20
                    SliderRow(
                        "Battery clear temperature", (n[VectorNodes.CLEAR] ?: 420).coerceAtMost(max),
                        300..max, { "%.1f °C".format(it / 10f) },
                    ) { vm.set(VectorNodes.CLEAR, it) }
                }
                if (has(VectorNodes.SKIN)) {
                    val skin = n[VectorNodes.SKIN] ?: 0
                    ToggleRow("Skin temperature guardrail", "Revoke overrides when chassis gets too hot", skin > 0) {
                        vm.set(VectorNodes.SKIN, if (it) 50000 else 0)
                    }
                    if (skin > 0) SliderRow(
                        "Skin limit", skin.coerceIn(45000, 60000), 45000..60000,
                        { "%.1f °C".format(it / 1000f) },
                    ) { vm.set(VectorNodes.SKIN, (it / 500) * 500) }
                }
            }
        }
    }
}

@Composable
fun Header(snap: Snapshot) {
    val charging = snap.status.equals("Charging", ignoreCase = true)
    val mA = abs(snap.currentUa) / 1000
    val watts = abs(snap.currentUa) / 1e6 * (snap.voltageUv / 1e6)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BatteryGauge(snap.capacity, Modifier.width(110.dp).height(200.dp))
        Spacer(Modifier.width(24.dp))
        Column {
            Text(
                if (charging) "Charging speed" else "Current draw",
                style = MaterialTheme.typography.labelLarge,
            )
            Text("$mA mA", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text("%.1f W".format(watts), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(snap.status.ifEmpty { "Unknown" }, style = MaterialTheme.typography.bodyMedium)
            Text(
                "%.2f V  ·  %.1f °C".format(snap.voltageUv / 1e6, snap.tempDeci / 10f),
                style = MaterialTheme.typography.bodyMedium,
            )
            if ((snap.nodes[VectorNodes.BYPASS] ?: 0) == 1) {
                Spacer(Modifier.height(4.dp))
                AssistChip(onClick = {}, label = { Text("Bypass active") })
            }
        }
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun ToggleRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SliderRow(
    title: String,
    value: Int,
    range: IntRange,
    format: (Int) -> String,
    onCommit: (Int) -> Unit,
) {
    // Local value so dragging isn't fought by the kernel poll; resets when the kernel value changes.
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(format(local.roundToInt()), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
fun IntFieldRow(title: String, desc: String, value: Int, onCommit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { text.toIntOrNull()?.let(onCommit) }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { text.toIntOrNull()?.let(onCommit) }) { Text("Set") }
        }
    }
}