package app.surrealar.util.diagnostics.report

/** Live GNSS / receiver state at export time (no coordinates). */
data class LiveState(
    val source: String?,
    val rtk: String?,
    val receiverLink: String?,
    val correctionAgeS: Double?,
    val corrections: String?,
    val freeStorageBytes: Long?,
)