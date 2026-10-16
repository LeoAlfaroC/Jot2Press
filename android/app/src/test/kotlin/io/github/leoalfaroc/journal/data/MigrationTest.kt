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
import org.junit.Assert.assertFalse
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

    /** The tables and indices of an earlier version, as Room exported them when that version shipped. */
    private fun schema(version: Int): List<String> {
        val database = Json.parseToJsonElement(File("schemas/io.github.leoalfaroc.journal.data.JournalDb/$version.json").readText())
            .jsonObject.getValue("database").jsonObject
        return database.getValue("entities").jsonArray.flatMap {
            val entity = it.jsonObject
            val table = entity.getValue("tableName").jsonPrimitive.content
            val indices = entity["indices"]?.jsonArray.orEmpty().map { index -> index.jsonObject.getValue("createSql").jsonPrimitive.content }
            (listOf(entity.getValue("createSql").jsonPrimitive.content) + indices).map { sql -> sql.replace("\${TABLE_NAME}", table) }
        }
    }

    private fun migrated(): JournalDb = Room.databaseBuilder(context, JournalDb::class.java, name)
        .addMigrations(JournalDb.MIGRATION_1_2, JournalDb.MIGRATION_2_3)
        .allowMainThreadQueries()
        .build()

    @Test
    fun `a version 1 queue opens as the current version with its draft and its pending entry`() {
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            schema(1).forEach(db::execSQL)
            db.execSQL("INSERT INTO entries (id, text, state) VALUES ('d', 'Unfinished', 'DRAFT')")
            db.execSQL(
                "INSERT INTO entries (id, text, state, writtenAt, writtenAtMillis) " +
                    "VALUES ('p', 'Coffee with Ana', 'PENDING', '2026-10-11T14:32:05-05:00', 1791748325000)",
            )
            db.version = 1
        }

        val migrated = migrated()
        try {
            val entries = runBlocking { migrated.entries().observeAll().first() }.associateBy { it.id }
            assertEquals(EntryState.DRAFT, entries.getValue("d").state)
            assertEquals("Unfinished", entries.getValue("d").text)
            assertEquals(EntryState.PENDING, entries.getValue("p").state)
            assertEquals("2026-10-11T14:32:05-05:00", entries.getValue("p").writtenAt)
            assertFalse(entries.getValue("p").attachLocation)
            assertNull(entries.getValue("p").lat)
            assertNull(entries.getValue("p").lastErrorCode)
            assertTrue(runBlocking { migrated.photos().of("p") }.isEmpty())
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `a version 2 queue opens as version 3 with its draft, its photo and its pending entry, none attaching a location`() {
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            schema(2).forEach(db::execSQL)
            db.execSQL("INSERT INTO entries (id, text, state) VALUES ('d', 'Unfinished', 'DRAFT')")
            db.execSQL("INSERT INTO photos (id, entryId, position, file, uploaded) VALUES ('ph', 'd', 0, 'ph.jpg', 0)")
            db.execSQL(
                "INSERT INTO entries (id, text, state, writtenAt, writtenAtMillis) " +
                    "VALUES ('p', 'Coffee with Ana', 'PENDING', '2026-10-11T14:32:05-05:00', 1791748325000)",
            )
            db.version = 2
        }

        val migrated = migrated()
        try {
            val entries = runBlocking { migrated.entries().observeAll().first() }.associateBy { it.id }
            assertEquals(EntryState.DRAFT, entries.getValue("d").state)
            assertEquals("Unfinished", entries.getValue("d").text)
            assertEquals(EntryState.PENDING, entries.getValue("p").state)
            assertEquals("2026-10-11T14:32:05-05:00", entries.getValue("p").writtenAt)
            assertFalse(entries.getValue("d").attachLocation)
            assertFalse(entries.getValue("p").attachLocation)
            assertNull(entries.getValue("p").lat)
            assertEquals(listOf("ph.jpg"), runBlocking { migrated.photos().of("d") }.map { it.file })
        } finally {
            migrated.close()
        }
    }
}
