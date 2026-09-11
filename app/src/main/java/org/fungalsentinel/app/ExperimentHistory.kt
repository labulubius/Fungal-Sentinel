package org.fungalsentinel.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.Serializable

enum class DngSaveMode(val displayName: String, val description: String) {
    ALL("All RAW captures", "Save every capture as DNG for full traceability."),
    KEY_CAPTURES("Samples only", "Save Sample and standard Sample DNG files only."),
    NONE("No DNG", "Analyze in memory. History keeps results and profiles, but not sensor RAW files.");

    fun shouldSave(purpose: AnalysisCapturePurpose?): Boolean = when (this) {
        ALL -> true
        KEY_CAPTURES -> purpose == AnalysisCapturePurpose.SAMPLE ||
            purpose == AnalysisCapturePurpose.STANDARD_SAMPLE
        NONE -> false
    }
}

data class StoredDng(
    val displayName: String,
    /** content:// URI on Android 10+, file:// URI on older versions. */
    val sourceUri: String,
    val sizeBytes: Long
) : Serializable

data class CaptureArchiveArtifact(
    val purpose: AnalysisCapturePurpose?,
    val replicate: Int,
    val capturedAtEpochMs: Long,
    val standardConcentration: Double?,
    val storedDng: StoredDng
) : Serializable

data class CapturedProfileRecord(
    val purpose: AnalysisCapturePurpose,
    val replicate: Int,
    val standardConcentration: Double?,
    val profile: SpectralProfile
) : Serializable

@Entity(tableName = "experiment_history")
data class ExperimentHistoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val fluorophoreName: String,
    val sampleArea: Double,
    val sampleSd: Double,
    val replicateCount: Int,
    val predictedConcentration: Double?,
    val rSquared: Double?,
    val calibrationQuality: String,
    val standardCount: Int,
    val dngSaveMode: String,
    val rawFileCount: Int,
    val archiveRelativePath: String,
    val archiveSizeBytes: Long
)

@Dao
interface ExperimentHistoryDao {
    @Query("SELECT * FROM experiment_history ORDER BY createdAtEpochMs DESC")
    fun getAll(): List<ExperimentHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: ExperimentHistoryEntity)

    @Query("DELETE FROM experiment_history WHERE id = :id")
    fun deleteById(id: String)
}

@Database(entities = [ExperimentHistoryEntity::class], version = 1, exportSchema = false)
abstract class ExperimentHistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): ExperimentHistoryDao

    companion object {
        @Volatile private var instance: ExperimentHistoryDatabase? = null

        fun get(context: Context): ExperimentHistoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ExperimentHistoryDatabase::class.java,
                "fungal-sentinel-history.db"
            ).build().also { instance = it }
        }
    }
}
