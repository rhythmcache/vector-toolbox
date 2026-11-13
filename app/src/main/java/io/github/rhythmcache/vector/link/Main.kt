package io.github.rhythmcache.vector.link

import android.content.Context
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
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
                // take the first token in case a node ever prints "80 75"
                line.startsWith("n:") ->
                    value.split(" ").firstOrNull()?.toIntOrNull()?.let { nodes[key] = it }
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

    fun dismissError() = _state.update { it.copy(error = null) }

    private suspend fun refreshLocked() {
        val gate = _state.value.gate
        if (gate == Gate.NoRoot) return
        if (gate != Gate.Ready && !VectorRepo.hasRoot()) {
            _state.update { it.copy(gate = Gate.NoRoot) }
            return
        }
        val snap = VectorRepo.read()
        if (snap.nodes.isEmpty()) {
            // Nothing readable under /sys/kernel/vector. Keeps retrying on every poll.
            _state.update { it.copy(gate = Gate.NoNodes, snap = null) }
            return
        }

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

    private fun write(node: String, value: String, expected: Map<String, Int>) {
        viewModelScope.launch {
            mutex.withLock {
                val until = SystemClock.elapsedRealtime() + 3000
                expected.forEach { (k, v) -> pending[k] = Pending(v, until) }
                // instant UI feedback
                _state.update { s ->
                    s.snap?.let { s.copy(snap = it.copy(nodes = it.nodes + expected)) } ?: s
                }
                val ok = VectorRepo.write(node, value)
                if (!ok) expected.keys.forEach { pending.remove(it) }
                _state.update {
                    it.copy(
                        error = if (ok) null
                        else "Couldn't write \"$value\" to $node. The kernel may have rejected the value."
                    )
                }
                refreshLocked()
            }
        }
    }

    /** value may be "80 75" for the dual-value syntax; only the first number is tracked. */
    fun set(node: String, value: String) {
        val first = value.trim().split(" ").firstOrNull()?.toIntOrNull()
        write(node, value, if (first != null) mapOf(node to first) else emptyMap())
    }

    fun set(node: String, value: Int) = set(node, value.toString())
    fun set(node: String, on: Boolean) = set(node, if (on) 1 else 0)

    /** Writes stop + resume together and tracks both so neither slider snaps back. */
    fun setLimit(stop: Int, resume: Int) = write(
        VectorNodes.STOP, "$stop $resume",
        mapOf(VectorNodes.STOP to stop, VectorNodes.RESUME to resume),
    )
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

// Largest system font scale the app will follow. Delete the CompositionLocalProvider
// below if you want full system font scaling.
private const val MAX_FONT_SCALE = 1.3f

@Composable
fun VectorTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(ctx) else darkColorScheme()
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(d.density, d.fontScale.coerceAtMost(MAX_FONT_SCALE))
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

// ============================================================
// Safety disclaimer + inline help
// ============================================================

private const val PREFS_NAME = "vector_toolbox"
private const val PREF_DISCLAIMER = "disclaimer_accepted"

private val DISCLAIMER_TEXT = """
    This app writes directly to your kernel's charging and thermal controls. Wrong values can overheat the phone, damage the battery, cause random shutdowns, or shorten battery life.

    Thermal overrides are especially risky. Xiaomi's mi_thermald normally keeps the outside of the phone comfortable; bypassing it lets the device run hotter. The kernel's JEITA limits and the battery trip temperature are safety nets, not a license to ignore heat.

    You are fully responsible for every value you change. Don't leave the phone unattended while charging with overrides enabled, don't raise the trip temperature casually, and turn an override off if the device gets unusually hot.

    Settings are not saved by the kernel: everything returns to its default after a reboot.

    The developer is not liable for any damage, data loss, fire, injury, or warranty issues caused by using this app. Use at your own risk.

    Tap the ⓘ icon next to any setting to see what it does and an example.
""".trimIndent()

data class HelpInfo(
    val title: String,
    val body: String,
    val example: String? = null,
)

@Composable
fun HelpButton(info: HelpInfo, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.size(36.dp)) {
        Icon(
            Icons.Filled.Info,
            contentDescription = "Help: ${info.title}",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
    if (open) HelpDialog(info) { open = false }
}

/** Title that wraps if needed, with the help button always visible right after it. */
@Composable
fun LabelWithHelp(
    text: String,
    help: HelpInfo?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = style, modifier = Modifier.weight(1f, fill = false))
        if (help != null) {
            Spacer(Modifier.width(4.dp))
            HelpButton(help)
        }
    }
}

@Composable
fun HelpDialog(info: HelpInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(info.title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(info.body, style = MaterialTheme.typography.bodyMedium)
                if (info.example != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "Example",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(info.example, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}

@Composable
fun DisclaimerDialog(onClose: (dontShowAgain: Boolean) -> Unit) {
    var dontShowAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onClose(dontShowAgain) },
        title = { Text("⚠ Read before changing anything") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(DISCLAIMER_TEXT, style = MaterialTheme.typography.bodyMedium)
                Row(
                    Modifier.fillMaxWidth().clickable { dontShowAgain = !dontShowAgain },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                    Text("Don't show this warning again")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onClose(dontShowAgain) }) { Text("I understand") }
        },
    )
}

@Composable
fun App(vm: VectorViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var showDisclaimer by remember {
        mutableStateOf(!prefs.getBoolean(PREF_DISCLAIMER, false))
    }
    val s by vm.state.collectAsStateWithLifecycle()

    if (showDisclaimer) {
        DisclaimerDialog { dontShowAgain ->
            if (dontShowAgain) {
                prefs.edit().putBoolean(PREF_DISCLAIMER, true).apply()
            }
            showDisclaimer = false
        }
    }

    Scaffold { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (s.gate) {
                Gate.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                Gate.NoRoot -> Message(
                    "Root access required",
                    "Grant root to this app in your root manager (KernelSU / Magisk), then tap Retry.",
                    vm::retry,
                )
                Gate.NoNodes -> Message(
                    "Vector kernel not detected",
                    "Nothing could be read from ${VectorNodes.DIR}. This app only works on a Vector kernel that exposes these controls.",
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
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Vector Link",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(4.dp))
                HelpButton(HelpInfo("Safety notes", DISCLAIMER_TEXT))
            }
        }
        item { Header(snap) }

        if (error != null) item {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::dismissError) { Text("Dismiss") }
                }
            }
        }

        // ---------- Charge limiter ----------
        if (has(VectorNodes.STOP)) item {
            Section("Charge limiter") {
                val stop = n[VectorNodes.STOP] ?: 0
                val resume = n[VectorNodes.RESUME] ?: 0
                ToggleRow(
                    "Charge limit",
                    "Pause charging at a chosen battery level",
                    stop > 0,
                    help = HelpInfo(
                        "Charge limit",
                        "Stops charging at a chosen battery percentage instead of always filling to 100%. Keeping a lithium battery around 50–80% reduces wear during long plugged-in sessions, but you lose some runtime each day.\n\nTurning it on starts with 80% / 75%. You can adjust both below.",
                        "Stop at 80%, resume at 75%: charging halts at 80% and starts again after the battery falls to 75%.",
                    ),
                ) {
                    if (it) vm.setLimit(80, 75) else vm.set(VectorNodes.STOP, 0)
                }
                if (stop > 0) {
                    SliderRow(
                        "Stop charging at", stop, 50..100, { "$it%" },
                        help = HelpInfo(
                            "Stop charging at",
                            "Battery percentage where charging stops. The kernel then holds the charge and waits for the battery to drop to the resume level. The kernel only accepts 50% to 100%.",
                            "80% keeps the battery near 75–80% overnight instead of sitting at 100%.",
                        ),
                    ) { s ->
                        val r = if (resume in 1 until s) resume else (s - 5).coerceAtLeast(1)
                        vm.setLimit(s, r)
                    }
                    SliderRow(
                        "Resume charging at", resume.coerceIn(1, stop - 1), 1..(stop - 1), { "$it%" },
                        help = HelpInfo(
                            "Resume charging at",
                            "Battery percentage where charging starts again after the limiter stopped it. It must be lower than the stop level. The gap between the two stops the phone from toggling charging on and off every few seconds.",
                            "Stop 80% / resume 75%: after reaching 80%, the phone waits until 75% before charging again.",
                        ),
                    ) { r ->
                        vm.setLimit(stop, r)
                    }
                }
                if (has(VectorNodes.LIMIT_STATE)) {
                    val label = when (n[VectorNodes.LIMIT_STATE]) {
                        0 -> "Idle (off or unplugged)"
                        1 -> "Charging"
                        2 -> "Holding at limit, waiting to resume"
                        else -> "Unknown"
                    }
                    LabelWithHelp(
                        "Limiter status: $label",
                        HelpInfo(
                            "Limiter status",
                            "Read-only status from the kernel.\n\n• Idle: the limiter is off or the cable is unplugged.\n• Charging: the battery is below the stop level.\n• Holding: the stop level was reached and charging is paused until the battery falls to the resume level.",
                            "Holding while plugged in at 80% is normal: the phone runs from USB power and waits to resume at 75%.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // ---------- Charger ----------
        if (has(VectorNodes.BYPASS) || has(VectorNodes.ICL)) item {
            Section("Charger") {
                if (has(VectorNodes.BYPASS)) ToggleRow(
                    "Bypass charging",
                    "Run the phone from USB power and leave the battery idle. Turns off by itself when unplugged or below 10%.",
                    on(VectorNodes.BYPASS),
                    help = HelpInfo(
                        "Bypass charging",
                        "Runs the phone directly from the charger's USB power while the battery stays idle instead of charging. This reduces battery heat and wear during long gaming or plugged-in sessions. The kernel switches bypass off automatically when the cable is unplugged or the battery drops below 10%.",
                        "Enable while gaming on a charger: the phone keeps running, but the battery stops heating up from charge current.",
                    ),
                ) { vm.set(VectorNodes.BYPASS, it) }
                if (has(VectorNodes.ICL)) SliderRow(
                    "USB input current limit", n[VectorNodes.ICL] ?: 0, 0..3000,
                    { if (it == 0) "Stock" else "$it mA" },
                    desc = "Raise this if a charger is stuck at 500 mA. Stock = no override.",
                    step = 100,
                    help = HelpInfo(
                        "USB input current limit",
                        "Overrides the stock input-current cap. Use this when a proper charger or cable is wrongly detected as a low-power USB port and only draws 500 mA. \"Stock\" (0) keeps the normal behavior. Higher values let the phone pull more current, but the charger, cable and port must actually support it.",
                        "A 5V/2A charger stuck at 500 mA can be raised to about 1500 mA. 3000 mA on a weak charger can cause overheating, voltage drops or unstable charging.",
                    ),
                ) { vm.set(VectorNodes.ICL, it) }
            }
        }

        // ---------- Charging thermal ----------
        if (has(VectorNodes.IGN_CHG)) item {
            Section("Charging throttle") {
                ToggleRow(
                    "Ignore vendor charging thermal",
                    "Don't let Xiaomi's thermal daemon slow charging because the frame feels warm",
                    on(VectorNodes.IGN_CHG),
                    help = HelpInfo(
                        "Ignore vendor charging thermal",
                        "Stops Xiaomi's mi_thermald daemon from cutting the charging current when the phone frame (skin) gets warm. The kernel's JEITA temperature limits and the battery trip temperature still apply, so hardware safety stays active. Enable it only if charging keeps dropping to a few watts while the actual battery is still cool.",
                        "If fast charging falls to ~5W at 36 °C battery because the frame is warm, enabling this keeps full speed until the trip temperature is reached.",
                    ),
                ) { vm.set(VectorNodes.IGN_CHG, it) }
                if (has(VectorNodes.VENDOR_MAX)) SliderRow(
                    "Throttle ceiling", n[VectorNodes.VENDOR_MAX] ?: 18, 0..24,
                    { if (it == 0) "0 (none)" else "$it" },
                    desc = "Highest vendor throttle step still obeyed. Lower = faster charging. Only active when the toggle above is on.",
                    help = HelpInfo(
                        "Throttle ceiling",
                        "Xiaomi's daemon requests throttle steps from 0 (none) to 24 (strongest). While the toggle above is on, the kernel applies min(requested step, this ceiling), so the ceiling caps how hard the vendor can throttle charging. 0 ignores vendor throttling completely; the default 18 allows up to about 3.5 A. It has no effect while the toggle is off.",
                        "Set 0 for maximum charging speed. Set 12 to let the vendor throttle charging, but never beyond step 12.",
                    ),
                ) { vm.set(VectorNodes.VENDOR_MAX, it) }
            }
        }

        // ---------- CPU / GPU / display ----------
        val perfNodes = listOf(
            VectorNodes.IGN_CPU, VectorNodes.CPU_LITTLE, VectorNodes.CPU_BIG,
            VectorNodes.IGN_GPU, VectorNodes.GPU_MAX, VectorNodes.IGN_BL,
        )
        if (perfNodes.any { has(it) }) item {
            Section("CPU, GPU & display") {
                if (has(VectorNodes.IGN_CPU)) ToggleRow(
                    "Ignore vendor CPU thermal",
                    "Don't let Xiaomi's thermal daemon lower CPU clocks because the frame feels warm",
                    on(VectorNodes.IGN_CPU),
                    help = HelpInfo(
                        "Ignore vendor CPU thermal",
                        "Prevents mi_thermald from capping CPU clock speeds just because the phone's exterior is warm. The kernel's own protection stays active: all overrides are revoked when the battery reaches the trip temperature.",
                        "Enable for sustained gaming or heavy apps when the phone feels warm but the battery is still below about 40 °C.",
                    ),
                ) { vm.set(VectorNodes.IGN_CPU, it) }
                if (has(VectorNodes.CPU_LITTLE)) IntFieldRow(
                    "Little cores: throttle limit",
                    "0 = no throttling (full speed). Applies while the CPU override is on.",
                    n[VectorNodes.CPU_LITTLE] ?: 0,
                    help = HelpInfo(
                        "Little cores: throttle limit",
                        "The highest kernel cooling state allowed for the Cortex-A55 little cores while the CPU override is on. 0 means no cap and the full 1.80 GHz stays available. Larger numbers allow stronger throttling.",
                        "0 = full speed. 5 = the kernel may throttle the little cluster up to cooling state 5.",
                    ),
                ) { vm.set(VectorNodes.CPU_LITTLE, it) }
                if (has(VectorNodes.CPU_BIG)) IntFieldRow(
                    "Big cores: throttle limit",
                    "0 = no throttling (full speed). Applies while the CPU override is on.",
                    n[VectorNodes.CPU_BIG] ?: 0,
                    help = HelpInfo(
                        "Big cores: throttle limit",
                        "The highest kernel cooling state allowed for the Cortex-A76 big cores while the CPU override is on. 0 means no cap and the full 2.21 GHz stays available. Larger numbers allow stronger throttling.",
                        "0 = full speed. 5 = the kernel may throttle the big cluster up to cooling state 5.",
                    ),
                ) { vm.set(VectorNodes.CPU_BIG, it) }

                if (has(VectorNodes.IGN_GPU)) ToggleRow(
                    "Ignore vendor GPU thermal",
                    "Don't let Xiaomi's thermal daemon lower GPU clocks because the frame feels warm",
                    on(VectorNodes.IGN_GPU),
                    help = HelpInfo(
                        "Ignore vendor GPU thermal",
                        "Prevents mi_thermald from downclocking the Adreno GPU because the phone frame is warm. The kernel still revokes this override at the battery trip temperature.",
                        "Useful for games that start stuttering after a few minutes even though the battery temperature is still moderate.",
                    ),
                ) { vm.set(VectorNodes.IGN_GPU, it) }
                if (has(VectorNodes.GPU_MAX)) IntFieldRow(
                    "GPU: throttle limit",
                    "0 = no throttling (full speed). Applies while the GPU override is on.",
                    n[VectorNodes.GPU_MAX] ?: 0,
                    help = HelpInfo(
                        "GPU: throttle limit",
                        "The highest GPU cooling state allowed while the GPU override is on. 0 means no cap and unthrottled GPU frequencies. Larger numbers allow more aggressive GPU throttling.",
                        "0 = no GPU cap. 4 = the kernel may throttle the GPU up to state 4.",
                    ),
                ) { vm.set(VectorNodes.GPU_MAX, it) }

                if (has(VectorNodes.IGN_BL)) ToggleRow(
                    "Ignore vendor backlight thermal",
                    "Don't let Xiaomi's thermal daemon dim the screen because the frame feels warm",
                    on(VectorNodes.IGN_BL),
                    help = HelpInfo(
                        "Ignore vendor backlight thermal",
                        "Stops mi_thermald from dimming the screen when the chassis gets warm. The display adds heat at high brightness, so expect the phone to run a bit warmer.",
                        "Enable if the screen suddenly dims during navigation or gaming even with auto-brightness off.",
                    ),
                ) { vm.set(VectorNodes.IGN_BL, it) }
            }
        }

        // ---------- Guardrails ----------
        if (has(VectorNodes.TRIP) || has(VectorNodes.SKIN)) item {
            Section(
                "Safety guardrails",
                subtitle = "When a limit is reached, every override above switches off automatically.",
            ) {
                val trip = n[VectorNodes.TRIP] ?: 450
                if (has(VectorNodes.TRIP)) SliderRow(
                    "Battery trip temperature", trip, 400..470, { "%.1f °C".format(it / 10f) },
                    desc = "Overrides switch off at this battery temperature. Default 45.0 °C.",
                    step = 5,
                    help = HelpInfo(
                        "Battery trip temperature",
                        "Hard safety tripwire based on the real battery cell temperature from the fuel gauge. When it's reached, every thermal override is cancelled immediately and stock throttling is restored. Default is 45.0 °C; allowed range is 40.0–47.0 °C. Lower is safer.",
                        "43.0 °C gives an extra safety margin while gaming; 45.0 °C is the default.",
                    ),
                ) { vm.set(VectorNodes.TRIP, it) }
                if (has(VectorNodes.CLEAR)) {
                    val max = trip - 20
                    SliderRow(
                        "Battery re-enable temperature",
                        (n[VectorNodes.CLEAR] ?: 420).coerceAtMost(max),
                        300..max, { "%.1f °C".format(it / 10f) },
                        desc = "After a trip, overrides come back once the battery cools below this. Must be at least 2.0 °C under the trip temperature.",
                        step = 5,
                        help = HelpInfo(
                            "Battery re-enable temperature",
                            "After a trip, overrides stay off until the battery cools below this temperature. It must be at least 2.0 °C below the trip temperature, which stops the phone from rapidly switching between overridden and stock thermal states. Default is 42.0 °C.",
                            "Trip 45.0 °C / re-enable 42.0 °C: overrides return only after a 3 °C cool-down.",
                        ),
                    ) { vm.set(VectorNodes.CLEAR, it) }
                }
                if (has(VectorNodes.SKIN)) {
                    val skin = n[VectorNodes.SKIN] ?: 0
                    ToggleRow(
                        "Frame temperature limit",
                        "Also switch overrides off when the phone's frame gets too hot",
                        skin > 0,
                        help = HelpInfo(
                            "Frame temperature limit",
                            "Optional extra guardrail that watches the chassis (skin) thermistor. If the frame reaches the chosen temperature, all thermal overrides are revoked, and they return after it cools by 2 °C. Off by default. This protects your hands and the exterior, not the battery chemistry.",
                            "Set 50 °C so overrides switch off if the frame becomes uncomfortably hot to hold.",
                        ),
                    ) {
                        vm.set(VectorNodes.SKIN, if (it) 50000 else 0)
                    }
                    if (skin > 0) SliderRow(
                        "Frame limit", skin.coerceIn(45000, 60000), 45000..60000,
                        { "%.1f °C".format(it / 1000f) },
                        step = 500,
                        help = HelpInfo(
                            "Frame limit",
                            "Chassis temperature where the guardrail revokes overrides. Range 45.0–60.0 °C, in 0.5 °C steps.",
                            "50.0 °C is a reasonable starting point; 45.0 °C is very conservative.",
                        ),
                    ) { vm.set(VectorNodes.SKIN, it) }
                }
            }
        }

        item {
            Text(
                "Changes apply immediately and reset to kernel defaults after a reboot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun Header(snap: Snapshot) {
    val label = when {
        snap.status.equals("Charging", ignoreCase = true) -> "Charging current"
        snap.status.equals("Discharging", ignoreCase = true) -> "Discharge current"
        else -> "Battery current"
    }
    val mA = abs(snap.currentUa) / 1000
    val watts = abs(snap.currentUa) / 1e6 * (snap.voltageUv / 1e6)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BatteryGauge(snap.capacity, Modifier.width(110.dp).height(200.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                "$mA mA",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                "≈ %.1f W at the battery".format(watts),
                style = MaterialTheme.typography.titleSmall,
            )
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
fun Section(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    desc: String,
    checked: Boolean,
    help: HelpInfo? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            LabelWithHelp(title, help)
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
    desc: String? = null,
    step: Int = 1,
    help: HelpInfo? = null,
    onCommit: (Int) -> Unit,
) {
    var local by remember { mutableFloatStateOf(value.toFloat()) }
    var dragging by remember { mutableStateOf(false) }

    // Follow the kernel only while the user isn't dragging.
    LaunchedEffect(value) { if (!dragging) local = value.toFloat() }

    // Snap to the step grid (only when the range divides evenly).
    fun snap(v: Float): Int {
        val r = v.roundToInt().coerceIn(range.first, range.last)
        if (step <= 1 || (range.last - range.first) % step != 0) return r
        val k = ((r - range.first).toFloat() / step).roundToInt()
        return (range.first + k * step).coerceIn(range.first, range.last)
    }

    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LabelWithHelp(title, help, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(
                format(local.roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (desc != null) {
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = local.coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { dragging = true; local = snap(it).toFloat() },
            onValueChangeFinished = {
                dragging = false
                onCommit(local.roundToInt())
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
fun IntFieldRow(
    title: String,
    desc: String,
    value: Int,
    help: HelpInfo? = null,
    onCommit: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }

    // Don't overwrite what the user is typing.
    LaunchedEffect(value) { if (!focused) text = value.toString() }

    Column {
        LabelWithHelp(title, help)
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
