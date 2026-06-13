package com.example.surveyingapp.gnss.model

/**
 * Tracks the connection state of an external GNSS device.
 *
 * Used by adapters and the UI to show the user what the receiver is doing
 * right now — whether it is still trying to connect, actively streaming data,
 * or has hit a problem.
 */
enum class ConnectionStatus {
    /** No active connection and no attempt in progress. */
    DISCONNECTED,

    /** A connection attempt is underway but data has not started flowing yet. */
    CONNECTING,

    /** TCP socket is open and the handshake succeeded. */
    CONNECTED,

    /** Data is arriving and being processed normally. */
    STREAMING,

    /** Connected, but data quality or throughput has dropped below expected levels. */
    DEGRADED,

    /** Connection failed or was lost unexpectedly. */
    ERROR
}
