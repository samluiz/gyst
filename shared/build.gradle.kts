import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(17)

    androidLibrary {
        namespace = "com.samluiz.gyst.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources {
            enable = true
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm("desktop")
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.lifecycle.viewmodel.compose)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)

            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.koin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        getByName("desktopMain").dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.sqldelight.jvm.driver)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
    }
}

val generatedBuildInfoDir = layout.buildDirectory.dir("generated/source/buildInfo/kotlin")

abstract class GenerateBuildInfoTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val packageDir = File(outputDir.get().asFile, "com/samluiz/gyst/app")
        val outputFile = File(packageDir, "BuildInfo.kt")
        val content = """
            package com.samluiz.gyst.app

            object BuildInfo {
                const val VERSION_NAME: String = "${versionName.get()}"
                const val VERSION_CODE: Int = ${versionCode.get()}
            }
            """.trimIndent()
        packageDir.mkdirs()
        if (!outputFile.exists() || outputFile.readText() != content) {
            outputFile.writeText(content)
        }
    }
}

val generateBuildInfo by tasks.registering(GenerateBuildInfoTask::class) {
    outputDir.set(generatedBuildInfoDir)
    versionName.set(rootProject.extra["appVersionName"].toString())
    versionCode.set(rootProject.extra["appVersionCode"].toString().toInt())
}

kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(generatedBuildInfoDir)
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateBuildInfo)
}

tasks.matching { it.name == "compileAndroidMain" }.configureEach {
    dependsOn(generateBuildInfo)
}

configurations
    .matching { config ->
        config.name.contains("desktop", ignoreCase = true) &&
            config.name.contains("CompileClasspath")
    }
    .configureEach {
        attributes {
            attribute(
                org.gradle.api.attributes.Attribute.of(
                    "org.jetbrains.kotlin.platform.type",
                    KotlinPlatformType::class.java,
                ),
                KotlinPlatformType.jvm,
            )
        }
    }

sqldelight {
    databases {
        create("GystDatabase") {
            packageName.set("com.samluiz.gyst.db")
        }
    }
}
