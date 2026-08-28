plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.aurora.protocol.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "org.aurora.protocol.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        buildConfig = false
    }

    buildTypes {
        release {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        disable += "AndroidGradlePluginVersion"
        warningsAsErrors = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

val nativeTaskEnvironmentNames = listOf(
    "ANDROID_HOME",
    "ANDROID_SDK_ROOT",
    "AURORA_ANDROID_NDK_HOME",
    "AURORA_CORE_DIR",
    "GOCACHE",
    "GOTOOLCHAIN",
)
val nativeTaskEnvironment = nativeTaskEnvironmentNames.associateWith { name ->
    providers.environmentVariable(name).getOrElse("")
}

val prepareNativeCore by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("sh", "${rootProject.projectDir}/scripts/build-native-core.sh")
    environment(nativeTaskEnvironment)
    inputs.properties(nativeTaskEnvironment.mapKeys { (name, _) -> "environment.$name" })
}

val verifyReleaseNativeTrust by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("sh", "${rootProject.projectDir}/scripts/verify-release-native-trust.sh")
    val trustEnvironment = nativeTaskEnvironment.filterKeys { name ->
        name in setOf("AURORA_CORE_DIR", "GOCACHE", "GOTOOLCHAIN")
    } + mapOf(
        "AURORA_RELEASE_TRUST_SHA256" to providers.environmentVariable("AURORA_RELEASE_TRUST_SHA256").getOrElse(""),
    )
    environment(trustEnvironment)
    inputs.properties(trustEnvironment.mapKeys { (name, _) -> "environment.$name" })
}

prepareNativeCore {
    // `clean` may otherwise delete app/build while this custom task recreates
    // generated Core outputs in the same directory.
    mustRunAfter(tasks.named("clean"))
    // When both tasks are present in a release graph, fail trust validation
    // before starting the more expensive two-ABI native compilation.
    mustRunAfter(verifyReleaseNativeTrust)
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(prepareNativeCore)
    }
    if (name == "preReleaseBuild") {
        dependsOn(verifyReleaseNativeTrust)
    }
}
