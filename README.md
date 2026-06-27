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
- **GNSS**: External receiver support via TCP NMEA (e.g. Emlid Reach RS2+/RS4/RS4 Pro over Wi-Fi)
- **UI**: Material Design 3, ViewBinding

## 📋 Requirements

- Android 7.0 (API 24) or higher
- Google Play Services
- Camera permission (for AR features)
- Location permissions (for GPS functionality)
- Optional: External GNSS receiver reachable over TCP NMEA (e.g. an Emlid Reach on the same Wi-Fi network) for enhanced accuracy

## 🔧 Setup

### 1. Clone the Repository
```bash
git clone https://github.com/yourusername/surveying-app.git
cd surveying-app
```

### 2. Configure API Keys

⚠️ **Important**: This app requires a Google Maps API key to function properly. Follow these steps to create your own API keys file:

#### Step 1: Create Your Local Properties File
1. **Copy the template file**:
   ```bash
   cp local.properties.template local.properties
   ```
   
   Or manually create a new file called `local.properties` in the root directory.

2. **Verify the file structure**: Your `local.properties` file should look like this:
   ```properties
   # Location of the Android SDK (automatically filled by Android Studio)
   sdk.dir=C:\\Users\\YourUsername\\AppData\\Local\\Android\\Sdk
   
   # API Keys - Replace with your actual keys
   GOOGLE_MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY_HERE
   
   # Additional API Keys (add as needed)
   # WEATHER_API_KEY=your_weather_api_key_here
   # ELEVATION_API_KEY=your_elevation_api_key_here
   # MAPBOX_ACCESS_TOKEN=your_mapbox_token_here
   ```

#### Step 2: Get Your Google Maps API Key

1. **Go to Google Cloud Console**:
   - Visit [Google Cloud Console](https://console.cloud.google.com/)
   - Sign in with your Google account

2. **Create or Select a Project**:
   - Click "Select a project" at the top
   - Either create a new project or select an existing one
   - Note: You may need to enable billing for the project

3. **Enable Required APIs**:
   - Go to "APIs & Services" > "Library"
   - Search for and enable these APIs:
     - **Maps SDK for Android**
     - **Places API** (if using place search features)
     - **Geocoding API** (if using address lookup)

4. **Create API Credentials**:
   - Go to "APIs & Services" > "Credentials"
   - Click "Create Credentials" > "API Key"
   - Copy the generated API key

5. **Secure Your API Key** (Highly Recommended):
   - Click on your newly created API key to edit it
   - Under "Application restrictions":
     - Select "Android apps"
     - Add your app's package name: `app.surrealar`
     - Add your app's SHA-1 certificate fingerprint
   - Under "API restrictions":
     - Select "Restrict key"
     - Choose only the APIs you enabled above

   > 👥 **Team note:** This repo uses a **shared debug keystore** so every developer's local debug
   > build has the *same* SHA-1. Run `gradlew signingReport` to get the debug SHA-1 and register it
   > once. Full instructions: [`docs/development-setup.md`](docs/development-setup.md) →
   > "Shared Debug Keystore for Google Maps". (Debug only — never used for release signing.)

#### Step 3: Adding Additional API Keys

As your surveying app grows, you may need additional API keys for enhanced functionality:

##### Common APIs for Surveying Applications:

1. **Weather APIs** (for field conditions):
   - **OpenWeatherMap**: Visit [OpenWeatherMap API](https://openweathermap.org/api)
   - **WeatherAPI**: Visit [WeatherAPI.com](https://www.weatherapi.com/)
   
   Add to `local.properties`:
   ```properties
   WEATHER_API_KEY=your_openweather_api_key_here
   ```

2. **Elevation APIs** (for topographic data):
   - **Google Elevation API**: Already included with Google Maps setup
   - **USGS Elevation Point Query Service**: No API key required
   
   Add to `local.properties`:
   ```properties
   ELEVATION_API_KEY=your_elevation_api_key_here
   ```

3. **Alternative Map Providers**:
   - **Mapbox**: Visit [Mapbox Account](https://account.mapbox.com/)
   - **Here Maps**: Visit [Here Developer Portal](https://developer.here.com/)
   
   Add to `local.properties`:
   ```properties
   MAPBOX_ACCESS_TOKEN=pk.your_mapbox_public_token_here
   HERE_API_KEY=your_here_api_key_here
   ```

4. **Satellite Imagery APIs**:
   - **Planet Labs**: For high-resolution satellite imagery
   - **Sentinel Hub**: For Copernicus satellite data
   
   Add to `local.properties`:
   ```properties
   PLANET_API_KEY=your_planet_api_key_here
   SENTINEL_API_KEY=your_sentinel_api_key_here
   ```

##### How to Add New API Keys to Your App:

1. **Add the key to `local.properties`**:
   ```properties
   YOUR_NEW_API_KEY=your_actual_api_key_value
   ```

2. **Update `build.gradle.kts`** to read the new key:
   ```kotlin
   // In android > defaultConfig section
   manifestPlaceholders["YOUR_NEW_API_KEY"] = localProperties.getProperty("YOUR_NEW_API_KEY", "DEFAULT_VALUE")
   
   // Or for BuildConfig access:
   buildConfigField("String", "YOUR_NEW_API_KEY", "\"${localProperties.getProperty("YOUR_NEW_API_KEY", "DEFAULT_VALUE")}\"")
   ```

3. **Use in AndroidManifest.xml** (if needed):
   ```xml
   <meta-data
       android:name="com.yourservice.API_KEY"
       android:value="${YOUR_NEW_API_KEY}"/>
   ```

4. **Access in Kotlin code**:
   ```kotlin
   // If using manifestPlaceholders:
   val apiKey = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
       .metaData.getString("com.yourservice.API_KEY")
   
   // If using BuildConfig:
   val apiKey = BuildConfig.YOUR_NEW_API_KEY
   ```

5. **Update your template file** (`local.properties.template`):
   ```properties
   # Add your new API key placeholder
   YOUR_NEW_API_KEY=YOUR_NEW_API_KEY_HERE
   ```

##### Security Best Practices for New API Keys:

- ✅ **Always add new keys to `.gitignore`** (already configured for `local.properties`)
- ✅ **Use API key restrictions** when available (IP, domain, or app restrictions)
- ✅ **Set usage limits** to prevent unexpected charges
- ✅ **Use different keys for development and production**
- ✅ **Regularly rotate API keys** for enhanced security
- ❌ **Never hardcode API keys** in source code
- ❌ **Never commit API keys** to version control

##### Example: Adding a Weather API

Here's a complete example of adding OpenWeatherMap API:

1. **Get API key from OpenWeatherMap**
2. **Add to `local.properties`**:
   ```properties
   OPENWEATHER_API_KEY=abcd1234your_actual_key_here
   ```

3. **Update `app/build.gradle.kts`**:
   ```kotlin
   buildConfigField("String", "OPENWEATHER_API_KEY", "\"${localProperties.getProperty("OPENWEATHER_API_KEY", "")}\"")
   ```

4. **Use in your app**:
   ```kotlin
   class WeatherRepository {
       private val apiKey = BuildConfig.OPENWEATHER_API_KEY
       private val baseUrl = "https://api.openweathermap.org/data/2.5/"
       
       suspend fun getCurrentWeather(lat: Double, lon: Double): WeatherData {
           // Use apiKey in your API calls
       }
   }
   ```

#### Step 4: Add Your API Key to the Project

1. **Open your `local.properties` file**
2. **Replace the placeholder** with your actual API key:
   ```properties
   GOOGLE_MAPS_API_KEY=AIzaSyBxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   ```
   ⚠️ **Never commit this file to version control!**

#### Step 5: Get Your SHA-1 Certificate Fingerprint (For API Key Security)

Run this command in your project directory:

**For Debug Certificate:**
```bash
# Windows
./gradlew signingReport

# macOS/Linux  
./gradlew signingReport
```

Or use keytool directly:
```bash
# Windows
keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android

# macOS/Linux
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

Copy the SHA-1 fingerprint and add it to your API key restrictions in Google Cloud Console.

#### Troubleshooting API Keys

- **"API key not found" error**: Make sure your `local.properties` file is in the root directory (same level as `settings.gradle.kts`)
- **"This API project is not authorized" error**: Check that you've enabled the Maps SDK for Android and added the correct package name and SHA-1 fingerprint
- **Maps not loading**: Verify your API key is correct and has proper restrictions configured

### 3. Build and Run
```bash
./gradlew assembleDebug
```

Or open the project in Android Studio and run it directly.

## 🏗️ Project Structure

```
app/src/main/java/app/surrealar/
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
