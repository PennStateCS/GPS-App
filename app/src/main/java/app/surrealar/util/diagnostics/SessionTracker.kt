package app.surrealar.util.diagnostics

import app.surrealar.util.BuildInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.surrealar.BuildConfig
import app.surrealar.util.DiagnosticsLogger
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * One SESSION_START line per process start, plus the "ended cleanly" marker.
 *
 * The marker is set when the session starts and whenever the app comes to the foreground, and cleared
 * when the app goes to the background (a normal stop). A crash, ANR or kill while the app is in front
 * leaves it set, so the next start reports `lastSessionClean=false`.
 */
class SessionTracker(private val prefs: SharedPreferences) {

    data class Info(
        val versionName: String, val versionCode: Int, val buildType: String, val gitHash: String,
        val device: String, val android: String, val sdk: Int, val locale: String, val timeZone: String,
    )

    data class Session(val sessionId: String, val installId: String, val lastClean: Boolean?, val line: String)

    /** Starts a session: creates the install ID once, reads and re-arms the marker, builds the line. */
    fun start(info: Info, newId: () -> String = { UUID.randomUUID().toString() }): Session {
        val installId = prefs.getString(KEY_INSTALL_ID, null) ?: newId().also {
            prefs.edit().putString(KEY_INSTALL_ID, it).apply()
        }
        val sessionId = newId().replace("-", "").take(12)
        val previous = prefs.getString(KEY_LAST_SESSION, null)
        val lastClean: Boolean? = if (previous == null) null else !prefs.getBoolean(KEY_OPEN, false)
        prefs.edit().putString(KEY_LAST_SESSION, sessionId).putBoolean(KEY_OPEN, true).commit()
        val line = "SESSION_START session=$sessionId install=$installId" +
            " version=${info.versionName} code=${info.versionCode} build=${info.buildType} commit=${info.gitHash}" +
            " device=\"${info.device}\" android=${info.android} sdk=${info.sdk}" +
            " locale=${info.locale} tz=${info.timeZone}" +
            " lastSessionClean=${lastClean?.toString() ?: "unknown(first run)"} previous=${previous ?: "none"}"
        return Session(sessionId, installId, lastClean, line)
    }

    fun markOpen() { prefs.edit().putBoolean(KEY_OPEN, true).apply() }
    fun markStopped() { prefs.edit().putBoolean(KEY_OPEN, false).apply() }

    companion object {
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_LAST_SESSION = "last_session_id"
        const val KEY_OPEN = "session_open"

        @Volatile var current: Session? = null
            private set

        fun installId(context: Context): String? =
            context.getSharedPreferences(ExitReasonRecorder.PREFS, Context.MODE_PRIVATE).getString(KEY_INSTALL_ID, null)

        /** Called once from Application.onCreate, right after DiagnosticsLogger.init. */
        fun startProcess(context: Context) {
            val app = context.applicationContext
            val tracker = SessionTracker(app.getSharedPreferences(ExitReasonRecorder.PREFS, Context.MODE_PRIVATE))
            val session = tracker.start(Info(
                versionName = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE,
                buildType = BuildConfig.BUILD_TYPE,
                gitHash = BuildInfo.gitHash + if (BuildInfo.gitDirty) "-dirty" else "",
                device = "${Build.MANUFACTURER} ${Build.MODEL}", android = Build.VERSION.RELEASE ?: "unknown",
                sdk = Build.VERSION.SDK_INT, locale = Locale.getDefault().toLanguageTag(),
                timeZone = TimeZone.getDefault().id,
            ))
            current = session
            DiagnosticsLogger.sessionId = session.sessionId
            if (session.lastClean == false) DiagnosticsLogger.w("APP", session.line) else DiagnosticsLogger.i("APP", session.line)
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = tracker.markOpen()
                override fun onStop(owner: LifecycleOwner) = tracker.markStopped()
            })
        }
    }
}