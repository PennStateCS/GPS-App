package app.surrealar.gnss.model

/**
 * Represents the current state of the location service.
 *
 * Used by ViewModels to reflect what the service is doing to the UI, without
 * exposing internal details about the underlying GNSS source or connection.
 */
sealed class LocationStatus {
    /** Service is inactive; no fix collection is taking place. */
    object Idle : LocationStatus()

    /** Service is starting up or waiting for the first fix. */
    object Connecting : LocationStatus()

    /** Fixes are arriving and being published normally. */
    object Streaming : LocationStatus()

    /** Something went wrong; the service is not producing fixes. */
    object Error : LocationStatus()
}
