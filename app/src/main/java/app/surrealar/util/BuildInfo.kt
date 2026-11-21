package app.surrealar.util

import android.content.Context
import app.surrealar.R

/**
 * Build metadata (git hash/branch/dirty, build time and number). These are string resources, not
 * BuildConfig fields, so a new build time doesn't make Kotlin recompile everything that reads them.
 * Read once at startup by [init] (Application.onCreate); "unknown" until then.
 */
object BuildInfo {
    @Volatile var gitHash: String = "unknown"; private set
    @Volatile var gitBranch: String = "unknown"; private set
    @Volatile var gitDirty: Boolean = false; private set
    @Volatile var buildTime: String = "unknown"; private set
    @Volatile var buildNumber: Int = 0; private set

    /** "abc1234" or "abc1234-dirty". */
    val gitHashWithDirty: String get() = gitHash + if (gitDirty) "-dirty" else ""

    fun init(context: Context) {
        val r = context.applicationContext.resources
        runCatching {
            gitHash = r.getString(R.string.build_git_hash)
            gitBranch = r.getString(R.string.build_git_branch)
            gitDirty = r.getString(R.string.build_git_dirty).toBoolean()
            buildTime = r.getString(R.string.build_time)
            buildNumber = r.getString(R.string.build_number).toIntOrNull() ?: 0
        }
    }
}