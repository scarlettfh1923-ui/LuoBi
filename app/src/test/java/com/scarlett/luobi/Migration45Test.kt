package com.scarlett.luobi

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.scarlett.luobi.shots.TestApp::class)
class Migration45Test {
    @Test fun v4ToV5KeepsDataAndAddsCategory() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val f = ctx.getDatabasePath("mig45.db"); f.parentFile!!.mkdirs(); f.delete()
        SQLiteDatabase.openOrCreateDatabase(f, null).use { d ->
            d.execSQL("CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `createdAt` INTEGER NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `tags` TEXT NOT NULL, `favorite` INTEGER NOT NULL, `raw` TEXT NOT NULL, `cleaned` TEXT NOT NULL, `intent` TEXT NOT NULL, `templateId` INTEGER NOT NULL, `templateName` TEXT NOT NULL, `model` TEXT NOT NULL, `finalPrompt` TEXT NOT NULL, `rootId` INTEGER NOT NULL, `version` INTEGER NOT NULL, `instruction` TEXT NOT NULL, `images` TEXT NOT NULL DEFAULT '[]', `factReportJson` TEXT NOT NULL DEFAULT '')")
            d.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_rootId` ON `notes` (`rootId`)")
            d.execSQL("CREATE TABLE IF NOT EXISTS `templates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `desc` TEXT NOT NULL, `builtIn` INTEGER NOT NULL, `prompt` TEXT NOT NULL, `defaultModel` TEXT NOT NULL, `sort` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL DEFAULT 0)")
            d.execSQL("CREATE TABLE IF NOT EXISTS `clean_tpls` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `prompt` TEXT NOT NULL, `fields` TEXT NOT NULL, `builtIn` INTEGER NOT NULL, `active` INTEGER NOT NULL)")
            d.execSQL("CREATE TABLE IF NOT EXISTS `channels` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `method` TEXT NOT NULL, `authHeader` TEXT NOT NULL, `authPrefix` TEXT NOT NULL, `format` TEXT NOT NULL, `bodyTpl` TEXT NOT NULL, `note` TEXT NOT NULL)")
            d.execSQL("INSERT INTO notes (id,createdAt,title,body,tags,favorite,raw,cleaned,intent,templateId,templateName,model,finalPrompt,rootId,version,instruction,images,factReportJson) VALUES (1,5,'旧文','正文','影评',1,'','','{}',0,'豆瓣影评','m','',1,1,'','[]','{\"issues\":[]}')")
            d.version = 4
        }
        val db = Room.databaseBuilder(ctx, AppDb::class.java, "mig45.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).allowMainThreadQueries().build()
        runBlocking {
            val n = db.notes().get(1)!!
            assertEquals("旧文", n.title); assertEquals("正文", n.body); assertTrue(n.favorite); assertEquals("{\"issues\":[]}", n.factReportJson)
            assertEquals("", n.category); assertEquals("", n.categoryAi)
            db.notes().update(n.copy(category = "电影"))
            assertEquals(listOf(CategoryCount("电影", 1)), db.notes().categoryCounts())
        }
        assertEquals(5, db.openHelper.readableDatabase.version)
        db.close()
    }
}
