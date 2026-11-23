package app.surrealar.util.diagnostics.report

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * Failure helpers for the report. `runCatching` also catches [CancellationException], which would
 * keep a cancelled export running; these rethrow it instead.
 */
object ReportFailures {

    /** Longest exception message kept in the report (after redaction). */
    const val MAX_MESSAGE_CHARS = 160

    /**
     * "ExceptionType: short message" for the report: the message goes through [ReportRedactor.scrub],
     * is put on one line and capped at [MAX_MESSAGE_CHARS]. Never the raw message.
     */
    fun describe(t: Throwable): String {
        val type = t.javaClass.simpleName.ifEmpty { t.javaClass.name }
        val msg = t.message?.let { ReportRedactor.scrub(it).replace(Regex("\\s+"), " ").trim() }?.takeIf { it.isNotEmpty() }
            ?: return type
        return "$type: " + if (msg.length > MAX_MESSAGE_CHARS) msg.take(MAX_MESSAGE_CHARS) + "…" else msg
    }

    /**
     * Like `runCatching`, but a [CancellationException] is rethrown when this coroutine is cancelled.
     * A stray one (a nested timeout of the source itself) while still active counts as a failure.
     */
    suspend inline fun <T> catching(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        Result.failure(e)
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /** Non-suspending [catching]: always rethrows [CancellationException]. */
    inline fun <T> catchingBlocking(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
