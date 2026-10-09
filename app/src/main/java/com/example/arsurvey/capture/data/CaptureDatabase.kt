package com.example.arsurvey.capture.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for the capture catalog. Single table pair (objects + photos); no TypeConverters
 * needed because the only complex field ([ARCoreFrameMetadata]) is stored as a JSON string column.
 */
@Database(
    entities = [CapturedObjectEntity::class, CapturedPhotoEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class CaptureDatabase : RoomDatabase() {
    abstract fun captureDao(): CaptureDao

    companion object {
        @Volatile
        private var instance: CaptureDatabase? = null

        fun getInstance(context: Context): CaptureDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CaptureDatabase::class.java,
                    "capture.db",
                ).build().also { instance = it }
            }
    }
}
