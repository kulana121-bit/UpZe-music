import java.util.Properties

// Release signing: keystore.properties (storeFile, storePassword, keyAlias, keyPassword).
// Lookup: -PkeystoreProperties=<path>, $MUSICSPOT_KEYSTORE_PROPERTIES, <root>/keystore.properties.
// Never commit the keystore or its properties.
val keystoreProperties = Properties().also { props ->
    listOfNotNull(
        providers.gradleProperty("keystoreProperties").orNull,
        System.getenv("MUSICSPOT_KEYSTORE_PROPERTIES"),
        rootProject.file("keystore.properties").path
    ).map(::File).firstOrNull { it.isFile }?.inputStream()?.use(props::load)
}
val releaseStoreFile: File? = keystoreProperties.getProperty("storeFile")?.let(::File)?.takeIf { it.isFile }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/*
 * NewPipeExtractor (GPL-3.0) ships org.schabi.newpipe.extractor.utils.Utils, and src/main/java carries a
 * patched copy (from OpenTune, see that file) that avoids URLEncoder.encode(String, Charset), which is
 * API 33+. Two definitions cannot both reach dexing, so the library's copy is stripped from its jar.
 * The artifact is resolved non-transitively for that; its dependencies are declared by hand below.
 */
val newPipeVersion = "v0.26.3"
val newPipeRaw: Configuration by configurations.creating { isTransitive = false; isCanBeConsumed = false }
dependencies { newPipeRaw("com.github.TeamNewPipe:NewPipeExtractor:$newPipeVersion") }
val newPipeStripped = tasks.register<org.gradle.api.tasks.bundling.Jar>("stripNewPipeExtractorUtils") {
    archiveFileName.set("NewPipeExtractor-$newPipeVersion-noutils.jar")
    destinationDirectory.set(layout.buildDirectory.dir("stripped-libs"))
    from(newPipeRaw.elements.map { jars -> jars.map { zipTree(it.asFile) } }) {
        exclude("org/schabi/newpipe/extractor/utils/Utils.class")
        exclude("org/schabi/newpipe/extractor/utils/Utils\$*.class")
    }
}

android {
    namespace = "com.ravi.musicspot"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.ravi.musicspot"
        minSdk = 26
        targetSdk = 37
        versionCode = 21
        versionName = "1.0.0"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = releaseStoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            // Never fall back to the debug key: a release build without the permanent keystore fails here
            // (unless -PunsignedRelease=true is passed explicitly, which produces an unsigned APK).
            val unsigned = providers.gradleProperty("unsignedRelease").orNull == "true"
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
            else if (!unsigned && gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) && !it.contains("UnitTest") })
                throw GradleException("Release signing keystore not found. Set MUSICSPOT_KEYSTORE_PROPERTIES or pass -PunsignedRelease=true.")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // v2.0.5: NewPipeExtractor calls Java 10+ library APIs (Collectors.toUnmodifiableList, List.of, ...) that
        // Android 10's ART lacks (NoSuchMethodError on Ravi's API 29 tablet). Core library desugaring rewrites them
        // to the bundled j$ backport on API < 33/34, as OpenTune does.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true; buildConfig = true }

    packaging { resources { excludes += setOf("META-INF/INDEX.LIST", "META-INF/*.version", "DebugProbesKt.bin", "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") } }

    androidResources { noCompress += listOf("ogg", "webp") }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
        // v2.0.1: Ravi's phone is Android 10 (API 29). Any un-guarded newer API is an error, never a warning.
        error += setOf("NewApi", "WrongConstant")
        xmlReport = true
        htmlReport = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric startup tests (StartupRobolectricTest) run the real app start on API 26/29/31 in the JVM.
        unitTests.isIncludeAndroidResources = true
        // Live smoke tests (LiveSmokeTest) run only with MUSICSPOT_LIVE=1; forward the build's proxy/trust store to them.
        unitTests.all { test ->
            listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort", "http.nonProxyHosts",
                "javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword")
                .forEach { k -> System.getProperty(k)?.let { test.systemProperty(k, it) } }
            // Robolectric fetches its android-all jars through the same proxy / Maven Central mirror.
            System.getProperty("https.proxyHost")?.let { test.systemProperty("robolectric.dependency.proxy.host", it) }
            System.getProperty("https.proxyPort")?.let { test.systemProperty("robolectric.dependency.proxy.port", it) }
            test.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
            test.maxHeapSize = "2g"
            // Graph and Media3's session registry are process-wide singletons: one fresh JVM per test class.
            test.forkEvery = 1
            test.testLogging { events("failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.palette:palette-ktx:1.0.0")

    val media3 = "1.8.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-database:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("com.google.guava:guava:33.3.1-android")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Artwork loading
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    // Liquid glass: Kyant Backdrop (lens refraction, API 33+ RuntimeShader) with Haze blur as the fallback (API 31+).
    implementation("io.github.kyant0:backdrop:2.0.0-alpha03")
    implementation("dev.chrisbanes.haze:haze:1.7.1")

    // YouTube stream extraction fallback (GPL-3.0) and its dependencies, declared by hand (see newPipeStripped)
    implementation(files(newPipeStripped))
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.google.protobuf:protobuf-javalite:4.35.0")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.mozilla:rhino-engine:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
