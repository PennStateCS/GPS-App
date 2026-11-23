package app.surrealar.ui.openinar

/**
 * Records which native resources of a multi-step init actually exist, so teardown releases exactly
 * those, once each, wherever init stopped. [ArFilamentRenderer.init] marks each Filament object as it
 * is created; [ArFilamentRenderer.destroy] releases through [release], so a partial init (one step
 * threw) frees what was made and a second destroy is a no-op.
 *
 * Pure Kotlin (no Filament types) so the lifecycle is JVM-testable. Single thread (main) only.
 */
internal class NativeResourceTracker<R : Enum<R>> {
    private val live = LinkedHashSet<R>()

    /** True when nothing is live: init never ran, failed before its first resource, or was torn down. */
    val isEmpty: Boolean get() = live.isEmpty()

    /** Live resources in creation order (a copy). */
    val liveResources: List<R> get() = live.toList()

    fun created(resource: R) {
        live += resource
    }

    fun isLive(resource: R): Boolean = resource in live

    /**
     * Runs [action] if [resource] is live and returns whether it ran. The resource is marked released
     * BEFORE [action] runs, so a release that throws is reported once through [onFailure] and is never
     * attempted again on a handle that may already be half-freed. Errors (not Exceptions) propagate.
     */
    fun release(resource: R, onFailure: (R, Exception) -> Unit, action: () -> Unit): Boolean {
        if (!live.remove(resource)) return false
        try {
            action()
        } catch (e: Exception) {
            onFailure(resource, e)
        }
        return true
    }
}
