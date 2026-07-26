package de.nereide.strohhalm.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class SyncStatusConverter {
    /**
     * An unrecognised stored value falls back rather than throwing, mirroring
     * how [SettingsRepository] treats unknown enum names.
     */
    @TypeConverter
    fun toStatus(name: String): SyncStatus =
        SyncStatus.entries.firstOrNull { it.name == name } ?: SyncStatus.NEVER

    @TypeConverter
    fun fromStatus(status: SyncStatus): String = status.name
}

@Database(entities = [Repo::class], version = 1, exportSchema = true)
@TypeConverters(SyncStatusConverter::class)
abstract class StrohhalmDatabase : RoomDatabase() {

    abstract fun repoDao(): RepoDao

    companion object {
        @Volatile
        private var INSTANCE: StrohhalmDatabase? = null

        fun getInstance(context: Context): StrohhalmDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StrohhalmDatabase::class.java,
                    "strohhalm.db"
                ).build().also { INSTANCE = it }
            }
    }
}
