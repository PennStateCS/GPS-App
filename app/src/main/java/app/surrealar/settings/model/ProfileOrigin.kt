package app.surrealar.settings.model

/**
 * Who chose the stored external receiver profile (`external_receiver_profile_origin`, Phase 5).
 *
 * - [USER]: picked by the user (profile selector, manual connect, or "Keep" on a mismatch banner). Never replaced
 *   automatically.
 * - [RECEIVER]: applied because the connected receiver reported its model (Emlid `/info`).
 * - [DEFAULT]: nobody chose it; the profile is the app default.
 *
 * Only [RECEIVER] and [DEFAULT] profiles may be replaced automatically by an identified model, and only when no
 * capture is running (see ReceiverIdentityRules).
 */
enum class ProfileOrigin(val prefKey: String) {
    USER("user"),
    RECEIVER("receiver"),
    DEFAULT("default");

    companion object {
        /**
         * The origin for a stored [token], given whether a profile key is stored at all.
         *
         * Installs from before Phase 5 have no origin key. A user who picked a profile back then has a profile key
         * stored, and that explicit choice must never be overridden automatically, so: absent token + stored profile
         * = [USER]; absent token + no stored profile = [DEFAULT]. An unknown token is treated as [USER] (the
         * conservative reading).
         */
        fun resolve(token: String?, profileStored: Boolean): ProfileOrigin = when {
            token == null -> if (profileStored) USER else DEFAULT
            else -> entries.firstOrNull { it.prefKey == token } ?: USER
        }
    }
}
