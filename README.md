# SurReal AR

[![Android](https://img.shields.io/badge/Android-7.0%2B-brightgreen?logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-blue?logo=kotlin)](https://kotlinlang.org)
[![API](https://img.shields.io/badge/API-24%2B-orange)](https://android-arsenal.com/api?level=24)
[![ARCore](https://img.shields.io/badge/ARCore-Supported-ff6f00?logo=google)](https://developers.google.com/ar)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

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

### 1. Clone the repository

```bash
git clone https://github.com/PennStateWilkes-Barre/GPS-App.git
cd GPS-App
```

### 2. Create `local.properties`

Copy the template file:

```bash
cp local.properties.template local.properties
```

Or create a new `local.properties` file in the project root.

At minimum, add your Android SDK path and Google Maps API key:

```properties
sdk.dir=YOUR_ANDROID_SDK_PATH
GOOGLE_MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
```

On Windows, the SDK path often looks similar to:

```properties
sdk.dir=C:\\Users\\YourUsername\\AppData\\Local\\Android\\Sdk
```

### 3. Configure your Google Maps API key

In Google Cloud Console:

1. Create or select a Google Cloud project.
2. Enable **Maps SDK for Android**.
3. Create an API key.
4. Restrict the key to Android apps.
5. Add the package name:

```text
app.surrealar
```

6. Add the SHA-1 fingerprint for the debug or release signing certificate.

For debug builds, run:

```bash
./gradlew signingReport
```

On Windows PowerShell:

```powershell
.\gradlew signingReport
```

### 4. Build the app

```bash
./gradlew assembleDebug
```

On Windows PowerShell:

```powershell
.\gradlew assembleDebug
```

You can also open the project in Android Studio and run the app directly from the IDE.

For more detailed setup and configuration instructions, see:

[Development Setup](docs/development-setup.md)

## Acknowledgements

SurReal AR is being developed as a faculty and undergraduate research project.

### Faculty

| Name             | Email                 | GitHub                                |
|------------------|-----------------------|---------------------------------------|
| Dimitrios Bolkas | dxb80@psu.edu | [dbolkas](https://github.com/dbolkas) |
| Jeffrey Chiampi  | jdc308@psu.edu        | [jdc308](https://github.com/jdc308)   |

### Undergraduate Research Students

| Name          | Email | GitHub |
|---------------|---|---|
| Jason Herrera | jvh6592@psu.edu | [accountrev](https://github.com/accountrev) |
| Kyle Jones    | kej5370@psu.edu | [kj000058](https://github.com/kj000058) |

## License

This project is licensed under the terms of the [MIT License](LICENSE).