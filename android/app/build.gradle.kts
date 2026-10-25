import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("app.cash.paparazzi")
}

// Release signing is optional so that anyone can build the public repository.
// Point Gradle at a keystore.properties file kept OUTSIDE the repository, either with
//   ./gradlew assembleRelease -Pstadtlaerm.keystoreProperties=/path/to/keystore.properties
// or with the environment variable STADTLAERM_KEYSTORE_PROPERTIES. The file needs
// storeFile, storePassword, keyAlias and keyPassword. Without it the release APK is unsigned.
val keystorePropertiesPath: String? =
    (findProperty("stadtlaerm.keystoreProperties") as String?)
        ?: System.getenv("STADTLAERM_KEYSTORE_PROPERTIES")
val keystoreProperties: Properties? = keystorePropertiesPath
    ?.takeIf { it.isNotBlank() }
    ?.let { path ->
        val file = file(path)
        require(file.isFile) { "Keystore properties file not found: $path" }
        Properties().apply { file.inputStream().use { load(it) } }
    }

// Build date shown in the app («Build vom …») and used for the offline "this version is n days
// old" reminder. Taken from the build time (Zürich calendar date); SOURCE_DATE_EPOCH, if set,
// overrides it for reproducible builds. Tests may read BuildConfig.BUILD_DATE but must never
// assert its value.
val buildDate: String = (System.getenv("SOURCE_DATE_EPOCH")?.toLongOrNull()
    ?.let { Instant.ofEpochSecond(it).atZone(ZoneId.of("Europe/Zurich")).toLocalDate() }
    ?: LocalDate.now(ZoneId.of("Europe/Zurich"))).toString()

// Base URL of the contribution server (server/DESIGN.md §8). One host, HTTPS only. The Gradle
// property keeps its old name (-Pstadtlaerm.api=…) so that existing test scripts still work.
val apiBase: String = (findProperty("stadtlaerm.api") as String?)?.trimEnd('/') ?: "https://api.lessnoise.ch"

android {
    namespace = "ch.lessnoise.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ch.lessnoise.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 40
        versionName = "0.8.8"
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
        // The one host the opt-in upload «Messwerte teilen» talks to (server/DESIGN.md §4). Only
        // app/src/main/kotlin/ch/lessnoise/app/upload/UploadClient.kt opens connections, and only
        // to this base URL. Override for a local test server with -Pstadtlaerm.api=https://…
        buildConfigField("String", "API_BASE", "\"$apiBase\"")
        // Whether this edition offers «Messwerte teilen» at all (public: yes; offline, labor: no).
        buildConfigField("boolean", "UPLOAD_AVAILABLE", "true")
        // Which edition this is: "offline" | "karte" | "labor" | "labor-karte" (set per flavour
        // below). Shown in Einstellungen → Über die App; the two published editions send it to
        // update.html as #…&e=<edition> (EditionInfo.updateEdition).
        buildConfigField("String", "EDITION", "\"karte\"")
        // 0.6.9 Labor iteration of «unterwegs» (true only in laborKarte, the owner's test phone): the
        // «Melden» button on the measurement notification in every state, the motion detector's
        // thresholds for trams and trains, and the position as a second signal (src/laborKarte/kotlin).
        // The Online-Version keeps the 0.6.8 behaviour until this is released there.
        // 0.8.4: «Unterwegs aufnehmen» and the «Sehr laut» notification are Labor·Karte-only code
        // (src/laborKarte/kotlin/…/alert, …/ui/LoudAlerts.kt); the Online-Version has no measurement on
        // the road at all (src/nounterwegs stand-in), so this flag no longer gates anything public.
        buildConfigField("boolean", "LABOR_UNTERWEGS", "false")
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    // Four editions (see PRIVACY.md → «Editions» and «Labor-Build»):
    // - offline: the alternative for even more privacy («Offline-Version»). The public app WITHOUT
    //            internet: its manifest removes INTERNET and ACCESS_NETWORK_STATE, «Messwerte teilen»
    //            is switched off (UPLOAD_AVAILABLE = false). Same Kotlin sources as public
    //            (src/public/kotlin, see sourceSets below), so it contains no recording code either.
    // - public:  the «Online-Version», the default download (with the map, release asset lessnoise-karte.apk):
    //            INTERNET only for the opt-in «Messwerte teilen» and «Laut! Melden», and (0.6.3) the
    //            tab «Karte», which shows the website's map page while it is open; location only for
    //            one fix right after «Starten» of a shared measurement and per report
    //            (src/public/AndroidManifest.xml).
    //            offline and public share the applicationId ch.lessnoise.app and the release key:
    //            installing one over the other switches the edition and keeps the data.
    // - labor:   a diagnostics build that can record audio (event clips, continuous AAC) for
    //            debugging the event detector and the classifier. All recording code lives in
    //            src/labor/ only. Never published on the website. No network access.
    // - laborKarte: internal only (the owner's own test phone), never published anywhere: the
    //            Labor build (all of src/labor/ except its manifest, see sourceSets below) PLUS the
    //            opt-in «Messwerte teilen» with INTERNET / ACCESS_NETWORK_STATE
    //            (src/laborKarte/AndroidManifest.xml). Same app id as labor, so it installs over a
    //            Labor install and keeps its measurements and recordings. Audio is never uploaded:
    //            the upload code is the Karte edition's (src/main/.../upload/), which reads only the
    //            Room tables; UploadSourceIsolationTest fails if it touches files or the Labor code.
    flavorDimensions += "edition"
    productFlavors {
        create("offline") {
            dimension = "edition"
            // The default variant in Android Studio is the edition without network access.
            isDefault = true
            // Like Labor: no network access (src/offline/AndroidManifest.xml removes INTERNET and
            // ACCESS_NETWORK_STATE): no sharing card, no upload worker.
            buildConfigField("boolean", "UPLOAD_AVAILABLE", "false")
            buildConfigField("String", "EDITION", "\"offline\"")
        }
        create("public") {
            dimension = "edition"
            buildConfigField("String", "EDITION", "\"karte\"")
        }
        create("labor") {
            dimension = "edition"
            applicationId = "ch.lessnoise.labor"
            versionNameSuffix = "-labor"
            // Labor keeps no network access (its manifest removes INTERNET): no upload UI, no worker.
            buildConfigField("boolean", "UPLOAD_AVAILABLE", "false")
            buildConfigField("String", "EDITION", "\"labor\"")
        }
        create("laborKarte") {
            dimension = "edition"
            applicationId = "ch.lessnoise.labor"
            versionNameSuffix = "-labor-karte"
            // UPLOAD_AVAILABLE stays true (defaultConfig): «Messwerte teilen», off until switched on.
            buildConfigField("String", "EDITION", "\"labor-karte\"")
            buildConfigField("boolean", "LABOR_UNTERWEGS", "true")
        }
    }

    sourceSets {
        // The offline edition compiles the public edition's Kotlin (EditionUi, NoAudioTap provider:
        // no recording code) but NOT src/public/AndroidManifest.xml, whose INTERNET permission
        // belongs to the map edition only. Its own manifest is src/offline/AndroidManifest.xml.
        named("offline") {
            kotlin.srcDir("src/public/kotlin")
            kotlin.srcDir("src/nomap/kotlin")
        }
        // The tab «Karte» (0.6.3): the website's map page in a WebView (src/karte/.../MapScreen.kt),
        // compiled ONLY into the two editions with the map. The Offline and Labor builds get the
        // stand-in src/nomap/.../MapTab.kt (no tab, no WebView in their dex).
        // 0.8.4: src/karte/res (the widget «Laut! Melden», the shortcut's icon and labels) goes with
        // src/karte/kotlin into both editions with the map; each has its own res/xml/shortcuts.xml
        // (the shortcut's targetPackage is the literal application id).
        named("public") {
            kotlin.srcDir("src/karte/kotlin")
            res.srcDir("src/karte/res")
            // 0.6.9: the stand-in of the position signal, which only laborKarte has (src/laborKarte/kotlin);
            // the editions without the map never reach it (their MotionWatch is the src/nomap stand-in).
            kotlin.srcDir("src/noposition/kotlin")
            // 0.8.4: the stand-in of «Unterwegs» / «Sehr laut» (ui/LoudAlerts.kt): no measurement on the
            // road in the Online-Version; the real facade and alert/ are in src/laborKarte/kotlin.
            kotlin.srcDir("src/nounterwegs/kotlin")
        }
        named("labor") {
            kotlin.srcDir("src/nomap/kotlin")
        }
        // laborKarte compiles the Labor edition's Kotlin and resources (recorder, clip player, red
        // icon, «Stadtlärm Labor») but NOT src/labor/AndroidManifest.xml, which removes INTERNET.
        // Its own manifest is src/laborKarte/AndroidManifest.xml. The Labor JVM tests run too.
        // src/labor/ has no other folders (no assets, java, resources); add them here if it gets any.
        named("laborKarte") {
            kotlin.srcDir("src/labor/kotlin")
            kotlin.srcDir("src/karte/kotlin")
            res.srcDir("src/labor/res")
            res.srcDir("src/karte/res")
        }
        named("testLaborKarte") {
            kotlin.srcDir("src/testLabor/kotlin")
            kotlin.srcDir("src/testKarte/kotlin")
        }
        // JVM tests of src/karte (map, compass, motion detector, 0.8.4 shortcut and widget), run for both
        // editions with the map. Since 0.8.4 the «Sehr laut» / «Unterwegs» tests (LoudAlertTest,
        // ReportTapTest, MotionAlertTest) are in src/testLaborKarte, with the code they test.
        named("testPublic") {
            kotlin.srcDir("src/testKarte/kotlin")
        }
    }

    buildTypes {
        // ABI filters are set per build type (AGP merges them with defaultConfig, so they
        // cannot be narrowed there). 32-bit x86 is never shipped.
        debug {
            // Phones (arm64/armv7) and the x86_64 emulator.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
        release {
            // Shrinking stays off for now: LiteRT uses reflection/JNI and has not been tested
            // with R8 rules yet.
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            // Phones only: the x86_64 emulator ABI is left out of the release APK to cut size.
            // (Debug builds keep it for the emulator.)
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // The model is memory-mapped by the interpreter, so it must be stored uncompressed.
        noCompress += "tflite"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":dsp"))
    implementation(project(":chart"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.google.ai.edge.litert:litert:1.4.0")

    // Scheduling of the opt-in upload (hourly + after a measurement stops). AndroidX, Apache-2.0.
    // It does no networking itself; not an analytics or crash-reporting SDK.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // 0.8.2: the tab «Karte» (src/karte, editions with the map only) follows the app's dark theme:
    // WebSettingsCompat's algorithmic-darkening / force-dark switches. AndroidX, Apache-2.0, no
    // networking of its own. Not in the Offline and Labor builds, which have no WebView.
    "publicImplementation"("androidx.webkit:webkit:1.12.1")
    "laborKarteImplementation"("androidx.webkit:webkit:1.12.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    // JVM SQLite to check the Room migration SQL without a device.
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
}

tasks.withType<Test>().configureEach {
    // testPublicDebugUnitTest → generated/ksp/publicDebug/…
    val variant = name.removePrefix("test").removeSuffix("UnitTest").replaceFirstChar { it.lowercase() }
    systemProperty(
        "stadtlaerm.generatedDb",
        layout.buildDirectory.dir("generated/ksp/$variant/kotlin/ch/lessnoise/app/data").get().asFile.absolutePath,
    )
}
