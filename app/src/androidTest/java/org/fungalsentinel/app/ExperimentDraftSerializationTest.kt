package org.fungalsentinel.app

import org.junit.Assert.assertEquals
import org.junit.Test
class ExperimentDraftSerializationTest {
    @Test
    fun draftRoundTripsWithoutAndroidState() {
        val metadata = CaptureMetadata("0", 1_000_000, 100, 0f, 64.0, 1023, 0, 2, 2)
        val profile = SpectralProfile(
            doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 3.0), doubleArrayOf(3.0, 4.0),
            0..1, 0.0, metadata
        )
        val fluorophore = Fluorophore.supported.first()
        val draft = ExperimentDraftSnapshot(
            state = FssaUiState(
                spdData = SpectralAlgorithms.SpdData(doubleArrayOf(400.0, 500.0), doubleArrayOf(1.0, 2.0)),
                spdSource = "test",
                spectralResponse = SpectralResponse(
                    doubleArrayOf(400.0, 500.0), doubleArrayOf(1.0, 1.0),
                    doubleArrayOf(1.0, 1.0), doubleArrayOf(1.0, 1.0)
                ),
                sampleAnalysis = SampleAnalysis(
                    doubleArrayOf(500.0, 501.0), doubleArrayOf(2.0, 3.0), 2.5, 3.0, fluorophore
                ),
                standards = listOf(StandardMeasurement(1.0, doubleArrayOf(2.0, 2.2))),
                positioningProfiles = listOf(profile)
            ),
            dngSaveMode = DngSaveMode.NONE,
            historyName = "draft",
            historyDirty = true,
            historyRevision = 3,
            cameraSettings = CameraControlSettings.manualDefaults(),
            projectId = "project-id",
            projectCreatedAtEpochMs = 123L,
            profiles = listOf(CapturedProfileRecord(AnalysisCapturePurpose.POSITIONING, 1, null, profile)),
            artifacts = emptyList(),
            projectDngFiles = listOf(StoredDng("test.dng", "content://test/1", 42L))
        )
        val restored = ExperimentDraftStore.decode(ExperimentDraftStore.encode(draft))
        assertEquals("draft", restored.historyName)
        assertEquals(DngSaveMode.NONE, restored.dngSaveMode)
        assertEquals(2, restored.state.spdData?.wavelengthsNm?.size)
        assertEquals(CameraControlSettings.DEFAULT_EXPOSURE_TIME_NS, restored.cameraSettings?.exposureTimeNs)
        assertEquals("project-id", restored.projectId)
        assertEquals("test.dng", restored.projectDngFiles.single().displayName)
    }
}
