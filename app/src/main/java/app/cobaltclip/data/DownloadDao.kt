package app.cobaltclip.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadRecord>>

    @Insert
    suspend fun insert(item: DownloadRecord): Long

    @Query("SELECT * FROM downloads WHERE status='QUEUED' AND scheduledAt <= :now ORDER BY createdAt LIMIT 1")
    suspend fun nextReady(now: Long = System.currentTimeMillis()): DownloadRecord?

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('QUEUED', 'RESOLVING', 'DOWNLOADING')")
    suspend fun pendingCount(): Int

    @Query("UPDATE downloads SET status=:status, progress=:progress, filename=:filename, error=:error, mediaUri=:mediaUri, updatedAt=:updatedAt WHERE id=:id")
    suspend fun update(
        id: Long,
        status: String,
        progress: Int,
        filename: String = "",
        error: String? = null,
        mediaUri: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE downloads SET status='QUEUED', progress=0, error=NULL, mediaUri=NULL, scheduledAt=0, updatedAt=:now WHERE id=:id")
    suspend fun retry(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET status='CANCELLED', error='Отменено', updatedAt=:now WHERE id=:id")
    suspend fun cancel(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET status='QUEUED', progress=0, updatedAt=:now WHERE status IN ('RESOLVING', 'DOWNLOADING')")
    suspend fun recoverInterrupted(now: Long = System.currentTimeMillis())

    @Query("DELETE FROM downloads WHERE id=:id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun clearFinished()
}
