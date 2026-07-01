# SurReal AR

[![Status](https://img.shields.io/badge/Status-In%20Development-orange)](#project-status)
[![Android](https://img.shields.io/badge/Android-7.0%2B-brightgreen?logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Android-blue?logo=kotlin)](https://kotlinlang.org)

[![Google Maps](https://img.shields.io/badge/Google%20Maps-API%20Key%20Required-4285F4?logo=googlemaps)](https://developers.google.com/maps/documentation/android-sdk)
[![ARCore](https://img.shields.io/badge/ARCore-Required%20for%20AR-ff6f00?logo=google)](https://developers.google.com/ar)
[![Docs](https://img.shields.io/badge/Docs-GitHub%20Pages-blue?logo=github)](https://pennstatewilkes-barre.github.io/GPS-App/)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)





SurReal AR is an Android field-mapping application for saving survey coordinates, linking them to 3D models, and visualizing those points and models in augmented reality.

## Project Status

SurReal AR is currently in development. Features, data formats, and device workflows may change as the project is tested with field equipment and refined for real surveying use cases.

## Developer Documentation

Developer API documentation is generated from KDoc using Dokka and published through GitHub Pages:

[View the SurReal AR Developer Documentation](https://pennstatewilkes-barre.github.io/GPS-App/)

These docs are intended for developers working on the codebase. They describe the app’s packages, important classes, data flow, coordinate/model storage, GNSS handling, import/export behavior, and AR rendering support. They are not an end-user manual.

## Project Requirements

- Android 7.0 or higher, API 24+
- Android Studio
- JDK compatible with the project’s Gradle configuration
- Google Play Services
- Google Maps API key
- Camera permission for AR features
- Location permission for GPS/GNSS features
- ARCore-supported device for AR features
- Optional: external GNSS receiver with TCP NMEA output, such as an Emlid Reach receiver on the same Wi-Fi network

## Quick Start

1. Clone the repository
2. Create `local.properties`
3. Configure your Google Maps API key
4. Build the app

You can also open the project in Android Studio and run the app directly from the IDE.  For more detailed setup and configuration instructions, see: [Development Setup](docs/development-setup.md)

## Acknowledgements

SurReal AR is being developed as a faculty and undergraduate research project.

### Faculty

| Name             | Email                 | GitHub                                |
|------------------|-----------------------|---------------------------------------|
| Dimitrios Bolkas | dxb80@psu.edu | [dbolkas](https://github.com/dbolkas) |
| Jeffrey Chiampi  | jdc308@psu.edu        | [jdc308](https://github.com/jdc308)   |

### Undergraduate Research Assistants 

| Name          | Email | GitHub |
|---------------|---|---|
| Jason Herrera | jvh6592@psu.edu | [accountrev](https://github.com/accountrev) |
| Kyle Jones    | kej5370@psu.edu | [kj000058](https://github.com/kj000058) |

## License

This project is licensed under the terms of the [GNU General Public License v3.0](LICENSE).
