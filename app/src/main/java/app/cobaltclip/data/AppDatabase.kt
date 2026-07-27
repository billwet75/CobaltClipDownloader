package app.cobaltclip.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DownloadRecord::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao

    companion object {
        fun create(context: Context) = Room.databaseBuilder(
            context, AppDatabase::class.java, "downloads.db"
        ).addMigrations(MIGRATION_1_2).build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE downloads ADD COLUMN downloadMode TEXT NOT NULL DEFAULT 'auto'"
                )
                db.execSQL(
                    "ALTER TABLE downloads ADD COLUMN quality TEXT NOT NULL DEFAULT '1080'"
                )
                db.execSQL(
                    "ALTER TABLE downloads ADD COLUMN incognito INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE downloads ADD COLUMN scheduledAt INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE downloads ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
