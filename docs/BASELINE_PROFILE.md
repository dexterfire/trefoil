# Baseline profile

The release APK ships a baseline profile (`android/app/src/release/generated/baselineProfiles/`)
that ART compiles ahead of time on install through `androidx.profileinstaller`. It covers
cold start, the machines screen and the pairing dialog on a fresh install.

The `:baselineprofile` module (`com.android.test`, `androidx.baselineprofile` plugin) holds:

- `BaselineProfileGenerator`: the journey that records the profile. It never connects to a T3
  server, so it uses no real threads or credentials.
- `StartupBenchmarks`: cold start with no compilation against the baseline profile.

## Regenerate

Needs one running emulator or device on API 33+, or a rooted API 28+ one (Google APIs
images are rootable). Only one device should be attached, or set `ANDROID_SERIAL`.

```sh
cd android
./gradlew :app:generateBaselineProfile \
  -Pandroid.testInstrumentationRunnerArguments.class=com.tajchert.trefoil.baselineprofile.BaselineProfileGenerator
```

Commit the updated `baseline-prof.txt` and `startup-prof.txt`. Regenerate after large
changes to startup or the first screen.

## Measure

```sh
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.tajchert.trefoil.baselineprofile.StartupBenchmarks
```

Emulator numbers only show the direction of a change. Use a physical device for absolute timings.
