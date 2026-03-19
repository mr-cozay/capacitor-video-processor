package com.gme.plugins.capacitor.videoprocessor

import android.util.Log

class CapacitorVideoProcessorPlugin {

    fun echo(value: String?): String? {
        Log.i("Echo", value ?: "null")

        return value
    }
}
