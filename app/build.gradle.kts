import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The key shake-to-report posts issues with. Never in the repository: `local.properties` is
 * ignored by git, and CI hands it in from a repository secret. An empty string is a working
 * build — reports queue on the phone and go out from a later one that has the key.
 */
val reportToken: String = run {
    val local = rootProject.file("local.properties")
    val fromFile = if (local.exists()) {
        Properties().apply { local.inputStream().use { load(it) } }.getProperty("reportToken")
    } else {
        null
    }
    fromFile ?: System.getenv("REPORT_TOKEN") ?: ""
}

android {
    namespace = "com.gios.lightcontrol"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.gios.lightcontrol"
        minSdk = 29
        targetSdk = 35
        // CI overwrites both from the workflow run number; see .github/workflows/build.yml
        versionCode = 1
        versionName = "3.57.0"

        buildConfigField("String", "REPORT_TOKEN", "\"$reportToken\"")
        buildConfigField("String", "REPORT_REPO", "\"gi-os/light-reports\"")

        // The LPIII is arm64 only; shipping four ABIs tripled the APK for nothing.
        ndk { abiFilters += "arm64-v8a" }
    }

    // The release key used to live in this repository with its password written three lines
    // below it, which meant anyone could build an APK that Android would accept as an update to
    // this one — and this app holds an adb shell, an accessibility key filter and the lock face.
    // It is a CI secret now: the workflow decodes KEYSTORE_B64 to keystore/lightcontrol.jks,
    // which is gitignored, and KEYSTORE_PASSWORD opens it.
    //
    // A build without the secret still works and still produces an installable APK — it is just
    // signed with the local debug key and will not install over a release. That is the right
    // failure. An unsigned build that announces itself beats one that silently is not the real
    // thing, and the fingerprint check in build.yml catches it before anything is published.
    val keystoreFile = rootProject.file("keystore/lightcontrol.jks")
    val keystorePassword: String = System.getenv("KEYSTORE_PASSWORD") ?: ""
    val canSignRelease = keystoreFile.exists() && keystorePassword.isNotEmpty()

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = "lightcontrol"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            // Deliberately left off. R8 in a process that filters every key on the phone is a
            // separate change with its own way of going wrong, and it is not this one.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (canSignRelease) signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // buildConfig carries REPORT_TOKEN into the app; see the reportToken block above.
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    // Standing a photo upright. A phone photo is landscape-on-disk with a rotation tag more often
    // than not, and a lock screen that shows it sideways looks broken rather than uncropped.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // On-device ADB. Lets the app connect to its own phone's wireless-debugging daemon over
    // loopback and run the grant commands itself, so a reinstall no longer means plugging into a
    // computer to re-enable the service and re-grant the appops. See adb/AdbManager.kt.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // Generates the X509 client certificate libadb needs for the TLS handshake; the daemon
    // shows its fingerprint in the pairing dialog. Pure-Java sun.security backport, no NDK.
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    // Custom Conscrypt so the TLS 1.3 pairing handshake works without any hidden-API bypass.
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // The shake gesture is plain arithmetic with no Android imports, so it runs here.
    testImplementation("junit:junit:4.13.2")
}
