package io.github.rhythmcache.vector.link

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================
// Kernel node definitions + root access
// ============================================================

object VectorNodes {
    const val DIR = "/sys/kernel/vector"
    const val BATT = "/sys/class/power_supply/battery"

    const val STOP = "charge_stop_pct"
    const val RESUME = "charge_resume_pct"
    const val LIMIT_STATE = "charge_limit_state"
    const val BYPASS = "bypass_charger"
    const val ICL = "usb_icl_override_ma"
    const val IGN_CHG = "ignore_vendor_charging_thermal"
    const val VENDOR_MAX = "vendor_thermal_max_level"
    const val IGN_CPU = "ignore_vendor_cpu_thermal"
    const val CPU_LITTLE = "cpu_little_override_max_state"
    const val CPU_BIG = "cpu_big_override_max_state"
    const val IGN_GPU = "ignore_vendor_gpu_thermal"
    const val GPU_MAX = "gpu_override_max_state"
    const val IGN_BL = "ignore_vendor_backlight_thermal"
    const val TRIP = "battery_trip_decic"
    const val CLEAR = "battery_clear_decic"
    const val SKIN = "skin_override_max_mc"

    val ALL = listOf(
        STOP, RESUME, LIMIT_STATE, BYPASS, ICL, IGN_CHG, VENDOR_MAX, IGN_CPU,
        CPU_LITTLE, CPU_BIG, IGN_GPU, GPU_MAX, IGN_BL, TRIP, CLEAR, SKIN
    )
}

data class Snapshot(
    val nodes: Map<String, Int>,
    val capacity: Int,
    val status: String,
    val currentUa: Long,
    val voltageUv: Long,
    val tempDeci: Int,
)

object VectorRepo {
    // One shell call reads everything.
    private val readScript = buildString {
        VectorNodes.ALL.forEach {
            append("echo \"n:$it=\$(cat ${VectorNodes.DIR}/$it 2>/dev/null)\"\n")
        }
        listOf("capacity", "status", "current_now", "voltage_now", "temp").forEach {
            append("echo \"b:$it=\$(cat ${VectorNodes.BATT}/$it 2>/dev/null)\"\n")
        }
    }

    suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) { Shell.getShell().isRoot }

    suspend fun hasNodes(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd("test -d ${VectorNodes.DIR}").exec().isSuccess
    }

    fun resetShell() {
        Shell.getCachedShell()?.close()
    }

    suspend fun read(): Snapshot = withContext(Dispatchers.IO) {
        val out = Shell.cmd(readScript).exec().out
        val nodes = HashMap<String, Int>()
        val batt = HashMap<String, String>()
        for (line in out) {
            val eq = line.indexOf('=')
            if (eq < 2) continue
            val key = line.substring(2, eq)
            val value = line.substring(eq + 1).trim()
            when {
                line.startsWith("n:") -> value.toIntOrNull()?.let { nodes[key] = it }
                line.startsWith("b:") -> batt[key] = value
            }
        }
        Snapshot(
            nodes = nodes,
            capacity = batt["capacity"]?.toIntOrNull() ?: 0,
            status = batt["status"].orEmpty(),
            currentUa = batt["current_now"]?.toLongOrNull() ?: 0L,
            voltageUv = batt["voltage_now"]?.toLongOrNull() ?: 0L,
            tempDeci = batt["temp"]?.toIntOrNull() ?: 0,
        )
    }

    suspend fun write(node: String, value: String): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd("echo \"$value\" > ${VectorNodes.DIR}/$node").exec().isSuccess
    }
}

// ============================================================
// ViewModel (race-free: shared mutex + pending-write grace period)
// ============================================================

enum class Gate { Loading, NoRoot, NoNodes, Ready }

data class UiState(
    val gate: Gate = Gate.Loading,
    val snap: Snapshot? = null,
    val error: String? = null,
)

private class Pending(val value: Int, val until: Long)

class VectorViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Reads and writes never interleave.
    private val mutex = Mutex()

    // Values we wrote but the kernel may not reflect yet. Only touched under the mutex.
    private val pending = HashMap<String, Pending>()

    suspend fun pollLoop() {
        while (true) {
            mutex.withLock { refreshLocked() }
            delay(1500)
        }
    }

    fun retry() {
        viewModelScope.launch {
            VectorRepo.resetShell()
            mutex.withLock {
                pending.clear()
                _state.update { UiState(Gate.Loading) }
                refreshLocked()
            }
        }
    }

    private suspend fun refreshLocked() {
        val gate = _state.value.gate
        if (gate == Gate.NoRoot) return
        if (gate != Gate.Ready && !VectorRepo.hasRoot()) {
            _state.update { it.copy(gate = Gate.NoRoot) }
            return
        }
        if (!VectorRepo.hasNodes()) {
            _state.update { it.copy(gate = Gate.NoNodes) }
            return
        }
        val snap = VectorRepo.read()

        // Overlay pending writes so a stale kernel value can't flip the UI back.
        val now = SystemClock.elapsedRealtime()
        val merged = snap.nodes.toMutableMap()
        val iter = pending.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            val actual = merged[e.key]
            if (actual == null || actual == e.value.value || now > e.value.until) {
                iter.remove() // confirmed, unreadable, or expired -> trust the kernel
            } else {
                merged[e.key] = e.value.value
            }
        }
        _state.update { it.copy(gate = Gate.Ready, snap = snap.copy(nodes = merged)) }
    }

    /** value may be "80 75" for the dual-value charge_stop_pct syntax. */
    fun set(node: String, value: String) {
        viewModelScope.launch {
            mutex.withLock {
                val first = value.trim().split(" ").firstOrNull()?.toIntOrNull()
                if (first != null) {
                    pending[node] = Pending(first, SystemClock.elapsedRealtime() + 3000)
                    // instant UI feedback
                    _state.update { s ->
                        s.snap?.let { s.copy(snap = it.copy(nodes = it.nodes + (node to first))) } ?: s
                    }
                }
                val ok = VectorRepo.write(node, value)
                if (!ok) pending.remove(node)
                _state.update { it.copy(error = if (ok) null else "Write failed: $node = $value") }
                refreshLocked()
            }
        }
    }

    fun set(node: String, value: Int) = set(node, value.toString())
    fun set(node: String, on: Boolean) = set(node, if (on) 1 else 0)
}

// ============================================================
// Activity + theme
// ============================================================

class MainActivity : ComponentActivity() {
    private val vm: VectorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

// ============================================================
// Dashboard
// ============================================================

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

// ============================================================
// Reusable rows
// ============================================================

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
    var local by remember { mutableFloatStateOf(value.toFloat()) }
    var dragging by remember { mutableStateOf(false) }

    // Follow the kernel only while the user isn't dragging.
    LaunchedEffect(value) { if (!dragging) local = value.toFloat() }

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(format(local.roundToInt()), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = local.coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { dragging = true; local = it },
            onValueChangeFinished = {
                dragging = false
                onCommit(local.roundToInt())
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
fun IntFieldRow(title: String, desc: String, value: Int, onCommit: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }

    // Don't overwrite what the user is typing.
    LaunchedEffect(value) { if (!focused) text = value.toString() }

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
                modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { text.toIntOrNull()?.let(onCommit) }) { Text("Set") }
        }
    }
}

// ============================================================
// Battery gauge (vertical battery with animated fluid level)
// ============================================================

@Composable
fun BatteryGauge(percent: Int, modifier: Modifier = Modifier) {
    val level by animateFloatAsState(percent.coerceIn(0, 100) / 100f, tween(800), label = "level")
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "phase",
    )
    val fluid = when {
        percent <= 15 -> Color(0xFFE53935)
        percent <= 30 -> Color(0xFFFFB300)
        else -> Color(0xFF2ECC71)
    }
    val outline = MaterialTheme.colorScheme.outline
    val track = MaterialTheme.colorScheme.surfaceVariant

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val capH = size.height * 0.05f
            val capW = size.width * 0.38f
            val bodyTop = capH
            val bodyH = size.height - capH
            val corner = CornerRadius(size.width * 0.16f)
            val stroke = 4.dp.toPx()

            // terminal cap
            drawRoundRect(
                color = outline,
                topLeft = Offset((size.width - capW) / 2f, 0f),
                size = Size(capW, capH + stroke),
                cornerRadius = CornerRadius(6f),
            )

            val body = RoundRect(Rect(0f, bodyTop, size.width, size.height), corner)
            clipPath(Path().apply { addRoundRect(body) }) {
                drawRect(track)
                val fillH = bodyH * level
                val top = size.height - fillH
                val amp = if (level in 0.02f..0.98f) 7.dp.toPx() else 0f
                val wave = Path().apply {
                    moveTo(0f, top)
                    var x = 0f
                    while (x <= size.width) {
                        val y = top + sin(x / size.width * 2f * PI.toFloat() * 1.4f + phase) * amp
                        lineTo(x, y)
                        x += 4f
                    }
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(wave, fluid)
            }
            drawRoundRect(
                color = outline,
                topLeft = Offset(0f, bodyTop),
                size = Size(size.width, bodyH),
                cornerRadius = corner,
                style = Stroke(stroke),
            )
        }
        Text(
            "$percent%",
            style = TextStyle(
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 8f),
            ),
        )
    }
}
