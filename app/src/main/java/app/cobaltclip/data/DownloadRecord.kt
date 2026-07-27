package app.cobaltclip.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUrl: String,
    val filename: String = "",
    val status: String = "QUEUED",
    val progress: Int = 0,
    val error: String? = null,
    val mediaUri: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
