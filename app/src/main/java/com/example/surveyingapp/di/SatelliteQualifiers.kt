package com.example.surveyingapp.di

import javax.inject.Qualifier

/**
 * Qualifier for SatelliteInventory used by InternalAdapter.
 * Ensures separate satellite tracking per GNSS provider.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InternalSatellites

/**
 * Qualifier for SatelliteInventory used by ExternalAdapter.
 * Ensures separate satellite tracking per GNSS provider.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ExternalSatellites

