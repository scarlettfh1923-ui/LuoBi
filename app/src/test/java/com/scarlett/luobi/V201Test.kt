package com.scarlett.luobi

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2.0.1：Room 1→6 每一步迁移不丢数据；升级前自动备份；启动保护不误判；通知兜底不闪退 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35])
class V201Test {
    private val DB = "mig-test.db"
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDb::class.java)

    private fun seedV1(v: Int) = helper.createDatabase(DB, v).apply {
        execSQL("INSERT INTO notes (id,createdAt,title,body,tags,favorite,raw,cleaned,intent,templateId,templateName,model,finalPrompt,rootId,version,instruction) VALUES (7,1700000000000,'丁玲','正文第一段\n\n第二段','民国 文学',1,'原文','清洗','意图',2,'模板','deepseek','提示',7,1,'')")
        execSQL("INSERT INTO notes (id,createdAt,title,body,tags,favorite,raw,cleaned,intent,templateId,templateName,model,finalPrompt,rootId,version,instruction) VALUES (8,1700000001000,'丁玲·改','改写后','',0,'','','',2,'模板','deepseek','',7,2,'更口语')")
        execSQL("INSERT INTO templates (id,name,`desc`,builtIn,prompt,defaultModel,sort) VALUES (2,'模板','说明',0,'写一篇','',1)")
        execSQL("INSERT INTO clean_tpls (id,name,prompt,fields,builtIn,active) VALUES (3,'清洗','去广告','[]',0,1)")
        execSQL("INSERT INTO channels (id,name,url,method,authHeader,authPrefix,format,bodyTpl,note) VALUES (4,'我的接口','https://x.example','POST','Authorization','Bearer','json','{}','')")
        close()
    }

    private fun assertKept(v: Int) {
        val db = helper.runMigrationsAndValidate(DB, 6, true, *AppDb.MIGRATIONS)
        db.query("SELECT title, body, tags, favorite, rootId, version, images, factReportJson, category FROM notes ORDER BY id").use { c ->
            assertEquals("from v$v", 2, c.count); c.moveToFirst()
            assertEquals("丁玲", c.getString(0)); assertEquals("正文第一段\n\n第二段", c.getString(1)); assertEquals("民国 文学", c.getString(2)); assertEquals(1, c.getInt(3))
            assertEquals(if (v >= 2) "[]" else "[]", c.getString(6)); assertEquals("", c.getString(7)); assertEquals("", c.getString(8))
            c.moveToNext(); assertEquals("丁玲·改", c.getString(0)); assertEquals(7, c.getInt(4)); assertEquals(2, c.getInt(5))
        }
        db.query("SELECT COUNT(*) FROM templates").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM clean_tpls").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT name FROM channels").use { it.moveToFirst(); assertEquals("我的接口", it.getString(0)) }
        db.query("SELECT COUNT(*) FROM fact_jobs").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.close()
    }

    @Test fun migrate1to6() { seedV1(1); assertKept(1) }
    @Test fun migrate2to6() { seedV1(2); assertKept(2) }
    @Test fun migrate3to6() { seedV1(3); assertKept(3) }
    @Test fun migrate4to6() { seedV1(4); assertKept(4) }
    @Test fun migrate5to6() { seedV1(5); assertKept(5) }

    @Test fun eachStepValidates() {
        seedV1(1)
        helper.runMigrationsAndValidate(DB, 2, true, MIGRATION_1_2).close()
        helper.runMigrationsAndValidate(DB, 3, true, MIGRATION_2_3).close()
        helper.runMigrationsAndValidate(DB, 4, true, MIGRATION_3_4).close()
        helper.runMigrationsAndValidate(DB, 5, true, MIGRATION_4_5).close()
        helper.runMigrationsAndValidate(DB, 6, true, MIGRATION_5_6).close()
    }

    @Test fun backupBeforeUpgradeOnlyWhenOlder() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        seedV1(3)
        val f = ctx.getDatabasePath(DB); assertEquals(3, DbBackup.diskVersion(f))
        val b = DbBackup.beforeOpen(ctx, DB, 6); assertNotNull(b); assertTrue(b!!.exists()); assertEquals(3, DbBackup.diskVersion(b))
        assertEquals(f.length(), b.length())
        assertNull(DbBackup.beforeOpen(ctx, DB, 3))      // 已是当前版本：不备份
        assertNull(DbBackup.beforeOpen(ctx, "none.db", 6)) // 新装：不备份
    }

    @Test fun swipeAwayIsNotACrash() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sp = ctx.getSharedPreferences("guard", 0)
        sp.edit().putInt("ver", 26).putBoolean("launching", true).putBoolean("crashed", false).commit() // 上次 5 秒内被划掉
        CrashGuard.onLaunch(ctx); assertFalse(CrashGuard.recovered)
        sp.edit().putBoolean("crashed", true).commit() // 真闪退
        CrashGuard.onLaunch(ctx); assertTrue(CrashGuard.recovered)
    }

    @Test fun fallbackForegroundCreatesChannel() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(FactWorker.CH_PROGRESS); nm.deleteNotificationChannel("gen")
        val fi = Notif.fallback(ctx, 1, FactWorker.CH_PROGRESS, "正在校对…")
        assertNotNull(nm.getNotificationChannel(FactWorker.CH_PROGRESS)); assertNotNull(nm.getNotificationChannel("gen"))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, fi.foregroundServiceType)
    }
}
