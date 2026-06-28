# Development Setup

This page explains how to set up the project for local Android development.

## Requirements

Before building the app, install the following:

- Android Studio
- Android SDK
- Git
- A Google Maps API key
- An Android device or emulator with Google Play services

For AR testing, use a physical Android device that supports ARCore / Google Play Services for AR. Some map and location features may work in an emulator, but AR and geospatial features should be tested on real hardware.

## 1. Clone the Repository

```bash
git clone https://github.com/pennstatewilkes-barre/GPS-App.git
cd GPS-App
```

## 2. Open the Project in Android Studio

Open Android Studio and choose:

```text
File > Open
```

Then select the cloned project folder.

Android Studio should detect the Gradle project and begin syncing dependencies automatically.

## 3. Create `local.properties`

The `local.properties` file stores machine-specific configuration and should not be committed to Git.

Create a file named:

```text
local.properties
```

in the root of the project.

On Windows, it may look like this:

```properties
sdk.dir=C\:\\Users\\YOUR_USERNAME\\AppData\\Local\\Android\\Sdk
MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
```

On macOS, it may look like this:

```properties
sdk.dir=/Users/YOUR_USERNAME/Library/Android/sdk
MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
```

On Linux, it may look like this:

```properties
sdk.dir=/home/YOUR_USERNAME/Android/Sdk
MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
```

Replace `YOUR_GOOGLE_MAPS_API_KEY` with your actual Google Maps API key.

Do not commit this file.

## 4. Configure the Google Maps API Key

To use map features, create and configure a Google Maps API key.

General steps:

1. Open the Google Cloud Console.
2. Create or select a Google Cloud project.
3. Enable the Maps SDK for Android.
4. Create an API key.
5. Restrict the key to Android apps.
6. Add this app's package name.
7. Add the SHA-1 fingerprint for your debug or release signing certificate.
8. Add the key to `local.properties`.

For local development, use the debug signing certificate. For release builds, use the release signing certificate.

## 5. Sync Gradle

After creating `local.properties`, sync the project in Android Studio:

```text
File > Sync Project with Gradle Files
```

If Gradle sync fails, check that:

- `local.properties` exists in the project root
- `sdk.dir` points to your Android SDK
- `MAPS_API_KEY` is present
- Android Studio has installed the required SDK version
- The Maps SDK for Android is enabled in Google Cloud

## 6. Build the App

From Android Studio, choose:

```text
Build > Make Project
```

You can also build from the command line.

On Windows:

```bash
gradlew.bat assembleDebug
```

On macOS or Linux:

```bash
./gradlew assembleDebug
```

The debug APK will be generated under:

```text
app/build/outputs/apk/debug/
```

## 7. Run the App

You can run the app directly from Android Studio.

1. Connect an Android device or start an emulator.
2. Select the device from the Android Studio device menu.
3. Click **Run**.

For AR and geospatial testing, use a physical device with:

- Location services enabled
- Camera permission granted
- Google Play services installed
- Google Play Services for AR installed or available
- A clear outdoor view when testing geospatial positioning

## Troubleshooting

### The map does not load

Check the following:

- The Maps SDK for Android is enabled in Google Cloud.
- The API key is correct.
- The API key is listed in `local.properties`.
- The app package name matches the API key restriction.
- The SHA-1 fingerprint matches the certificate used to build the app.
- The device has internet access.

### Gradle cannot find the Android SDK

Check the `sdk.dir` value in `local.properties`.

Example:

```properties
sdk.dir=C\:\\Users\\YOUR_USERNAME\\AppData\\Local\\Android\\Sdk
```

You can find the SDK location in Android Studio under:

```text
File > Settings > Languages & Frameworks > Android SDK
```

On macOS:

```text
Android Studio > Settings > Languages & Frameworks > Android SDK
```

### AR does not start

Check the following:

- The device supports ARCore.
- Google Play Services for AR is installed or available.
- Camera permission is granted.
- Location permission is granted.
- The device has adequate lighting and camera visibility.

### Location or geospatial features are inaccurate

For best results:

- Test outdoors.
- Allow the device time to acquire a GPS fix.
- Avoid testing near tall buildings, heavy tree cover, or structures that block satellite visibility.
- Confirm that location permissions are enabled.
- Confirm that high-accuracy location mode is enabled on the device.