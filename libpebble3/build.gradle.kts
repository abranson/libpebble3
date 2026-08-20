import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlinx.atomicfu)
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/pebble-dev/libpebblecommon")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

room {
    schemaDirectory("schema")
}

// Non-mac machines cannot build iOS targets, so disable them
val enableIosTarget = System.getProperty("os.name").contains("mac", ignoreCase = true)

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())

    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    android {
        namespace = "io.rebble.libpebblecommon"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.valueOf("JVM_${libs.versions.jvm.toolchain.get()}"))
        }

        androidResources {
            enable = true
        }

        withHostTestBuilder {}

        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        optimization {
            consumerKeepRules.file("consumer-rules.pro")
        }
    }

    // Generic-Linux library target. Applications provide their own entrypoint
    // and distribution packaging.
    jvm()

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { target ->
        target.binaries.framework {
            baseName = "libpebble3"
        }
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.ExperimentalUnsignedTypes")
                optIn("kotlin.ExperimentalStdlibApi")
                optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("kotlin.time.ExperimentalTime")
                optIn("kotlinx.coroutines.FlowPreview")
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
                optIn("kotlinx.serialization.ExperimentalSerializationApi")
                optIn("kotlinx.serialization.ExperimentalSerializationApi")
                optIn("kotlinx.cinterop.BetaInteropApi")
            }
        }
        commonMain {
            kotlin {
                // Include ksp-generated common code (from our :blobdgen processor)
                srcDir("build/generated/ksp/metadata/commonMain/kotlin")
            }
        }
        commonMain.dependencies {
            api(libs.coroutines)
            implementation(libs.serialization)
            implementation(libs.kermit)
            implementation(libs.room.runtime)
            api(libs.room.paging)
            implementation(libs.sqlite.bundled)
            api(libs.kotlinx.io.core)
            implementation(libs.kotlinx.io.okio)
            implementation(libs.okio)
            implementation(libs.kable)
            implementation(libs.kmpio)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.network)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
            api(libs.kotlinx.datetime)
            implementation(libs.koin.core)
            implementation(libs.compose.ui)
            implementation(project(":blobannotations"))
            implementation(libs.settings)
            implementation(libs.settings.serialization)
            implementation(libs.uri)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.pebblekit)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.ktor.websockets)
        }

        jvmMain.dependencies {
            // Linux desktop GATT server + pairing/adapter state over BlueZ D-Bus.
            // (Kable's JVM/btleplug backend for the central role comes transitively via libs.kable.)
            implementation(libs.bluez.dbus)
            // HttpClient() engine for the JVM target (android uses OkHttp, ios uses Darwin).
            implementation(libs.ktor.cio)
            // Client-side WebSockets plugin for the PKJS WebSocketManager (mirrors iosMain).
            implementation(libs.ktor.websockets)
            // dbus-java discovers its transport via ServiceLoader; without this module every
            // D-Bus connection fails with TransportRegistrationException.
            implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.0")
            // dbus-java logs internal errors (e.g. signal demarshalling failures in its
            // receiver threads) via slf4j; without a backend those vanish into the NOP logger.
            implementation("org.slf4j:slf4j-simple:2.0.16")
            // PKJS runtime: GraalJS (Truffle) — embedded in the GraalVM native image;
            // interpreted (slow but fine) when running on a stock JVM.
            implementation("org.graalvm.polyglot:polyglot:25.0.0")
            implementation("org.graalvm.polyglot:js-community:25.0.0")
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.test.junit)
            implementation(libs.ktor.websockets)
            implementation(libs.ktor.cio)
            implementation(libs.ktor.client.okhttp)
        }

        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.rules)
            implementation(libs.androidx.monitor)
        }

        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.test.junit)
            implementation(libs.coroutines.test)
        }
    }
}


// Otherwise it doesn't trigger our blobdbgen processor when compiling code
// https://github.com/google/ksp/issues/567
tasks.withType<KotlinCompilationTask<*>>().all {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

afterEvaluate {
    tasks.named("kspKotlinJvm") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
    // Room's generated jvm actual for DatabaseConstructor fails expect/actual matching
    // (identical android codegen compiles fine — KSP/K2 quirk). Room on JVM falls back to
    // reflectively instantiating Database_Impl, so the constructor object isn't needed.
    tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlinJvm") {
        exclude("**/DatabaseConstructor.kt")
    }
    tasks.named("kspAndroidMain") {
        dependsOn("kspCommonMainKotlinMetadata")
    }

    if (enableIosTarget) {
        tasks.named("kspKotlinIosArm64") {
            dependsOn("kspCommonMainKotlinMetadata")
        }
        tasks.named("kspKotlinIosSimulatorArm64") {
            dependsOn("kspCommonMainKotlinMetadata")
        }
    }
}

dependencies {
//    add("kspCommonMainMetadata", libs.room.compiler)
    // Room codegen for the JVM/Linux target — without this Database_Impl doesn't exist.
    add("kspJvm", libs.room.compiler)
    add("kspCommonMainMetadata", project(":blobdbgen"))
    add("kspAndroid", libs.room.compiler)

    if (enableIosTarget) {
        add("kspIosArm64", libs.room.compiler)
        add("kspIosSimulatorArm64", libs.room.compiler)
    }
}

/*project.afterEvaluate {
    tasks.withType(PublishToMavenRepository::class.java) {
        onlyIf {
            !publication.name.contains("ios")
        }
    }
    tasks.withType(Jar::class.java) {
        onlyIf {
            !name.contains("ios")
        }
    }
}*/

