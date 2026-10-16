package io.github.leoalfaroc.journal.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries ORDER BY writtenAtMillis DESC, rowid DESC")
    fun observeAll(): Flow<List<Entry>>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun byId(id: String): Entry?

    @Query("SELECT * FROM entries WHERE state = 'DRAFT' LIMIT 1")
    suspend fun draft(): Entry?

    @Query("SELECT * FROM entries WHERE state != 'DRAFT' ORDER BY writtenAtMillis DESC, rowid DESC LIMIT 1")
    suspend fun latestSent(): Entry?

    @Query("SELECT * FROM entries WHERE state = 'PENDING' ORDER BY writtenAtMillis ASC, rowid ASC LIMIT 1")
    suspend fun nextPending(): Entry?

    @Query("SELECT * FROM entries WHERE state = 'GRACE'")
    suspend fun inGrace(): List<Entry>

    @Query("UPDATE entries SET state = 'PENDING', graceEndsAt = NULL WHERE state = 'GRACE' AND graceEndsAt <= :now")
    suspend fun promoteDue(now: Long): Int

    /** Writes only while the entry is in its grace window, so that it never races the sender. Returns the rows changed. */
    @Query("UPDATE entries SET lat = :lat, lng = :lng WHERE id = :id AND state = 'GRACE' AND graceEndsAt > :now")
    suspend fun place(id: String, lat: Double, lng: Double, now: Long): Int

    @Upsert
    suspend fun put(entry: Entry)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos WHERE entryId = :entryId ORDER BY position")
    suspend fun of(entryId: String): List<Photo>

    @Query("SELECT file FROM photos")
    suspend fun files(): List<String>

    @Query("SELECT entryId, COUNT(*) AS count FROM photos GROUP BY entryId")
    fun observeCounts(): Flow<List<PhotoCount>>

    @Insert
    suspend fun insert(photos: List<Photo>)

    @Query("DELETE FROM photos WHERE entryId = :entryId")
    suspend fun deleteOf(entryId: String)

    @Query("UPDATE photos SET uploaded = 1 WHERE id = :id")
    suspend fun markUploaded(id: String)

    @Query("UPDATE photos SET uploaded = 0 WHERE entryId = :entryId")
    suspend fun clearUploaded(entryId: String)
}

@Database(entities = [Entry::class, Photo::class], version = 3, exportSchema = true)
abstract class JournalDb : RoomDatabase() {
    abstract fun entries(): EntryDao
    abstract fun photos(): PhotoDao

    companion object {
        /** Permanent: the queue of an installed app is found under this name. */
        const val NAME = "journal.db"

        /** Adds photos, and the columns for location and for the code of a refusal. Keeps every entry. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `entries` ADD COLUMN `lat` REAL")
                db.execSQL("ALTER TABLE `entries` ADD COLUMN `lng` REAL")
                db.execSQL("ALTER TABLE `entries` ADD COLUMN `lastErrorCode` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `photos` (`id` TEXT NOT NULL, `entryId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                        "`file` TEXT NOT NULL, `uploaded` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`entryId`) REFERENCES `entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_entryId` ON `photos` (`entryId`)")
            }
        }

        /** Adds whether each entry sends its position. Keeps every entry and photo; none of them attaches one. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `entries` ADD COLUMN `attachLocation` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
