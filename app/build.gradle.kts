import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: reads a properties file kept OUTSIDE the repo
// (storeFile / storePassword / keyAlias / keyPassword). Override the path with
// the JEV_KEYSTORE_PROPS env var. Without it, release builds are unsigned.
val releaseProps = Properties().apply {
    val f = file(System.getenv("JEV_KEYSTORE_PROPS") ?: "H:/android/keys/jev-release.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

// Private build: when <repoRoot>/apikey.toml exists (gitignored — NEVER commit
// it), its keys are baked into BuildConfig and the version gets a "-private"
// suffix, so the APK works out of the box with no keys typed into settings.
// Without the file the output is byte-for-byte the public build's config:
// empty strings, no suffix. Only simple `key = "value"` lines are read.
val apiKeys: Map<String, String> = run {
    val f = rootProject.file("apikey.toml")
    if (!f.exists()) emptyMap() else f.readText(Charsets.UTF_8).lineSequence()
        .mapNotNull { Regex("""^\s*(\w+)\s*=\s*"([^"]*)"\s*$""").find(it) }
        .associate { it.groupValues[1] to it.groupValues[2] }
}
val privateBuild = apiKeys.isNotEmpty()
fun bakedKey(v: String?): String =
    (v ?: "").replace("\\", "\\\\").replace("\"", "\\\"").ifEmpty { "" }

android {
    namespace = "com.jev.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jev.probe"
        minSdk = 30
        targetSdk = 35
        versionCode = 6
        versionName = "1.5"
        if (privateBuild) versionNameSuffix = "-private"

        // ML Kit's bundled Chinese recognizer ships native libs for every ABI.
        // The target phone (and every phone this can run on: minSdk 30) is
        // arm64, so keep only that one — the other three are dead weight.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("boolean", "PRIVATE_BUILD", privateBuild.toString())
        buildConfigField("String", "PRIVATE_JUDGE_KEY", "\"${bakedKey(apiKeys["jev"])}\"")
        buildConfigField("String", "PRIVATE_GLM_KEY", "\"${bakedKey(apiKeys["glm"])}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (releaseProps.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Uncompressed, page-aligned .so files: required for the 16 KB page-size
    // devices Android 15+ ships, and it lets the loader mmap the ML Kit natives
    // instead of unpacking them at install time.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // On-device OCR. The *bundled* Chinese model (not the play-services variant):
    // it works on phones with no Google Play services and needs no model download.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
