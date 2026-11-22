package app.surrealar.util.diagnostics

import android.content.Context
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.surrealar.util.DiagnosticsLogger
import kotlin.coroutines.cancellation.CancellationException

/** DATA lines for imports/exports: start, done (rows, duration) or failed (reason). */
object DataOpLog {

    fun doneLine(op: String, format: String, rows: Int?, ms: Long) = "$op done format=$format rows=${rows ?: "?"} in ${ms}ms"
    fun failLine(op: String, format: String, ms: Long, e: Throwable) =
        "$op failed format=$format after ${ms}ms: ${e.javaClass.simpleName}: ${e.message}"

    inline fun <T> run(op: String, format: String, rows: (T) -> Int?, block: () -> T): T {
        val t0 = System.nanoTime()
        DiagnosticsLogger.i(DiagTags.DATA, "$op start format=$format")
        try {
            val r = block()
            DiagnosticsLogger.i(DiagTags.DATA, doneLine(op, format, rows(r), (System.nanoTime() - t0) / 1_000_000))
            return r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DiagnosticsLogger.w(DiagTags.DATA, failLine(op, format, (System.nanoTime() - t0) / 1_000_000, e))
            throw e
        }
    }
}

/**
 * DATA lines for the Room database: created, opened, migrated (from → to, time from build to first
 * open) and destructive resets. Logging only — it doesn't touch the schema or the migrations.
 */
object DbDiagnostics {
    private const val KEY_LAST_DB_VERSION = "last_db_version"

    fun openLine(previous: Int?, version: Int, ms: Long): String = when {
        previous == null -> "Database opened v$version (first open recorded, ${ms}ms after build)"
        previous != version -> "Database migrated v$previous → v$version (${ms}ms from build to open)"
        else -> "Database opened v$version (${ms}ms after build)"
    }

    fun callback(context: Context): RoomDatabase.Callback {
        val prefs = context.applicationContext.getSharedPreferences(ExitReasonRecorder.PREFS, Context.MODE_PRIVATE)
        val builtAt = System.nanoTime()
        return object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                DiagnosticsLogger.i(DiagTags.DATA, "Database created v${db.version}")
            }
            override fun onOpen(db: SupportSQLiteDatabase) {
                runCatching {
                    val prev = prefs.getInt(KEY_LAST_DB_VERSION, -1).takeIf { it >= 0 }
                    DiagnosticsLogger.i(DiagTags.DATA, openLine(prev, db.version, (System.nanoTime() - builtAt) / 1_000_000))
                    prefs.edit().putInt(KEY_LAST_DB_VERSION, db.version).apply()
                }
            }
            override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                DiagnosticsLogger.w(DiagTags.DATA, "Database reset by destructive migration (ALLOW_DESTRUCTIVE_MIGRATION) — saved data cleared")
            }
        }
    }

    fun lastRecordedVersion(context: Context): Int? = context.applicationContext
        .getSharedPreferences(ExitReasonRecorder.PREFS, Context.MODE_PRIVATE).getInt(KEY_LAST_DB_VERSION, -1).takeIf { it >= 0 }
}