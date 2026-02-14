package com.samluiz.gyst.desktop

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.samluiz.gyst.app.GystRoot
import com.samluiz.gyst.db.GystDatabase
import com.samluiz.gyst.di.initKoin
import com.samluiz.gyst.domain.service.GoogleAuthSyncService
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager

fun main() {
    val homeDir = Path.of(System.getProperty("user.home"), ".gyst")
    val dbPath = homeDir.resolve("gyst.db")
    val backupPath = homeDir.resolve("backup").resolve("gyst-backup.db")
    initKoin(platformModule = module {
        single<SqlDriver> {
            Files.createDirectories(homeDir)
            openDesktopDriver(dbPath, backupPath)
        }
        single<GoogleAuthSyncService> { DesktopGoogleAuthSyncService(dbPath = dbPath, backupPath = backupPath) }
    })

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Gyst",
            icon = painterResource("app_icon.png"),
        ) {
            GystRoot()
        }
    }
}

private fun openDesktopDriver(dbPath: Path, backupPath: Path): SqlDriver {
    Files.createDirectories(dbPath.parent)
    ensureDesktopDbHealth(dbPath, backupPath)
    return runCatching {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        val currentVersion = readUserVersion(dbPath)
        val targetVersion = GystDatabase.Schema.version
        when {
            currentVersion <= 0L -> GystDatabase.Schema.create(driver)
            currentVersion < targetVersion -> GystDatabase.Schema.migrate(driver, currentVersion, targetVersion)
        }
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        driver
    }.getOrElse { error ->
        backupDbFiles(dbPath, backupPath, "desktop_migration_failed")
        deleteDbFiles(dbPath)
        val fresh = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        GystDatabase.Schema.create(fresh)
        fresh.execute(null, "PRAGMA foreign_keys=ON", 0)
        fresh
    }
}

private fun ensureDesktopDbHealth(dbPath: Path, backupPath: Path) {
    if (!Files.exists(dbPath)) return
    val healthy = runCatching {
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA quick_check(1)").use { rs ->
                    rs.next() && rs.getString(1).equals("ok", ignoreCase = true)
                }
            }
        }
    }.getOrDefault(false)
    if (!healthy) {
        backupDbFiles(dbPath, backupPath, "desktop_quick_check_failed")
        deleteDbFiles(dbPath)
    }
}

private fun readUserVersion(dbPath: Path): Long {
    if (!Files.exists(dbPath)) return 0L
    return runCatching {
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }
    }.getOrDefault(0L)
}

private fun backupDbFiles(dbPath: Path, backupPath: Path, reason: String) {
    runCatching { Files.createDirectories(backupPath.parent) }
    val stamp = System.currentTimeMillis()
    listOf(
        dbPath,
        Path.of("${dbPath}-wal"),
        Path.of("${dbPath}-shm"),
    ).forEach { source ->
        if (!Files.exists(source)) return@forEach
        val target = backupPath.parent.resolve("${source.fileName}.$reason.$stamp.bak")
        runCatching {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private fun deleteDbFiles(dbPath: Path) {
    listOf(
        dbPath,
        Path.of("${dbPath}-wal"),
        Path.of("${dbPath}-shm"),
    ).forEach { source ->
        runCatching { Files.deleteIfExists(source) }
    }
}
