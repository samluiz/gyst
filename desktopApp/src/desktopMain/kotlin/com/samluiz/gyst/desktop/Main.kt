package com.samluiz.gyst.desktop

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.samluiz.gyst.app.GystRoot
import com.samluiz.gyst.data.repository.SqlDriverFactory
import com.samluiz.gyst.db.GystDatabase
import com.samluiz.gyst.di.initKoin
import com.samluiz.gyst.domain.service.GoogleAuthSyncService
import com.samluiz.gyst.logging.AppLogger
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager

fun main() {
    val homeDir = Path.of(System.getProperty("user.home"), ".gyst")
    val dbPath = homeDir.resolve("gyst.db")
    val backupPath = homeDir.resolve("backup").resolve("gyst-backup.db")
    val logsPath = homeDir.resolve("logs").resolve("app.log")
    AppLogger.addSink(DesktopFileLogSink(logsPath))
    AppLogger.i("DesktopMain", "Starting desktop app")
    initKoin(platformModule = module {
        single<SqlDriverFactory> {
            object : SqlDriverFactory {
                override fun createDriver(): SqlDriver = openDesktopDriver(dbPath, backupPath)
            }
        }
        single<SqlDriver> {
            Files.createDirectories(homeDir)
            get<SqlDriverFactory>().createDriver()
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
            currentVersion <= 0L && !hasAnyUserTables(dbPath) -> GystDatabase.Schema.create(driver)
            currentVersion <= 0L -> {
                if (hasExpectedSchema(dbPath)) {
                    setUserVersion(dbPath, targetVersion)
                    AppLogger.w(
                        "DesktopMain",
                        "Detected existing schema with user_version=0. Adopted DB by setting user_version=$targetVersion.",
                    )
                } else {
                    error("Existing database has user_version=0 but schema is incompatible.")
                }
            }
            currentVersion < targetVersion -> GystDatabase.Schema.migrate(driver, currentVersion, targetVersion)
        }
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        driver
    }.getOrElse { error ->
        val hasData = hasAnyUserTables(dbPath)
        if (hasData) {
            backupDbFiles(dbPath, backupPath, "desktop_migration_failed")
            throw IllegalStateException(
                "Desktop DB migration failed for non-empty database. " +
                    "A backup was created in ${backupPath.parent.toAbsolutePath()}. " +
                    "Refusing to overwrite existing data.",
                error,
            )
        }
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

private fun setUserVersion(dbPath: Path, version: Long) {
    DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
        conn.createStatement().use { st ->
            st.execute("PRAGMA user_version = $version")
        }
    }
}

private fun hasAnyUserTables(dbPath: Path): Boolean {
    if (!Files.exists(dbPath)) return false
    return runCatching {
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT COUNT(*) FROM sqlite_master
                    WHERE type='table'
                      AND name NOT LIKE 'sqlite_%'
                    """.trimIndent()
                ).use { rs ->
                    rs.next() && rs.getInt(1) > 0
                }
            }
        }
    }.getOrDefault(false)
}

private fun hasExpectedSchema(dbPath: Path): Boolean {
    if (!Files.exists(dbPath)) return false
    return runCatching {
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            val requiredTables = setOf(
                "category",
                "budget_month",
                "budget_allocation",
                "expense",
                "subscription",
                "installment_plan",
                "payment_schedule_item",
                "safety_guard",
                "app_setting",
            )
            val presentTables = conn.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rs ->
                    buildSet {
                        while (rs.next()) add(rs.getString(1))
                    }
                }
            }
            if (!requiredTables.all { it in presentTables }) return@runCatching false

            val expenseColumns = conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(expense)").use { rs ->
                    buildSet {
                        while (rs.next()) add(rs.getString("name"))
                    }
                }
            }
            setOf("recurrence_type", "schedule_item_id").all { it in expenseColumns }
        }
    }.getOrDefault(false)
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
