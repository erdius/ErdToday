package com.erdman.erdtoday

import android.app.Application
import com.erdman.erdtoday.di.AppContainer

/** Application entry point. Builds and holds the manual-DI [AppContainer]. */
class TodayApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.applyReminderChannel()
    }
}
