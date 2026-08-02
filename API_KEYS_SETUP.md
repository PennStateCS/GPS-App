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
   - Enable **Maps SDK for Android** *and* the **ARCore API** (the same key feeds both `com.google.android.geo.API_KEY` and `com.google.android.ar.API_KEY`)
   - Create credentials (API Key)
   - **Restrict the key** — this is required, not optional. See [Restricting the key so only this app can use it](#restricting-the-key-so-only-this-app-can-use-it) below.

## How It Works

The build system automatically reads API keys from `local.properties` and injects them into the Android manifest:

```kotlin
// In build.gradle.kts
manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = localProperties.getProperty("GOOGLE_MAPS_API_KEY", "YOUR_API_KEY_HERE")
```

## Restricting the key so only this app can use it

The key is **never committed** (`local.properties` is gitignored), so cloning this repo does not hand anyone a working key — each developer supplies their own. The remaining risk is someone extracting the key from a distributed APK. Restrictions close that off: a restricted key only works when called from *this* app.

In **Google Cloud Console → APIs & Services → Credentials → [your key]:**

1. **Application restrictions → Android apps.** Add an entry:
   - Package name: `app.surrealar`
   - SHA-1 fingerprint: the signing certificate SHA-1 (see below)
2. **API restrictions → Restrict key** to **Maps SDK for Android** and **ARCore API** only.
3. Save.

### Getting the SHA-1

Debug builds are signed with the committed shared keystore (`keystores/shared-debug.keystore`), so every developer shares one SHA-1. Print it with:

```bash
keytool -list -v -keystore keystores/shared-debug.keystore -alias androiddebugkey -storepass android -keypass android
```

Look for the `SHA1:` line. (The `keytool` command is the source of truth — run it rather than trusting a copied value, since the fingerprint changes if the keystore is ever regenerated.)

## Two keys: development vs. release

The shared debug keystore is **committed on purpose** (so all developers share one SHA-1), which means its SHA-1 is *public*. A package + debug-SHA-1 restriction therefore can't truly stop someone who clones the repo from building a same-fingerprint app. To keep the public from using your key, split it in two:

| Key | Restrict to | Used by | Notes |
|-----|-------------|---------|-------|
| **Dev key** | package `app.surrealar` + **debug** SHA-1 | local development builds | The debug SHA-1 is public, so also set a **low quota + a billing budget alert** in Google Cloud to cap abuse. Put this key in your gitignored `local.properties`. |
| **Release key** | package `app.surrealar` + **release** keystore SHA-1 (private) | distributed / released builds | Real protection — only builds signed with your private release certificate can use it. |

Rules of thumb:

- Put the **dev key** in `local.properties` (`GOOGLE_MAPS_API_KEY=...`). Never commit it.
- Generate a **release keystore** for shipping builds and keep it **out of git** (`.gitignore` already blocks `*.keystore`/`*.jks` except the shared debug one — never `git add -f` a release keystore). Store it and its passwords in a password manager or CI secrets.
- **Do not distribute an APK built with the dev key** to the public — ship a release build signed with the private release keystore, using the release key.

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
