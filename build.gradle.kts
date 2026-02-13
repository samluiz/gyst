import kotlin.math.max

fun normalizeSemVerOrNull(raw: String?): String? {
    val candidate = raw?.trim()?.removePrefix("refs/tags/")?.removePrefix("v") ?: return null
    val core = candidate.substringBefore('-')
    val parts = core.split(".")
    if (parts.size < 3) return null
    val major = parts[0].toIntOrNull() ?: return null
    val minor = parts[1].toIntOrNull() ?: return null
    val patch = parts[2].takeWhile { it.isDigit() }.toIntOrNull() ?: return null
    if (major <= 0 || minor < 0 || patch < 0) return null
    return "$major.$minor.$patch"
}

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

val resolvedVersionName: String =
    normalizeSemVerOrNull(providers.gradleProperty("app.version").orNull)
        ?: normalizeSemVerOrNull(System.getenv("APP_VERSION"))
        ?: normalizeSemVerOrNull(System.getenv("GITHUB_REF_NAME"))
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
