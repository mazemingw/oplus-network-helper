package com.nvmex.networkhelper.util.map

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Persistence tests do not need Root, the AMap SDK or the hotspot native initialization. */
class DriveDatabaseTestRunner : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader, className: String, context: Context): Application =
        super.newApplication(loader, Application::class.java.name, context)
}
