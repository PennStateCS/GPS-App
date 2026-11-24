# Installing the Phase 1 + 2 build on the test tablet, and rolling back

Nothing here is automatic. Run each step yourself, in order, only after approval. Never uninstall the app and
never clear its data: either deletes the field data.

## 1. Facts

| Item | Value |
|---|---|
| Device | Samsung SM-X940, wireless adb serial `adb-R5GL82C2R6X-YZFE9H._adb-tls-connect._tcp` (check `adb devices -l`) |
| Application ID | `app.surrealar` (debug build, `DEBUGGABLE`, so `run-as` works) |
| Installed today | debug build of `refactor/rendermap` (bf9f8be, merged into main), database **v13** |
| New APK | `survey/phase2` → `app/build/outputs/apk/debug/app-debug.apk` (rebuild with `.\gradlew.bat :app:assembleDebug --no-daemon --max-workers=2`) |
| Database change | Room **v13 → v14**: ADD COLUMN only (5 nullable columns). Room cannot open a v14 database with the v13 app |
| Signing | Both builds use the committed shared debug keystore; certificate SHA-256 `1e74213c…dd0865a6` |
| versionCode | 1 for both builds (an in-place `install -r` of an equal versionCode is allowed; no `-d` needed) |
| Existing backups | `C:\Users\jdc308\Desktop\SurveyingApp-tablet-backups\2026-11-24-before-rendermap\` (data tar + APK of the older main build) and `...\2026-11-24-pre-install\` (data tar taken just before the current build was installed) |

## 2. Before installing (all read-only on the tablet)

Pick a new folder, e.g. `D=C:\Users\jdc308\Desktop\SurveyingApp-tablet-backups\<date>-before-phase2`.

1. Fresh data backup of the **current** v13 state (keep it; it is the rollback point):
   `adb -s <serial> exec-out run-as app.surrealar tar cf - databases files shared_prefs no_backup app_ > "$D\app.surrealar-data.tar"`
2. Save the **currently installed** APK (it matches the v13 database):
   `adb -s <serial> shell pm path app.surrealar` → `adb -s <serial> pull <path>/base.apk "$D\installed-v13-base.apk"`
3. Check the backup is readable: extract it, open `databases/surveying_app.db` with SQLite (copy the `-wal`/`-shm`
   files alongside): `PRAGMA integrity_check` = ok, `PRAGMA user_version` = 13, 5 rows in `coordinates`, 2 in `models`.
   Record a hash of the coordinate rows for comparison after the upgrade.
4. Signing/version check (build-tools `apksigner` / `aapt2`):
   `apksigner verify --print-certs installed-v13-base.apk` and the new `app-debug.apk` → same certificate SHA-256;
   `aapt2 dump badging` → same `package: name='app.surrealar'`, versionCode new ≥ installed.
   **Stop if the certificate differs**: an install would fail, and uninstalling to "fix" it would delete the data.

## 3. Install in place

`adb -s <serial> install -r app-debug.apk` → `Success`. Confirm `dumpsys package app.surrealar` shows the same
`firstInstallTime` as before (an update, not a fresh install). Do not use `-d`, `uninstall` or `pm clear`.

## 4. After installing

Open the app once (the v13 → v14 migration runs on first database access), then follow
[tablet-acceptance-checklist.md](tablet-acceptance-checklist.md) §1.

## 5. Verify the migrated data

`adb -s <serial> exec-out run-as app.surrealar tar cf - databases files/datastore > after.tar`, extract, and check:
`user_version` = 14, integrity ok, same 5 coordinate rows (original columns identical to the step-2 hash), 2 models,
the 5 new columns NULL for those rows, model and thumbnail files present.

## 6. Rolling back (only if the new build or the migration fails)

Reinstalling the old APK alone is **not** a rollback: the v13 app cannot open the v14 database (Room refuses a
downgrade; destructive fallback is disabled in these builds, so it would crash rather than wipe — still unusable).
A rollback therefore restores the matching pre-upgrade data **and** the old APK, together:

1. Stop the app: `adb -s <serial> shell am force-stop app.surrealar`.
2. Reinstall the saved v13 APK in place: `adb -s <serial> install -r "$D\installed-v13-base.apk"`
   (same versionCode and certificate, so no downgrade flag is needed; if Android ever reports a downgrade, use
   `install -r -d`, still never uninstall).
3. Restore the step-1 data into the app's private directory (debuggable build only):
   `adb -s <serial> push "$D\app.surrealar-data.tar" /data/local/tmp/surrealar-restore.tar`
   `adb -s <serial> shell run-as app.surrealar tar xf /data/local/tmp/surrealar-restore.tar`
   (this overwrites `databases/`, `files/`, `shared_prefs/` with the v13 copies; make sure the app is stopped)
   `adb -s <serial> shell rm /data/local/tmp/surrealar-restore.tar`
4. Open the app and check the 5 coordinates and 2 models (checklist §1.2–1.3).
5. Data captured **after** the upgrade is not in the restored copy: export it first (JSON backup from the new
   build, if it still runs) and re-import it after the rollback (old builds ignore the new provenance keys).

If anything is unclear at any step, stop: the backup folder still holds a complete copy and nothing has been lost.
