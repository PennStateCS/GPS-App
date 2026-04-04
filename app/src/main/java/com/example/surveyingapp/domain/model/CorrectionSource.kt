package com.example.surveyingapp.domain.model

enum class CorrectionSource {
    NTRIP,          // Network RTK via Internet Protocol
    LORA,           // LoRa radio corrections
    BASE_TCP,       // Direct TCP connection to base station
    RADIO_UHF,      // UHF radio corrections
    RADIO_VHF,      // VHF radio corrections
    BLUETOOTH,      // Bluetooth corrections from nearby base
    SERIAL,         // Serial/USB corrections
    WAAS_SBAS,      // Wide Area Augmentation System
    EGNOS,          // European Geostationary Navigation Overlay Service
    MSAS,           // Multi-functional Satellite Augmentation System
    QZSS_SLAS,      // QZSS Sub-meter Level Augmentation Service
    PPP,            // Precise Point Positioning
    OSNMA,          // Open Service Navigation Message Authentication
    INTERNAL,       // Device internal corrections
    UNKNOWN         // Fallback value
}
