package com.nvmex.networkhelper.util.map

import android.util.Log
import com.nvmex.networkhelper.BuildConfig
import com.nvmex.networkhelper.xposed.logger.Logger

/** Debug recordings are diagnosable with adb logcat -s DriveTest; no coordinates or radio IDs. */
internal fun driveLog(message: String) {
    runCatching {
        if (BuildConfig.DEBUG) Log.i("DriveTest", message) else Logger.log("DriveTest $message")
    }
}
