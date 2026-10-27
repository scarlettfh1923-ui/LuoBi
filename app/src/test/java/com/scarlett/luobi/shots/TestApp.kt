package com.scarlett.luobi.shots
import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
class TestApp : Application() {
    override fun onCreate() { super.onCreate(); com.scarlett.luobi.AppDb.resetForTest(); deleteDatabase(com.scarlett.luobi.AppDb.NAME); com.scarlett.luobi.Secure.overrideForTest(getSharedPreferences("t", 0)); com.scarlett.luobi.InspireSeed.seed(this); runCatching { WorkManager.initialize(this, Configuration.Builder().setExecutor { it.run() }.build()) } }
}
