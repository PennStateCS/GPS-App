package com.example.surveyingapp.gnss.model

/**
 * Identifies where a GNSS fix came from.
 *
 * This value is stamped onto every [Fix] so downstream code (UI, storage, accuracy
 * estimation) knows which receiver produced the data without having to inspect
 * the fix pipeline itself.
 */
enum class Provider {
    /** Android device's built-in GNSS chip (via the NMEA listener API). */
    INTERNAL,

    /** External receiver connected over RS2 — general/legacy label. */
    RS2_EXTERNAL,

    /** RS2+ receiver connected over Bluetooth. */
    RS2_BT,

    /** RS2+ receiver connected over TCP/IP (e.g. Wi-Fi hotspot). */
    RS2_TCP,

    /** Any external receiver that does not fall into the RS2 categories. */
    OTHER
}
