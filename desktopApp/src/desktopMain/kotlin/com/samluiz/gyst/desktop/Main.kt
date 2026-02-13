package com.samluiz.gyst.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.samluiz.gyst.app.GystRoot
import com.samluiz.gyst.db.GystDatabase
import com.samluiz.gyst.di.initKoin
import com.samluiz.gyst.domain.service.GoogleAuthSyncService
import com.samluiz.gyst.domain.service.NoOpGoogleAuthSyncService
import org.koin.dsl.module

fun main() {
    initKoin(platformModule = module {
        single<SqlDriver> {
            JdbcSqliteDriver("jdbc:sqlite:gyst.db").also {
                GystDatabase.Schema.create(it)
            }
        }
        single<GoogleAuthSyncService> { NoOpGoogleAuthSyncService() }
    })

    application {
        Window(onCloseRequest = ::exitApplication, title = "Gyst") {
            GystRoot()
        }
    }
}
