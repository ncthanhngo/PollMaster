package com.ncthanhngo.pollmaster

import android.app.Application
import kotlinx.coroutines.launch

class PollMasterApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scope.launch {
            container.templates.installBundled()
            container.projects.migrate()
            container.records.refresh()
        }
    }
}
