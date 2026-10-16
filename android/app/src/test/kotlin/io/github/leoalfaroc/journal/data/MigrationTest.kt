package io.github.leoalfaroc.journal.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** An installed app's queue survives the update to the version with photos. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @After
    fun clean() {
        context.deleteDatabase(name)
    }

    /** The tables of version 1, as Room exported them when that version shipped. */
    private fun version1Tables(): List<String> {
        val database = Json.parseToJsonElement(File("schemas/io.github.leoalfaroc.journal.data.JournalDb/1.json").readText())
            .jsonObject.getValue("database").jsonObject
        return database.getValue("entities").jsonArray.map {
            val entity = it.jsonObject
            entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", entity.getValue("tableName").jsonPrimitive.content)
        }
    }

    @Test
    fun `a version 1 queue opens as version 2 with its draft and its pending entry`() {
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            version1Tables().forEach(db::execSQL)
            db.execSQL("INSERT INTO entries (id, text, state) VALUES ('d', 'Unfinished', 'DRAFT')")
            db.execSQL(
                "INSERT INTO entries (id, text, state, writtenAt, writtenAtMillis) " +
                    "VALUES ('p', 'Coffee with Ana', 'PENDING', '2026-10-11T14:32:05-05:00', 1791748325000)",
            )
            db.version = 1
        }

        val migrated = Room.databaseBuilder(context, JournalDb::class.java, name)
            .addMigrations(JournalDb.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val entries = runBlocking { migrated.entries().observeAll().first() }.associateBy { it.id }
            assertEquals(EntryState.DRAFT, entries.getValue("d").state)
            assertEquals("Unfinished", entries.getValue("d").text)
            assertEquals(EntryState.PENDING, entries.getValue("p").state)
            assertEquals("2026-10-11T14:32:05-05:00", entries.getValue("p").writtenAt)
            assertNull(entries.getValue("p").lat)
            assertNull(entries.getValue("p").lastErrorCode)
            assertTrue(runBlocking { migrated.photos().of("p") }.isEmpty())
        } finally {
            migrated.close()
        }
    }
}
