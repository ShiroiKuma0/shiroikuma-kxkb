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
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
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
    doLast {
        val apkName = "shiroikuma-kxkb_${forkVersionName}_arm64-v8a.apk"
        val outputDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val targetDir = File(System.getProperty("user.home"), "tmp")
        targetDir.mkdirs()
        outputDir.listFiles { _, name -> name.endsWith(".apk") }?.firstOrNull()?.let { apk ->
            val targetFile = File(targetDir, apkName)
            apk.copyTo(targetFile, overwrite = true)
            println("[1;36m>>> ${targetFile.absolutePath}[0m")
            println("[1;36m>>> versionCode $forkVersionCode[0m")
        } ?: throw GradleException("No APK found in $outputDir")

        // Auto-increment BUILD_NUMBER for the next build.
        val propsFile = rootProject.file("gradle.properties")
        val currentBuildNumber = project.property("BUILD_NUMBER").toString().toInt()
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

    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.androidx.core.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.core.ktx)
}
