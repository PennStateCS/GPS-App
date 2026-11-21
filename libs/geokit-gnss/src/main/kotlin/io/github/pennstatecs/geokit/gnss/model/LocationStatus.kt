package io.github.pennstatecs.geokit.gnss.model

/**
 * Represents the current state of the location service.
 *
 * Used by ViewModels to reflect what the service is doing to the UI, without
 * exposing internal details about the underlying GNSS source or connection.
 */
public sealed class LocationStatus {
    /** Service is inactive; no fix collection is taking place. */
    public object Idle : LocationStatus()

    /** Service is starting up or waiting for the first fix. */
    public object Connecting : LocationStatus()

    /** Fixes are arriving and being published normally. */
    public object Streaming : LocationStatus()

    /** Something went wrong; the service is not producing fixes. */
    public object Error : LocationStatus()
}
