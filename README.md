# 📐 Surveying App

[![Android](https://img.shields.io/badge/Android-7.0%2B-brightgreen?logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blue?logo=kotlin)](https://kotlinlang.org)
[![API](https://img.shields.io/badge/API-24%2B-orange)](https://android-arsenal.com/api?level=24)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Build Status](https://img.shields.io/badge/Build-Passing-success)](https://github.com/yourusername/surveying-app)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](http://makeapullrequest.com)
[![Google Maps](https://img.shields.io/badge/Google%20Maps-SDK-red?logo=google-maps)](https://developers.google.com/maps/documentation/android-sdk/overview)
[![ARCore](https://img.shields.io/badge/ARCore-Supported-ff6f00?logo=google)](https://developers.google.com/ar)

A professional Android surveying application that combines GPS positioning, augmented reality (AR), and mapping capabilities for field surveying and data collection.


## 🛠️ Tech Stack

- **Language**: Kotlin
- **Architecture**: MVVM with LiveData and ViewModel
- **Maps**: Google Maps SDK
- **AR**: ARCore
- **Database**: Room (SQLite)
- **Navigation**: Android Navigation Component
- **Networking**: Coroutines + OkHttp
- **GNSS**: External receiver support via Bluetooth
- **UI**: Material Design 3, ViewBinding

## 📋 Requirements

- Android 7.0 (API 24) or higher
- Google Play Services
- Camera permission (for AR features)
- Location permissions (for GPS functionality)
- Optional: Bluetooth-enabled GNSS receiver for enhanced accuracy

## 🔧 Setup

### 1. Clone the Repository
```bash
git clone https://github.com/yourusername/surveying-app.git
cd surveying-app
```

### 2. Configure API Keys
1. Copy the template file:
   ```bash
   cp local.properties.template local.properties
   ```

2. Get your Google Maps API Key:
   - Go to [Google Cloud Console](https://console.cloud.google.com/apis/credentials)
   - Create a new project or select an existing one
   - Enable the Maps SDK for Android
   - Create credentials (API Key)
   - Restrict the key to your app's package name for security

3. Add your API key to `local.properties`:
   ```properties
   GOOGLE_MAPS_API_KEY=your_actual_api_key_here
   ```

### 3. Build and Run
```bash
./gradlew assembleDebug
```

Or open the project in Android Studio and run it directly.

## 🏗️ Project Structure

```
app/src/main/java/com/example/surveyingapp/
├── data/           # Data layer (repositories, data sources)
├── di/             # Dependency injection
├── domain/         # Domain layer (use cases, entities)
├── gnss/           # GNSS/GPS related functionality
├── service/        # Background services
├── ui/             # UI layer (activities, fragments, views)
├── util/           # Utility classes and helpers
├── MainActivity.kt # Main entry point
└── SurveyingApp.kt # Application class
```

## 🔒 Security

- API keys are stored in `local.properties` (not committed to version control)
- The app follows Android security best practices
- Location data is handled according to privacy guidelines

## 🧪 Testing

Run the test suite:
```bash
./gradlew test
./gradlew connectedAndroidTest
```

## 🙏 Acknowledgments

- Google Maps SDK for mapping functionality
- ARCore for augmented reality features
- Android Jetpack libraries for modern Android development
- Material Design for UI components


