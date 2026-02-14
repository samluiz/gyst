import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.JavaExec

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val appVersionName = rootProject.extra["appVersionName"].toString().removePrefix("v")

kotlin {
    jvm("desktop")

    sourceSets {
        getByName("desktopMain").dependencies {
            implementation(projects.shared)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.core)
            implementation(libs.sqldelight.jvm.driver)
            implementation(libs.google.api.client.jvm)
            implementation(libs.google.oauth.client.java6)
            implementation(libs.google.oauth.client.jetty)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.samluiz.gyst.desktop.MainKt"
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Gyst"
            packageVersion = appVersionName
            vendor = "Gyst"
            description = "Gyst personal finance and planning app"
            modules(
                "java.sql",
                "java.naming",
            )
            windows {
                iconFile.set(rootProject.file("docs/assets/favicon/favicon.ico"))
                menuGroup = "Gyst"
                upgradeUuid = "f89dcceb-d8fc-4b8a-82d9-b9e3476db48c"
            }
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    if (name == "desktopRun" || name == "run" || name == "hotRunDesktop") {
        mainClass.set("com.samluiz.gyst.desktop.MainKt")
    }
}
