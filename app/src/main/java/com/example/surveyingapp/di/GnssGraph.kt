package com.example.surveyingapp.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.bus.adapters.ExternalAdapter
import com.example.surveyingapp.gnss.bus.adapters.NmeaSourceBridge
import com.example.surveyingapp.gnss.satellites.SatelliteInventory
import com.example.surveyingapp.ui.rs2.Rs2ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import com.example.surveyingapp.gnss.repo.CoordinateRepository
import com.example.surveyingapp.ui.capture.CaptureViewModel
import com.example.surveyingapp.gnss.bus.adapters.ExistingNmeaFromLocationManager


/**
 * Small graph to initialize the Option B bus for RS2.
 * Swap to your real DI when convenient.
 */
class GnssGraph(
    private val appScope: CoroutineScope = MainScope(),
    bridgeFactory: () -> NmeaSourceBridge,          // inject your real bridge here
    private val switchboard: FixSwitchboard,         // created elsewhere if you also wire internal
    private val coordinateRepository: CoordinateRepository
) {
    private val existing = ExistingNmeaFromLocationManager(appScope)
    private val bridge = NmeaSourceBridge(appScope, existing)
    private val inventory = SatelliteInventory()
    private val externalAdapter = ExternalAdapter(appScope, bridge, inventory)


    init {
        // Start external path and attach its sky to the bus once
        externalAdapter.start()
        switchboard.attachSkyFlow(externalAdapter.sky)

        // Route fixes based on your settings (you already call refreshRouting() elsewhere)
        switchboard.refreshRouting()
    }

    // ViewModels the UI will use
    val rs2ViewModel by lazy {
        val fixBus = switchboard as com.example.surveyingapp.gnss.bus.FixBus
        val skyBus = switchboard as com.example.surveyingapp.gnss.bus.SkyBus
        Rs2ViewModel(fixBus, skyBus)
    }

    val captureViewModel by lazy {
        val fixBus = switchboard as com.example.surveyingapp.gnss.bus.FixBus
        CaptureViewModel(fixBus, coordinateRepository)
    }
}

/** Activity/fragment can implement this to access the graph easily. */
interface HasGnssGraph {
    val gnssGraph: GnssGraph
}

/** Simple factory for the VM if you use ViewModelProvider directly. */
class Rs2VmFactory(
    private val graph: GnssGraph
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(Rs2ViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return graph.rs2ViewModel as T
        }
        throw IllegalArgumentException("Unknown VM class")
    }
}
