package app.surrealar.util.diagnostics.incident

import java.util.Locale

/**
 * App state at the moment a tester pressed "Mark Problem". Every field is nullable ("unknown" when
 * rendered). Holds no coordinates, point names or raw NMEA: only names of states, accuracy values,
 * ages, counts and booleans.
 */
data class IncidentContext(
    val gnssSource: String? = null,
    val receiverProfile: String? = null,
    val receiverLink: String? = null,
    val corrections: String? = null,
    val rtkStatus: String? = null,
    val fixAgeMs: Long? = null,
    val hAccM: Double? = null,
    val vAccM: Double? = null,
    val correctionAgeS: Double? = null,
    val arSessionActive: Boolean? = null,
    val arCameraTracking: String? = null,
    val arEarthTracking: String? = null,
    val arModelsInScene: Int? = null,
    val arModelsLoading: Int? = null,
    val freeStorageBytes: Long? = null,
    val cameraPermission: Boolean? = null,
    val fineLocationPermission: Boolean? = null,
    val notificationPermission: Boolean? = null,
) {
    /** Stable `key: value` lines in a fixed order; missing values are `unknown`. */
    fun renderLines(): List<String> = listOf(
        "gnssSource" to text(gnssSource),
        "receiverProfile" to text(receiverProfile),
        "receiverLink" to text(receiverLink),
        "corrections" to text(corrections),
        "rtkStatus" to text(rtkStatus),
        "fixAgeMs" to (fixAgeMs?.toString() ?: UNKNOWN),
        "hAccM" to num(hAccM, 3),
        "vAccM" to num(vAccM, 3),
        "correctionAgeS" to num(correctionAgeS, 1),
        "arSessionActive" to bool(arSessionActive),
        "arCameraTracking" to text(arCameraTracking),
        "arEarthTracking" to text(arEarthTracking),
        "arModelsInScene" to (arModelsInScene?.toString() ?: UNKNOWN),
        "arModelsLoading" to (arModelsLoading?.toString() ?: UNKNOWN),
        "freeStorageMb" to (freeStorageBytes?.let { (it / (1024 * 1024)).toString() } ?: UNKNOWN),
        "cameraPermission" to bool(cameraPermission),
        "fineLocationPermission" to bool(fineLocationPermission),
        "notificationPermission" to bool(notificationPermission),
    ).map { (k, v) -> "$k: $v" }

    companion object {
        const val UNKNOWN = "unknown"
        private const val MAX_TEXT = 64

        /** A number with 4+ decimals looks like a latitude/longitude; such runs are never rendered. */
        private val COORDINATE_LIKE = Regex("""-?\d+[.,]\d{4,}""")
        private val UNSAFE_CHARS = Regex("""[^A-Za-z0-9 _.:=<>/()\[\]+\-]""")

        /**
         * Makes a state name safe to render: first line only, cut at any `$` (an NMEA sentence start),
         * coordinate-like numbers replaced, whitespace collapsed, odd characters dropped, length
         * capped. Blank → null.
         */
        fun safeText(s: String?): String? = s
            ?.lineSequence()?.firstOrNull()
            ?.substringBefore('$')
            ?.replace(COORDINATE_LIKE, "[redacted]")
            ?.replace(Regex("""\s+"""), " ")
            ?.replace(UNSAFE_CHARS, "")
            ?.trim()
            ?.take(MAX_TEXT)
            ?.takeIf { it.isNotEmpty() }

        /** Replaces coordinate-like numbers in free text (e.g. a tester's note), keeping the rest. */
        fun redactCoordinates(s: String): String = s.replace(COORDINATE_LIKE, "[redacted]")

        private fun text(s: String?) = safeText(s) ?: UNKNOWN
        private fun bool(b: Boolean?) = b?.toString() ?: UNKNOWN
        private fun num(v: Double?, decimals: Int) =
            v?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: UNKNOWN
    }
}

/** Latest fix quality, no position. */
data class FixQuality(
    val rtkStatus: String?, val ageMs: Long?, val hAccM: Double?, val vAccM: Double?, val correctionAgeS: Double?,
)

/** AR state as last recorded by the AR screen (on change, not per frame). */
data class ArState(
    val sessionActive: Boolean?, val cameraTracking: String?, val earthTracking: String?,
    val modelsInScene: Int?, val modelsLoading: Int?,
)

data class PermissionState(val camera: Boolean?, val fineLocation: Boolean?, val notifications: Boolean?)

/** Where an [IncidentContext] reads from. Any call may return null or throw; the collector copes. */
interface IncidentContextInputs {
    fun gnssSource(): String?
    suspend fun receiverProfile(): String?
    fun receiverLink(): String?
    fun corrections(): String?
    fun fixQuality(): FixQuality?
    fun arState(): ArState?
    fun freeStorageBytes(): Long?
    fun permissions(): PermissionState?
}

/** Builds an [IncidentContext] from [IncidentContextInputs]; one failing input only blanks its own fields. */
object IncidentContextCollector {

    suspend fun collect(inputs: IncidentContextInputs): IncidentContext {
        val fix = attempt { inputs.fixQuality() }
        val ar = attempt { inputs.arState() }
        val perms = attempt { inputs.permissions() }
        return IncidentContext(
            gnssSource = IncidentContext.safeText(attempt { inputs.gnssSource() }),
            receiverProfile = IncidentContext.safeText(attemptSuspend { inputs.receiverProfile() }),
            receiverLink = IncidentContext.safeText(attempt { inputs.receiverLink() }),
            corrections = IncidentContext.safeText(attempt { inputs.corrections() }),
            rtkStatus = IncidentContext.safeText(fix?.rtkStatus),
            fixAgeMs = fix?.ageMs?.takeIf { it >= 0 },
            hAccM = fix?.hAccM?.takeIf { it.isFinite() },
            vAccM = fix?.vAccM?.takeIf { it.isFinite() },
            correctionAgeS = fix?.correctionAgeS?.takeIf { it.isFinite() },
            arSessionActive = ar?.sessionActive,
            arCameraTracking = IncidentContext.safeText(ar?.cameraTracking),
            arEarthTracking = IncidentContext.safeText(ar?.earthTracking),
            arModelsInScene = ar?.modelsInScene?.takeIf { it >= 0 },
            arModelsLoading = ar?.modelsLoading?.takeIf { it >= 0 },
            freeStorageBytes = attempt { inputs.freeStorageBytes() }?.takeIf { it >= 0 },
            cameraPermission = perms?.camera,
            fineLocationPermission = perms?.fineLocation,
            notificationPermission = perms?.notifications,
        )
    }

    private inline fun <T> attempt(block: () -> T?): T? = try { block() } catch (e: Exception) { null }

    private suspend fun <T> attemptSuspend(block: suspend () -> T?): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
