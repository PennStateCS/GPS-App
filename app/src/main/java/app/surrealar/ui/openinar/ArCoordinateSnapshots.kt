package app.surrealar.ui.openinar

/**
 * The AR screen's coordinate list as one immutable [Snapshot], published by the main thread and read by
 * the GL thread, plus the bookkeeping that decides when geospatial anchors must be rebuilt.
 *
 *  - Every [publish] stores the newest list, so names, placements, updatedAt and the rest are never
 *    stale, and bumps [Snapshot.revision].
 *  - [Snapshot.anchorRevision] bumps only when something the anchor build reads changes ([anchorKey]):
 *    a placement- or metadata-only edit never rebuilds anchors.
 *  - The GL thread builds from one snapshot and reports it with [markBuilt]. A list published during
 *    the build has a newer anchorRevision, so [anchorsOutdated] stays true and the next frame rebuilds.
 *  - [takeUpdate] hands the GL thread each newer snapshot once, so live anchors can pick up current
 *    metadata (and drop deleted points) without a rebuild.
 *
 * The snapshot and both GL-side revisions are @Volatile; [publish] is main-thread only, the rest GL
 * thread only. Pure Kotlin → unit-tested.
 */
internal class ArCoordinateSnapshots<T>(private val anchorKey: (T) -> Any?) {

    class Snapshot<T>(
        val items: List<T>,
        /** Bumped by every publish. */
        val revision: Long,
        /** Bumped only when the anchor inputs change. */
        val anchorRevision: Long,
        internal val anchorKeys: List<Any?>,
    )

    @Volatile var current: Snapshot<T> = Snapshot(emptyList(), 0L, 0L, emptyList())
        private set

    /** anchorRevision of the snapshot the live anchors were built from (-1: never built). */
    @Volatile var builtAnchorRevision: Long = -1L
        private set

    /** revision of the last snapshot handed out by [takeUpdate] or built by [markBuilt]. */
    @Volatile private var seenRevision: Long = 0L

    /** Main thread. Publishes [items]; returns true when the anchor inputs changed (anchors need a rebuild). */
    fun publish(items: List<T>): Boolean {
        val prev = current
        val keys = items.map(anchorKey)
        val changed = keys != prev.anchorKeys
        current = Snapshot(items, prev.revision + 1, if (changed) prev.anchorRevision + 1 else prev.anchorRevision, keys)
        return changed
    }

    /** GL thread: the live anchors were built from an older anchor input than the current snapshot. */
    fun anchorsOutdated(): Boolean = builtAnchorRevision != current.anchorRevision

    /**
     * GL thread: the anchors now reflect [built]. Returns true when [built] still carries the current
     * anchor input; false when a newer list arrived during the build (a rebuild stays pending).
     */
    fun markBuilt(built: Snapshot<T>): Boolean {
        builtAnchorRevision = built.anchorRevision
        if (built.revision > seenRevision) seenRevision = built.revision
        return current.anchorRevision == built.anchorRevision
    }

    /** GL thread: the current snapshot if it is newer than the last one seen, else null. */
    fun takeUpdate(): Snapshot<T>? {
        val snap = current
        if (snap.revision == seenRevision) return null
        seenRevision = snap.revision
        return snap
    }
}
