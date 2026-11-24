package io.github.pennstatecs.geokit.datum

import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition

/**
 * A transformation of positions from [source] to [target], evaluated at a coordinate epoch. Positions
 * are latitude/longitude in degrees and ellipsoidal height in metres. The epoch is that of both the
 * source and the target coordinates: a transformation never moves a point in time (propagating
 * coordinates between epochs within a frame is a separate, explicit step that GeoKit does not do yet).
 */
public interface FrameTransformation {
    /** Frame of the input coordinates. */
    public val source: ReferenceFrame

    /** Frame of the output coordinates. */
    public val target: ReferenceFrame

    /**
     * The verified edges applied in order (empty for a no-op between a frame and itself or its
     * labelling alias).
     */
    public val steps: List<FrameEdge>

    /** Short description, e.g. "ITRF2020 → ITRF2014 → NAD83(2011)". */
    public val description: String

    /** Transforms [position] at [epoch]. */
    public fun transform(position: GeodeticPosition, epoch: CoordinateEpoch): GeodeticPosition
}

/**
 * One registered, verified transformation between two frames, usable in both directions.
 *
 * @property from frame of the [forward] input.
 * @property to frame of the [forward] output.
 * @property name the authority's name (e.g. EPSG "ITRF2014 to ITRF2020 (1)").
 * @property accuracyM stated accuracy in metres (EPSG), or null if the authority gives none.
 * @property verification what the implementation was verified against (reference vectors).
 */
public class FrameEdge(
    public val from: ReferenceFrame,
    public val to: ReferenceFrame,
    public val name: String,
    public val accuracyM: Double?,
    public val verification: String,
    private val forward: (GeodeticPosition, Double) -> GeodeticPosition,
    private val reverse: (GeodeticPosition, Double) -> GeodeticPosition,
) {
    init {
        require(from == from.coordinatesFrame && to == to.coordinatesFrame) { "Register edges between coordinate frames, not aliases" }
        require(from != to) { "An edge needs two different frames" }
    }

    /** [from] → [to] at [epoch] (decimal year). */
    public fun forward(p: GeodeticPosition, epoch: Double): GeodeticPosition = forward.invoke(p, epoch)

    /** [to] → [from] at [epoch] (decimal year). */
    public fun reverse(p: GeodeticPosition, epoch: Double): GeodeticPosition = reverse.invoke(p, epoch)

    override fun toString(): String = name
}

/** Why no transformation is available. */
public enum class FrameUnavailableReason {
    /** A frame id is not known to this version (e.g. stored by a newer version). */
    UNKNOWN_FRAME,

    /** No chain of registered transformations connects the two frames. */
    NOT_DEFINED,

    /** A transformation exists in principle, but none is implemented and verified to survey precision. */
    NOT_VERIFIED,
}

/** Result of [FrameTransformationRegistry.find]. */
public sealed interface FrameTransformationLookup {
    /** A verified transformation, bound to the requested coordinate [epoch]. */
    public data class Available(val transformation: FrameTransformation, val epoch: CoordinateEpoch) : FrameTransformationLookup {
        /** Transforms [position] at [epoch]. */
        public fun apply(position: GeodeticPosition): GeodeticPosition = transformation.transform(position, epoch)
    }

    /**
     * No transformation. [source] and [target] are the requested frame ids; [explanation] is a short
     * English sentence for logs and diagnostics.
     */
    public data class Unavailable(
        val source: String,
        val target: String,
        val reason: FrameUnavailableReason,
        val explanation: String,
    ) : FrameTransformationLookup
}

/**
 * The transformations between [ReferenceFrame]s that GeoKit can perform. A lookup returns either a
 * transformation made only of registered (verified) [FrameEdge]s — directly, inverted, or chained
 * through intermediate frames by the shortest path — or a typed reason. Frames listed in
 * `unavailable` never get a path, whatever edges exist; there is never an identity or placeholder
 * between different frames (a labelling alias such as [ReferenceFrame.WGS84_AS_ITRF2014] is the same
 * coordinates, not a transformation).
 *
 * The default registry, [DEFAULT], contains:
 * - ITRF2014 ⇄ NAD83(2011): [Itrf2014ToNad83] unchanged (EPSG "ITRF2014 to NAD83(2011) (1)");
 * - ITRF2014 ⇄ ITRF2020: [Itrf2014ToItrf2020] (EPSG "ITRF2014 to ITRF2020 (1)");
 * so ITRF2020 ⇄ NAD83(2011) is the chain through ITRF2014. NATRF2022, the WGS 84 ensemble and the WGS 84
 * realisations are [FrameUnavailableReason.NOT_VERIFIED].
 *
 * Immutable and thread-safe.
 */
public class FrameTransformationRegistry(
    edges: List<FrameEdge>,
    unavailable: Map<ReferenceFrame, String> = emptyMap(),
) {
    private val edges: List<FrameEdge> = edges.toList()
    private val unavailable: Map<ReferenceFrame, String> = unavailable.toMap()

    /** The registered edges, in registration order. */
    public val registeredEdges: List<FrameEdge> get() = edges

    /** The transformation from [source] to [target] at [epoch], or why there is none. */
    public fun find(source: ReferenceFrame, target: ReferenceFrame, epoch: CoordinateEpoch): FrameTransformationLookup {
        if (source == target) return FrameTransformationLookup.Available(Chain(source, target, emptyList()), epoch)
        val from = source.coordinatesFrame
        val to = target.coordinatesFrame
        // A blocked frame wins even over an alias (an alias is never blocked by construction).
        for (f in listOf(from, to)) {
            unavailable[f]?.let {
                return FrameTransformationLookup.Unavailable(source.id, target.id, FrameUnavailableReason.NOT_VERIFIED, it)
            }
        }
        if (from == to) return FrameTransformationLookup.Available(Chain(source, target, emptyList()), epoch)
        val path = shortestPath(from, to)
            ?: return FrameTransformationLookup.Unavailable(
                source.id, target.id, FrameUnavailableReason.NOT_DEFINED,
                "No registered transformation connects ${source.displayName} and ${target.displayName}.",
            )
        return FrameTransformationLookup.Available(Chain(source, target, path), epoch)
    }

    /** As [find], by stable frame ids; an unknown id gives [FrameUnavailableReason.UNKNOWN_FRAME]. */
    public fun find(sourceId: String, targetId: String, epoch: CoordinateEpoch): FrameTransformationLookup {
        val s = ReferenceFrame.fromId(sourceId)
        val t = ReferenceFrame.fromId(targetId)
        if (s == null || t == null) {
            val unknown = listOfNotNull(sourceId.takeIf { s == null }, targetId.takeIf { t == null }).distinct()
            return FrameTransformationLookup.Unavailable(
                sourceId, targetId, FrameUnavailableReason.UNKNOWN_FRAME,
                "Unknown reference frame id ${unknown.joinToString { "\"$it\"" }}.",
            )
        }
        return find(s, t, epoch)
    }

    /** A step along an edge in one direction. */
    private class Step(val edge: FrameEdge, val forward: Boolean)

    private fun shortestPath(from: ReferenceFrame, to: ReferenceFrame): List<Step>? {
        val previous = HashMap<ReferenceFrame, Pair<ReferenceFrame, Step>>()
        val queue = ArrayDeque(listOf(from))
        val seen = hashSetOf(from)
        while (queue.isNotEmpty()) {
            val f = queue.removeFirst()
            if (f == to) break
            for (e in edges) {
                val (next, step) = when (f) {
                    e.from -> e.to to Step(e, true)
                    e.to -> e.from to Step(e, false)
                    else -> continue
                }
                if (next in unavailable || !seen.add(next)) continue
                previous[next] = f to step
                queue.addLast(next)
            }
        }
        if (to !in seen) return null
        val steps = ArrayList<Step>()
        var f = to
        while (f != from) {
            val (p, step) = previous.getValue(f)
            steps.add(step); f = p
        }
        return steps.asReversed().toList()
    }

    private class Chain(
        override val source: ReferenceFrame,
        override val target: ReferenceFrame,
        private val path: List<Step>,
    ) : FrameTransformation {
        override val steps: List<FrameEdge> = path.map { it.edge }

        override val description: String = if (path.isEmpty()) "${source.displayName} (no transformation)" else
            (listOf(source.displayName) + path.mapIndexed { i, s ->
                if (i == path.lastIndex) target.displayName else (if (s.forward) s.edge.to else s.edge.from).displayName
            }).joinToString(" → ")

        override fun transform(position: GeodeticPosition, epoch: CoordinateEpoch): GeodeticPosition {
            var p = position
            for (s in path) p = if (s.forward) s.edge.forward(p, epoch.decimalYear) else s.edge.reverse(p, epoch.decimalYear)
            return p
        }

        override fun toString(): String = description
    }

    public companion object {
        /** EPSG "ITRF2014 to NAD83(2011) (1)", delegating to [Itrf2014ToNad83] (numbers unchanged). */
        public val ITRF2014_TO_NAD83_2011: FrameEdge = FrameEdge(
            ReferenceFrame.ITRF2014, ReferenceFrame.NAD83_2011, "ITRF2014 to NAD83(2011) (1)", null,
            "PROJ 9.8.1 EPSG:7912→EPSG:6319 (ReferencePointsTest)",
            Itrf2014ToNad83::forward, Itrf2014ToNad83::inverse,
        )

        /** EPSG "ITRF2014 to ITRF2020 (1)", [Itrf2014ToItrf2020]; EPSG accuracy 1 mm. */
        public val ITRF2014_TO_ITRF2020: FrameEdge = FrameEdge(
            ReferenceFrame.ITRF2014, ReferenceFrame.ITRF2020, "ITRF2014 to ITRF2020 (1)", 0.001,
            "PROJ 9.8.1 EPSG:7912⇄EPSG:9989 (coords/itrf2020_vectors.csv)",
            Itrf2014ToItrf2020::forward, Itrf2014ToItrf2020::inverse,
        )

        private const val WGS84_REALISATION =
            "is aligned with ITRF only at the centimetre level; EPSG/PROJ relate it to ITRF by null " +
                "transformations, so GeoKit has no verified survey-grade transformation for it."

        /** Why the default registry has no transformation for these frames. */
        public val DEFAULT_UNAVAILABLE: Map<ReferenceFrame, String> = mapOf(
            ReferenceFrame.NATRF2022 to "NATRF2022 is defined (EPSG 10966-10968), but the NGS transformation " +
                "from ITRF2020 (plate rotation plus the IFDM2022 deformation model) is not implemented or " +
                "verified; PROJ 9.8.1 offers only ballpark no-op operations, which are not used.",
            ReferenceFrame.WGS84 to "The WGS 84 ensemble (realisation unspecified) is accurate to 2 m only; " +
                "choose a realisation, or WGS84 (ITRF2014) for coordinates known to be ITRF2014.",
            ReferenceFrame.WGS84_G1762 to "WGS 84 (G1762) $WGS84_REALISATION",
            ReferenceFrame.WGS84_G2139 to "WGS 84 (G2139) $WGS84_REALISATION",
            ReferenceFrame.WGS84_G2296 to "WGS 84 (G2296) $WGS84_REALISATION",
        )

        /** The registry GeoKit uses by default (see the class documentation). */
        public val DEFAULT: FrameTransformationRegistry = FrameTransformationRegistry(
            listOf(ITRF2014_TO_NAD83_2011, ITRF2014_TO_ITRF2020), DEFAULT_UNAVAILABLE,
        )
    }
}
