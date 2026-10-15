package io.github.leoalfaroc.journal.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
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

    @Upsert
    suspend fun put(entry: Entry)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [Entry::class], version = 1, exportSchema = true)
abstract class JournalDb : RoomDatabase() {
    abstract fun entries(): EntryDao

    companion object {
        /** Permanent: the queue of an installed app is found under this name. */
        const val NAME = "journal.db"
    }
}
