package io.github.pennstatecs.geokit.datum

import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition
import io.github.pennstatecs.geokit.status.ProductStatus

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

    /**
     * The weakest [FrameEdge.status] along [steps] (UNAVAILABLE < VERIFIED_BETA < VERIFIED_LEGACY), so a
     * chain through a beta edge is beta; [ProductStatus.VERIFIED_LEGACY] when there are no steps.
     */
    public val status: ProductStatus get() = weakestStatus(steps.map { it.status })

    /** The distinct non-null [FrameEdge.datasetVersion]s along [steps], in order. */
    public val datasetVersions: List<String> get() = steps.mapNotNull { it.datasetVersion }.distinct()
}

private fun rank(s: ProductStatus): Int = when (s) {
    ProductStatus.UNAVAILABLE -> 0
    ProductStatus.VERIFIED_BETA -> 1
    ProductStatus.VERIFIED_LEGACY -> 2
}

/** The weakest of [statuses]; [ProductStatus.VERIFIED_LEGACY] for none. */
internal fun weakestStatus(statuses: Iterable<ProductStatus>): ProductStatus =
    statuses.minByOrNull(::rank) ?: ProductStatus.VERIFIED_LEGACY

/**
 * One registered, verified transformation between two frames, usable in both directions.
 *
 * @property from frame of the [forward] input.
 * @property to frame of the [forward] output.
 * @property name the authority's name (e.g. EPSG "ITRF2014 to ITRF2020 (1)").
 * @property accuracyM stated accuracy in metres (EPSG), or null if the authority gives none.
 * @property verification what the implementation was verified against (reference vectors).
 * @property status how far the edge can be trusted: [ProductStatus.VERIFIED_LEGACY] for established
 *   verified transformations, [ProductStatus.VERIFIED_BETA] for NGS beta definitions verified against NGS
 *   beta tools. [ProductStatus.UNAVAILABLE] is rejected: an unverified edge is never registered.
 * @property datasetVersion version of the defining dataset (e.g. the NGS EPP release), null if none.
 */
public class FrameEdge(
    public val from: ReferenceFrame,
    public val to: ReferenceFrame,
    public val name: String,
    public val accuracyM: Double?,
    public val verification: String,
    public val status: ProductStatus,
    public val datasetVersion: String?,
    private val forward: (GeodeticPosition, Double) -> GeodeticPosition,
    private val reverse: (GeodeticPosition, Double) -> GeodeticPosition,
) {
    /** A [ProductStatus.VERIFIED_LEGACY] edge with no dataset version (the Phase 2 signature). */
    public constructor(
        from: ReferenceFrame,
        to: ReferenceFrame,
        name: String,
        accuracyM: Double?,
        verification: String,
        forward: (GeodeticPosition, Double) -> GeodeticPosition,
        reverse: (GeodeticPosition, Double) -> GeodeticPosition,
    ) : this(from, to, name, accuracyM, verification, ProductStatus.VERIFIED_LEGACY, null, forward, reverse)

    init {
        require(from == from.coordinatesFrame && to == to.coordinatesFrame) { "Register edges between coordinate frames, not aliases" }
        require(from != to) { "An edge needs two different frames" }
        require(status != ProductStatus.UNAVAILABLE) { "An unverified transformation is never an edge" }
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
    /**
     * [ProductStatus] of the result: the transformation's weakest edge status when available (callers
     * must label or refuse [ProductStatus.VERIFIED_BETA]), [ProductStatus.UNAVAILABLE] otherwise.
     */
    public val status: ProductStatus

    /** A verified transformation, bound to the requested coordinate [epoch]. */
    public data class Available(val transformation: FrameTransformation, val epoch: CoordinateEpoch) : FrameTransformationLookup {
        override val status: ProductStatus get() = transformation.status

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
    ) : FrameTransformationLookup {
        override val status: ProductStatus get() = ProductStatus.UNAVAILABLE
    }
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
 * so ITRF2020 ⇄ NAD83(2011) is the chain through ITRF2014. Both edges are [ProductStatus.VERIFIED_LEGACY].
 * NATRF2022 (and the other [PlateFrame2022] frames, by id), the WGS 84 ensemble and the WGS 84
 * realisations are [FrameUnavailableReason.NOT_VERIFIED]. [NSRS2022_BETA] adds the NGS-verified beta
 * edge ITRF2020 ⇄ NATRF2022 for callers that opt in.
 *
 * Path search prefers verified-legacy edges: a chain uses a [ProductStatus.VERIFIED_BETA] edge only when
 * no legacy-only chain exists, and the result then reports [FrameTransformation.status] beta.
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
        val path = shortestPath(from, to) { it.status == ProductStatus.VERIFIED_LEGACY }
            ?: shortestPath(from, to) { true }
            ?: return FrameTransformationLookup.Unavailable(
                source.id, target.id, FrameUnavailableReason.NOT_DEFINED,
                "No registered transformation connects ${source.displayName} and ${target.displayName}.",
            )
        return FrameTransformationLookup.Available(Chain(source, target, path), epoch)
    }

    /**
     * As [find], by stable frame ids. An unknown id gives [FrameUnavailableReason.UNKNOWN_FRAME]; a
     * reserved [PlateFrame2022] id that is not yet a [ReferenceFrame] (PATRF2022, CATRF2022, MATRF2022)
     * gives [FrameUnavailableReason.NOT_VERIFIED] with [PlateFrame2022.unavailableReason].
     */
    public fun find(sourceId: String, targetId: String, epoch: CoordinateEpoch): FrameTransformationLookup {
        val s = ReferenceFrame.fromId(sourceId)
        val t = ReferenceFrame.fromId(targetId)
        for ((frame, id) in listOf(s to sourceId, t to targetId)) {
            if (frame != null) continue
            val reserved = PlateFrame2022.fromId(id) ?: continue
            return FrameTransformationLookup.Unavailable(sourceId, targetId, FrameUnavailableReason.NOT_VERIFIED, reserved.unavailableReason)
        }
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

    private fun shortestPath(from: ReferenceFrame, to: ReferenceFrame, usable: (FrameEdge) -> Boolean): List<Step>? {
        val previous = HashMap<ReferenceFrame, Pair<ReferenceFrame, Step>>()
        val queue = ArrayDeque(listOf(from))
        val seen = hashSetOf(from)
        while (queue.isNotEmpty()) {
            val f = queue.removeFirst()
            if (f == to) break
            for (e in edges) {
                if (!usable(e)) continue
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
            ReferenceFrame.NATRF2022 to "NATRF2022 is defined (EPSG 10966-10968) as an NGS beta EPP2022 plate " +
                "rotation of ITRF2020, but ${PlateFrame2022.UNAVAILABLE_REASON}",
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

        /**
         * NGS beta "ITRF2020 to NATRF2022": the EPP2022 plate rotation ([PlateFrame2022.NATRF2022]
         * `.rotation`, NOAA TR NOS NGS 62 (2021) eq. 60), [ProductStatus.VERIFIED_BETA]. Verified against
         * NGS-computed coordinates ([PlateFrame2022.VERIFICATION]). Not part of [DEFAULT]; see [NSRS2022_BETA].
         */
        public val ITRF2020_TO_NATRF2022: FrameEdge = PlateFrame2022.NATRF2022.rotation.let { rot ->
            FrameEdge(
                ReferenceFrame.ITRF2020, ReferenceFrame.NATRF2022, "ITRF2020 to NATRF2022 (NGS EPP2022 beta)", null,
                PlateFrame2022.VERIFICATION, ProductStatus.VERIFIED_BETA, PlateFrame2022.DATASET_VERSION,
                rot::forward, rot::inverse,
            )
        }

        /**
         * Opt-in registry: [DEFAULT] plus [ITRF2020_TO_NATRF2022] (NATRF2022 is not blocked). Paths that
         * exist in [DEFAULT] are unchanged (legacy-only chains are preferred); any chain to or from NATRF2022
         * reports [ProductStatus.VERIFIED_BETA]. PATRF2022 / CATRF2022 / MATRF2022 are verified numerically
         * ([PlateFrame2022.rotation]) but are not [ReferenceFrame] entries yet, so they stay unavailable here.
         * [DEFAULT] is kept as it was until the app's capability model and its tests adopt the beta frames.
         */
        public val NSRS2022_BETA: FrameTransformationRegistry = FrameTransformationRegistry(
            DEFAULT.registeredEdges + ITRF2020_TO_NATRF2022, DEFAULT_UNAVAILABLE - ReferenceFrame.NATRF2022,
        )
    }
}
