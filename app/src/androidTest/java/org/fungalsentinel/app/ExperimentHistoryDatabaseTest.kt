package org.fungalsentinel.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExperimentHistoryDatabaseTest {
    private lateinit var database: ExperimentHistoryDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ExperimentHistoryDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun insertListAndDeleteHistorySummary() {
        val older = item("old", 10L)
        val newer = item("new", 20L)
        database.historyDao().insert(older)
        database.historyDao().insert(newer)

        assertEquals(listOf("new", "old"), database.historyDao().getAll().map { it.id })
        database.historyDao().deleteById("new")
        assertEquals(listOf("old"), database.historyDao().getAll().map { it.id })
        assertTrue(database.historyDao().getAll().single().predictedConcentration == null)
    }

    private fun item(id: String, time: Long) = ExperimentHistoryEntity(
        id = id,
        name = id,
        createdAtEpochMs = time,
        fluorophoreName = "EGFP",
        sampleArea = 1.0,
        sampleSd = 0.1,
        replicateCount = 2,
        predictedConcentration = null,
        rSquared = null,
        calibrationQuality = "PASS",
        standardCount = 0,
        dngSaveMode = DngSaveMode.NONE.name,
        rawFileCount = 0,
        archiveRelativePath = "experiments/$id.zip",
        archiveSizeBytes = 100
    )
}
