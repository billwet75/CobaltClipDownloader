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

    @Query("UPDATE downloads SET status=:status, progress=:progress, filename=:filename, error=:error, mediaUri=:mediaUri WHERE id=:id")
    suspend fun update(
        id: Long,
        status: String,
        progress: Int,
        filename: String = "",
        error: String? = null,
        mediaUri: String? = null
    )

    @Query("DELETE FROM downloads")
    suspend fun clear()
}
