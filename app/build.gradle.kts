import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.serialization)
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    id("androidx.room")
    id("org.jetbrains.kotlinx.kover")
}

// --- shiroikuma-kxkb fork: signing + versioning (see gradle.properties + build-apk skill) ---
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val forkVersionName = "${project.property("VERSION_NAME")}+${project.property("BUILD_NUMBER")}"
val forkVersionCode = project.property("VERSION_CODE").toString().toInt() * 10000 +
    project.property("BUILD_NUMBER").toString().toInt()

base {
    archivesName = "shiroikuma-kxkb_${forkVersionName}_arm64-v8a"
}

android {
    namespace = project.property("APP_NAMESPACE").toString()
    compileSdk = 36

    defaultConfig {
        applicationId = project.property("APP_ID").toString()
        minSdk = 26
        targetSdk = 36
        versionCode = forkVersionCode
        versionName = forkVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Fork: the Whisper voice engine (ONNX Runtime + WebRTC VAD) ships native libs for
        // four ABIs; package arm64-v8a only (matches the APK filename convention).
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isDebuggable = false
            isJniDebuggable = false
            isShrinkResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        // Fork: don't let lintVitalRelease abort our release builds.
        checkReleaseBuilds = false
    }

    buildFeatures {
        viewBinding = true
        resValues = false
        shaders = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes +=
                setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/LICENSE.md",
                    "/META-INF/LICENSE-notice.md"
                )
        }
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true

            all {
                it.jvmArgs("-XX:+EnableDynamicAgentLoading")
                // Forward the DictDumpTool trigger into the forked test JVM (tools/clean_dictionaries.sh).
                System.getProperty("urik.dump")?.let { v -> it.systemProperty("urik.dump", v) }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

// --- shiroikuma-kxkb fork: compile the clean engine subset of the whisperIMEplus submodule ---
// Only com/whisperonnx/voice_translation/** (the UI-free ONNX Whisper recognizer) is compiled;
// the submodule's app/IME/recorder layer is re-implemented in Kotlin under service/voice/.
val whisperEngineDir = layout.buildDirectory.dir("generated/whisperEngine/java")
val syncWhisperEngine = tasks.register<Sync>("syncWhisperEngine") {
    description = "Copy the whisperIMEplus engine subset into generated sources."
    from(rootProject.layout.projectDirectory.dir("external/whisperIMEplus/app/src/main/java")) {
        include("com/whisperonnx/voice_translation/**")
    }
    into(whisperEngineDir)
}
android.sourceSets.getByName("main").java.srcDir(whisperEngineDir.get().asFile)
tasks.named("preBuild") {
    dependsOn(syncWhisperEngine)
}

ktlint {
    android.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("$rootDir/detekt.yml")
    parallel = true
    autoCorrect = false
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(true)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "*Fragment",
                    "*Fragment$*",
                    "*Activity",
                    "*Activity$*",
                    "*.databinding.*",
                    "*.BuildConfig",
                    "*_Factory",
                    "*_HiltModules*",
                    "*Hilt_*",
                    "dagger.hilt.*"
                )
            }
        }

        total {
            html {
                onCheck = true
            }
            xml {
                onCheck = true
            }
        }
    }
}

// --- shiroikuma-kxkb fork: build the release APK, copy to ~/tmp, bump BUILD_NUMBER ---
tasks.register("buildApk") {
    description = "Build the release APK, copy it to ~/tmp, and bump BUILD_NUMBER for next time."
    dependsOn("assembleRelease")
    // Capture project state at configuration time so the action is configuration-cache compatible.
    val fvName = forkVersionName
    val fvCode = forkVersionCode
    val releaseApkDir = layout.buildDirectory.dir("outputs/apk/release")
    val userHome = providers.systemProperty("user.home")
    val propsFile = rootProject.file("gradle.properties")
    val currentBuildNumber = project.property("BUILD_NUMBER").toString().toInt()
    doLast {
        val apkName = "shiroikuma-kxkb_${fvName}_arm64-v8a.apk"
        val outputDir = releaseApkDir.get().asFile
        val targetDir = File(userHome.get(), "tmp")
        targetDir.mkdirs()
        outputDir.listFiles { _, name -> name.endsWith(".apk") }?.firstOrNull()?.let { apk ->
            val targetFile = File(targetDir, apkName)
            apk.copyTo(targetFile, overwrite = true)
            println("[1;36m>>> ${targetFile.absolutePath}[0m")
            println("[1;36m>>> versionCode $fvCode[0m")
        } ?: throw GradleException("No APK found in $outputDir")

        // Auto-increment BUILD_NUMBER for the next build.
        val nextBuildNumber = currentBuildNumber + 1
        propsFile.writeText(
            propsFile.readText().replace(
                "BUILD_NUMBER=$currentBuildNumber",
                "BUILD_NUMBER=$nextBuildNumber"
            )
        )
        println("[1;36m>>> BUILD_NUMBER bumped to $nextBuildNumber[0m")
    }
}

dependencies {
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.biometric)
    implementation(libs.material)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    implementation(libs.androidx.core.ktx)
    ksp(libs.hilt.android.compiler)

    implementation(libs.androidx.preference.ktx)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.emoji2.emojipicker)

    implementation(libs.android.database.sqlcipher)
    implementation(libs.androidx.sqlite.ktx)

    implementation(libs.androidx.window)

    // In-app git for the Library archive (JGit 5.13 LTS — last Java-8 line, Android-safe at minSdk 26).
    implementation(libs.jgit)
    implementation(libs.slf4j.nop)

    // Whisper voice engine (whisperIMEplus submodule subset): ONNX Runtime inference,
    // Guava primitives used by TensorUtils, WebRTC VAD for auto-stop recording.
    implementation(libs.onnxruntime.android)
    implementation(libs.onnxruntime.extensions.android)
    implementation(libs.guava)
    implementation(libs.vad.webrtc)

    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.androidx.core.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.core.ktx)
}
