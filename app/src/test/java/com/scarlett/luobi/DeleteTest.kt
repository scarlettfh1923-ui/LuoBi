package com.scarlett.luobi

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.scarlett.luobi.shots.TestApp::class)
class DeleteTest {
    private fun db() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java).allowMainThreadQueries().build()
    private fun note(t: String) = Note(title = t, body = "正文$t", tags = "")

    @Test fun deleteSingleFromLatest() = runBlocking {
        val db = db(); val d = db.notes()
        listOf("a", "b").forEach { val n = note(it); val id = d.insert(n); d.update(n.copy(id = id, rootId = id)) }
        val shown = d.latest().first(); assertEquals(2, shown.size)
        val target = shown.first { it.title == "b" }
        d.deleteRoots(listOf(target.root))
        val after = d.latest().first()
        assertEquals(listOf("a"), after.map { it.title })
    }

    /** 1.7 真机 bug：rootId=0 的旧行，提示「已删除」但还在列表里 */
    @Test fun deleteLegacyRootZero() = runBlocking {
        val db = db(); val d = db.notes()
        d.insert(note("合肥")); d.insert(note("鹤岗"))          // rootId 默认 0
        val target = d.latest().first().first { it.title == "鹤岗" }
        d.deleteRoots(listOf(target.root))
        assertEquals(listOf("合肥"), d.latest().first().map { it.title })
    }
    @Test fun repairRootsFixesLegacy() = runBlocking {
        val db = db(); val d = db.notes()
        d.insert(note("x")); d.repairRoots()
        val n = d.latest().first().single(); assertEquals(n.id, n.rootId)
    }
}
