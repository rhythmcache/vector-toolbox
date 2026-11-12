package io.github.rhythmcache.vector.link

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    // One shell invocation reads everything -> cheap enough to poll every second or so.
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
            if (eq < 0) continue
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