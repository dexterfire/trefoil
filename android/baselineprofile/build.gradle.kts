plugins { alias(libs.plugins.android.test); alias(libs.plugins.baselineprofile) }

// Generates app/src/release/generated/baselineProfiles via :app:generateBaselineProfile.
// See docs/BASELINE_PROFILE.md.
android {
    namespace = "com.tajchert.trefoil.baselineprofile"
    compileSdk = 37
    defaultConfig { minSdk = 29; targetSdk = 37; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    targetProjectPath = ":app"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
}

baselineProfile { useConnectedDevices = true }

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
