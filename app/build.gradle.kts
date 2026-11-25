plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.myrunningapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.myrunningapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes from the environment so the key never enters the
    // repo. Without it, assembleRelease still runs but produces an unsigned APK.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE_PATH")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // Built-in Kotlin derives jvmTarget from targetCompatibility below when
    // not set explicitly, so there is no separate compilerOptions block.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("androidTest").assets.directories += "$projectDir/schemas"
        getByName("test").resources.directories += "$projectDir/schemas"
    }
}

ksp {
    // Exported Room schemas — checked in, used by migration tests.
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Robolectric downloads its Android runtime jar from Maven Central the first
// time a test needs it, outside Gradle and so outside CI's Gradle cache. Every
// CI run fetched it afresh, and Maven Central rate-limits shared runners (HTTP
// 429), failing whichever test classes asked first. Resolving the jar here puts
// it in Gradle's cache, and offline mode has Robolectric use that copy instead
// of fetching. Locally it behaves the same: one download, then cached.
val robolectricRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

tasks.withType<Test>().configureEach {
    val runtimeJars: FileCollection = robolectricRuntime
    inputs.files(runtimeJars).withPropertyName("robolectricRuntime").withPathSensitivity(PathSensitivity.NAME_ONLY)
    jvmArgumentProviders += CommandLineArgumentProvider {
        // Gradle stores each artifact as <hash dir>/<artifactId>-<version>.jar,
        // the name Robolectric's offline mode looks for in its dependency dir.
        listOf(
            "-Drobolectric.offline=true",
            "-Drobolectric.dependency.dir=${runtimeJars.singleFile.parentFile.absolutePath}",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.play.services.location)
    implementation(libs.osmdroid.android)

    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Forces a version compatible with androidx.test.ext:junit/espresso-core's
    // transitive requirement; otherwise AGP's android.dependency.useConstraints
    // strictly pins the androidTest classpath to whatever an older transitive
    // dependency resolved on the main classpath.
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.concurrent.futures.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    robolectricRuntime(libs.robolectric.android.all)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
