import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // expect/actual classes are Beta in Kotlin 2.0; the project uses them deliberately.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.atomicfu)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.material)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.work)
            implementation(libs.androidx.glance)
        }
        getByName("androidUnitTest").dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

android {
    namespace = "com.diveintocrypto.android"
    compileSdk = 36

    // Release signing: environment variables first, then a local git-ignored
    // keystore.properties. A blank value counts as unset (CI exports an unset secret
    // as an empty string), so it falls through to the properties file.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
    fun signingValue(envName: String, propName: String): String? =
        System.getenv(envName)?.takeIf { it.isNotBlank() }
            ?: keystoreProps.getProperty(propName)?.takeIf { it.isNotBlank() }

    val finalStoreFile = signingValue("STORE_FILE", "storeFile")
    val finalStorePassword = signingValue("STORE_PASSWORD", "storePassword")
    val finalKeyAlias = signingValue("KEY_ALIAS", "keyAlias")
    val finalKeyPassword = signingValue("KEY_PASSWORD", "keyPassword")

    val hasSigningConfig = finalStoreFile != null &&
            finalStorePassword != null &&
            finalKeyAlias != null &&
            finalKeyPassword != null

    // Explicit opt-out for CI's unsigned smoke build (e.g. -PallowUnsignedRelease=true).
    val allowUnsignedRelease = project.findProperty("allowUnsignedRelease")?.toString() == "true"

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = rootProject.file(finalStoreFile!!)
                storePassword = finalStorePassword
                keyAlias = finalKeyAlias
                keyPassword = finalKeyPassword
            }
        }
    }

    // Never publish an unsigned release by accident: a release bundle/APK task in the
    // graph without a complete signing config fails the build with a clear message.
    // Kotlin DSL: Action<TaskExecutionGraph> exposes the graph as the receiver (`this`).
    gradle.taskGraph.whenReady {
        val releaseRequested = allTasks.any { task ->
            task.name.endsWith("Release") &&
                (task.name.startsWith("assemble") ||
                    task.name.startsWith("bundle") ||
                    task.name.startsWith("install"))
        }
        if (releaseRequested && !hasSigningConfig && !allowUnsignedRelease) {
            throw GradleException(
                "Release build requested without a complete signing config. Set STORE_FILE, " +
                    "STORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD (environment or keystore.properties), " +
                    "or pass -PallowUnsignedRelease=true for an unsigned smoke build."
            )
        }
    }

    defaultConfig {
        applicationId = "com.diveintocrypto.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // ScanBenchmark is opt-in: `-Ddive.bench=true` on the Gradle command line must reach the
    // unit-test JVMs, otherwise the benchmark's assumeTrue always skips it. Ordinary runs stay "false".
    tasks.withType<Test>().configureEach {
        systemProperty("dive.bench", System.getProperty("dive.bench") ?: "false")
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigningConfig) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}
