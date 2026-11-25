package io.github.pennstatecs.geokit.status

/**
 * How far a coordinate product (reference-frame transformation, projected zone, geoid model) can be
 * trusted. Every Phase 3 (modernized NSRS) capability carries one, so beta results can never be shown
 * as authoritative survey coordinates and nothing unverified is ever offered as working.
 */
public enum class ProductStatus {
    /** Established, independently verified workflow (NAD83(2011), SPCS83, GEOID18, ITRF2014⇄ITRF2020, EGM…). */
    VERIFIED_LEGACY,

    /**
     * Implemented from NGS beta (pre-release) definitions and independently tested against NGS beta tools
     * (e.g. beta NCAT). Numerically checked but NOT authoritative for production surveying: always labelled
     * "beta" with its dataset version, never a default, opt-in only.
     */
    VERIFIED_BETA,

    /** Missing definitions, unverified calculations or no coverage: never offered as operational. */
    UNAVAILABLE,
}
