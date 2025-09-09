# API Key Security Setup

This project uses `local.properties` file to keep API keys secure and prevent them from being committed to version control.

## Setup Instructions

1. Copy the template file:
   ```
   cp local.properties.template local.properties
   ```

2. Edit `local.properties` and replace `YOUR_GOOGLE_MAPS_API_KEY_HERE` with your actual Google Maps API key:
   ```
   GOOGLE_MAPS_API_KEY=AIzaSyBxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   ```

3. Get your Google Maps API Key:
   - Go to [Google Cloud Console](https://console.cloud.google.com/apis/credentials)
   - Create a new project or select an existing one
   - Enable the Maps SDK for Android
   - Create credentials (API Key)
   - Restrict the key to your app's package name for security

## How It Works

The build system automatically reads API keys from `local.properties` and injects them into the Android manifest:

```kotlin
// In build.gradle.kts
manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = localProperties.getProperty("GOOGLE_MAPS_API_KEY", "YOUR_API_KEY_HERE")
```
## For CI/CD

If you need to build in CI/CD environments, you can:
1. Set environment variables in your CI system
2. Use secure secrets in GitHub Actions, Jenkins, etc.
3. Create the `local.properties` file dynamically in your build script

Example for GitHub Actions:
```yaml
- name: Create local.properties
  run: echo "GOOGLE_MAPS_API_KEY=${{ secrets.GOOGLE_MAPS_API_KEY }}" > local.properties
```
