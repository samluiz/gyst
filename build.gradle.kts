import kotlin.math.max

fun parseVersionCode(versionName: String): Int {
    val cleaned = versionName.removePrefix("v")
    val parts = cleaned.split(".")
    if (parts.size < 3) return 1
    val major = parts[0].toIntOrNull() ?: 0
    val minor = parts[1].toIntOrNull() ?: 0
    val patch = parts[2].takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    // 1.2.3 -> 10203
    return max(major * 10_000 + minor * 100 + patch, 1)
}

val resolvedVersionName: String = providers.gradleProperty("app.version").orNull
    ?: System.getenv("APP_VERSION")
    ?: System.getenv("GITHUB_REF_NAME")?.removePrefix("refs/tags/")
    ?: "1.0.0"
val resolvedVersionCode: Int = providers.gradleProperty("app.versionCode").orNull?.toIntOrNull()
    ?: parseVersionCode(resolvedVersionName)

allprojects {
    version = resolvedVersionName
    extensions.extraProperties["appVersionName"] = resolvedVersionName
    extensions.extraProperties["appVersionCode"] = resolvedVersionCode
}

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.sqldelight) apply false
}
