package com.scarlett.luobi

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.WorkManager

/**
 * 1.18.1：启动保护。上次启动没撑过几秒就闪退（或记录到未捕获异常）→ 这次启动先清掉
 * 后台生成 / 校对的「进行中」状态和进度卡，并关闭背景模糊，避免闪退循环。已存进案头的文章不动。
 */
object CrashGuard {
    @Volatile var recovered = false; private set
    private const val K_LAUNCHING = "launching"; private const val K_CRASHED = "crashed"
    private fun p(c: Context) = c.getSharedPreferences("guard", Context.MODE_PRIVATE)
    private var installed = false

    fun onLaunch(c: Context) {
        val app = c.applicationContext
        try {
            val sp = p(app)
            val bad = sp.getBoolean(K_LAUNCHING, false) || sp.getBoolean(K_CRASHED, false) || sp.getInt("ver", 0) < 24
            if (bad) { recovered = true; reset(app) }
            sp.edit().putBoolean(K_LAUNCHING, true).putBoolean(K_CRASHED, false).putInt("ver", BuildConfig.VERSION_CODE).commit()
        } catch (_: Throwable) {}
        if (!installed) {
            installed = true
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try { p(app).edit().putBoolean(K_CRASHED, true).commit() } catch (_: Throwable) {}
                prev?.uncaughtException(t, e)
            }
        }
    }

    /** 界面正常跑了 5 秒 → 认为这次启动是健康的 */
    fun healthyLater(c: Context) {
        val app = c.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({ try { p(app).edit().putBoolean(K_LAUNCHING, false).apply() } catch (_: Throwable) {} }, 5000)
    }

    /** 清掉进行中的生成 / 校对与进度卡（文章本身已在数据库里，不删） */
    const val FACT_CRASH = "上次应用异常退出，校对已中断，请重新检测"
    fun failFactJobs(c: Context) { Thread { runCatching { kotlinx.coroutines.runBlocking { AppDb.get(c).factJobs().failRunning(FACT_CRASH) } } }.apply { start(); join(3000) } }
    fun reset(c: Context) {
        try { val wm = WorkManager.getInstance(c); wm.cancelUniqueWork(CopyWorker.NAME); wm.cancelAllWorkByTag(FactWorker.TAG); wm.cancelAllWorkByTag(FactWorker::class.java.name); wm.pruneWork() } catch (_: Throwable) {}
        try { failFactJobs(c) } catch (_: Throwable) {}
        try { GenCard.clear(c) } catch (_: Throwable) {}
        try { c.getSharedPreferences("gen", Context.MODE_PRIVATE).edit().remove("card").remove("seen").commit() } catch (_: Throwable) {}
        try { CopyWorker.clearDoneNotice(c) } catch (_: Throwable) {}
    }
}
