package org.fungalsentinel.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Versioned internal recovery data. Exported experiments continue to use documented JSON/CSV ZIP files. */
data class ExperimentDraftSnapshot(
    val schemaVersion: Int = 1,
    val state: FssaUiState,
    val dngSaveMode: DngSaveMode,
    val historyName: String,
    val historyDirty: Boolean,
    val historyRevision: Long,
    val cameraSettings: CameraControlSettings?,
    val projectId: String,
    val projectCreatedAtEpochMs: Long,
    val profiles: List<CapturedProfileRecord>,
    val artifacts: List<CaptureArchiveArtifact>,
    val projectDngFiles: List<StoredDng>
)

object ExperimentDraftStore {
    private const val FILE_NAME = "current-experiment-draft.json.gz"

    fun load(context: Context): ExperimentDraftSnapshot? {
        val target = File(context.filesDir, FILE_NAME)
        val backup = File(context.filesDir, "$FILE_NAME.bak")
        fun read(file: File): ExperimentDraftSnapshot? = try {
            val text = GZIPInputStream(file.inputStream().buffered()).bufferedReader().use { it.readText() }
            decode(JSONObject(text)).takeIf { it.schemaVersion == 1 }
        } catch (_: Exception) {
            null
        }
        if (target.isFile) read(target)?.let { return it }
        if (backup.isFile) read(backup)?.let { recovered ->
            target.delete()
            backup.renameTo(target)
            return recovered
        }
        // Keep unreadable files for diagnostics instead of silently destroying user work.
        return null
    }

    fun save(context: Context, draft: ExperimentDraftSnapshot) {
        val target = File(context.filesDir, FILE_NAME)
        val temporary = File(context.filesDir, "$FILE_NAME.tmp")
        val backup = File(context.filesDir, "$FILE_NAME.bak")
        try {
            GZIPOutputStream(temporary.outputStream().buffered()).bufferedWriter().use { it.write(encode(draft).toString()) }
            backup.delete()
            if (target.exists()) check(target.renameTo(backup)) { "Could not back up the previous experiment draft." }
            if (!temporary.renameTo(target)) {
                backup.renameTo(target)
                error("Could not finalize experiment draft.")
            }
            backup.delete()
        } catch (error: Exception) {
            temporary.delete()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            throw error
        }
    }

    fun delete(context: Context) {
        listOf(FILE_NAME, "$FILE_NAME.tmp", "$FILE_NAME.bak").forEach { File(context.filesDir, it).delete() }
    }

    internal fun encode(draft: ExperimentDraftSnapshot) = JSONObject().apply {
        put("schemaVersion", draft.schemaVersion)
        put("dngSaveMode", draft.dngSaveMode.name)
        put("historyName", draft.historyName)
        put("historyDirty", draft.historyDirty)
        put("historyRevision", draft.historyRevision)
        put("cameraSettings", draft.cameraSettings?.let(::cameraJson))
        put("projectId", draft.projectId)
        put("projectCreatedAtEpochMs", draft.projectCreatedAtEpochMs)
        put("state", stateJson(draft.state))
        put("profiles", JSONArray().apply { draft.profiles.forEach { put(recordJson(it)) } })
        put("artifacts", JSONArray().apply { draft.artifacts.forEach { put(artifactJson(it)) } })
        put("projectDngFiles", JSONArray().apply { draft.projectDngFiles.forEach { put(storedDngJson(it)) } })
    }

    internal fun decode(root: JSONObject): ExperimentDraftSnapshot {
        val stateObject = root.getJSONObject("state")
        val records = root.optJSONArray("profiles")?.objects()?.map(::recordFromJson) ?: emptyList()
        val draftConcentration = stateObject.optNullableDouble("standardDraftConcentration")
        fun recordsFor(purpose: AnalysisCapturePurpose) = records.filter { it.purpose == purpose }.map { it.profile }
        val positioning = recordsFor(AnalysisCapturePurpose.POSITIONING)
        val responseProfiles = recordsFor(AnalysisCapturePurpose.RESPONSE)
        val sampleBlanks = recordsFor(AnalysisCapturePurpose.SAMPLE_BLANK)
        val samples = recordsFor(AnalysisCapturePurpose.SAMPLE)
        val standardBlanks = records.filter {
            it.purpose == AnalysisCapturePurpose.STANDARD_BLANK && it.standardConcentration == draftConcentration
        }.map { it.profile }
        val standardSamples = records.filter {
            it.purpose == AnalysisCapturePurpose.STANDARD_SAMPLE && it.standardConcentration == draftConcentration
        }.map { it.profile }
        val spd = stateObject.optJSONObject("spd")?.let {
            SpectralAlgorithms.SpdData(doubleArray(it.getJSONArray("wavelengths")), doubleArray(it.getJSONArray("intensity")))
        }
        val fluorophore = Fluorophore.supported.getOrElse(stateObject.optInt("fluorophoreIndex", 0)) { Fluorophore.supported.first() }
        val wavelengths = runCatching {
            PositioningWavelengths(
                stateObject.getString("redWavelengthInput").toDouble(),
                stateObject.getString("greenWavelengthInput").toDouble(),
                stateObject.getString("blueWavelengthInput").toDouble()
            )
        }.getOrNull()
        val calibration = if (positioning.isNotEmpty() && wavelengths != null) runCatching {
            SpectralAlgorithms.calibrateWavelength(SpectralAlgorithms.averageProfiles(positioning), wavelengths)
        }.getOrNull() else null
        val response = if (responseProfiles.isNotEmpty() && calibration != null && spd != null) runCatching {
            SpectralAlgorithms.calibrateResponse(SpectralAlgorithms.averageProfiles(responseProfiles), calibration, spd)
        }.getOrNull() else null
        val sampleAnalysis = if (sampleBlanks.isNotEmpty() && samples.isNotEmpty() && calibration != null && response != null) runCatching {
            SpectralAlgorithms.analyzeSamples(sampleBlanks, samples, calibration, response, fluorophore)
        }.getOrNull() else null
        val standards = stateObject.optJSONArray("standards")?.objects()?.map { item ->
            StandardMeasurement(item.getDouble("concentration"), doubleArray(item.getJSONArray("areas")))
        } ?: emptyList()
        val concentration = if (stateObject.optBoolean("hadConcentrationResult") && sampleAnalysis != null) runCatching {
            SpectralAlgorithms.calculateConcentration(standards, sampleAnalysis.area)
        }.getOrNull() else null
        val step = runCatching { AnalysisStep.valueOf(stateObject.optString("step")) }.getOrDefault(AnalysisStep.PROJECT)
        val purpose = runCatching { AnalysisCapturePurpose.valueOf(stateObject.optString("selectedCapturePurpose")) }
            .getOrDefault(AnalysisCapturePurpose.POSITIONING)
        val state = FssaUiState(
            step = step,
            status = "Recovered experiment draft.",
            redWavelengthInput = stateObject.optString("redWavelengthInput", PositioningWavelengths.DEFAULT.redNm.toString()),
            greenWavelengthInput = stateObject.optString("greenWavelengthInput", PositioningWavelengths.DEFAULT.greenNm.toString()),
            blueWavelengthInput = stateObject.optString("blueWavelengthInput", PositioningWavelengths.DEFAULT.blueNm.toString()),
            wavelengthCalibration = calibration,
            spectralResponse = response,
            sampleAnalysis = sampleAnalysis,
            standards = standards,
            concentrationResult = concentration,
            selectedFluorophore = fluorophore,
            lockedMetadata = positioning.firstOrNull()?.metadata,
            lockedXRoi = positioning.firstOrNull()?.xRoi,
            positioningProfiles = positioning,
            positioningAverageProfile = positioning.takeIf { it.isNotEmpty() }?.let(SpectralAlgorithms::averageProfiles),
            responseProfiles = responseProfiles,
            sampleBlankProfiles = sampleBlanks,
            sampleProfiles = samples,
            standardBlankProfiles = standardBlanks,
            standardSampleProfiles = standardSamples,
            lastProfile = standardSamples.lastOrNull() ?: standardBlanks.lastOrNull() ?: samples.lastOrNull()
                ?: sampleBlanks.lastOrNull() ?: responseProfiles.lastOrNull() ?: positioning.lastOrNull(),
            spdData = spd,
            spdSource = stateObject.optString("spdSource", FssaUiState.SPD_NOT_LOADED_SOURCE),
            standardConcentrationInput = stateObject.optString("standardConcentrationInput"),
            standardDraftConcentration = stateObject.optNullableDouble("standardDraftConcentration"),
            selectedCapturePurpose = purpose,
            logs = stateObject.optJSONArray("logs")?.strings() ?: listOf("Experiment draft recovered.")
        )
        return ExperimentDraftSnapshot(
            schemaVersion = root.getInt("schemaVersion"), state = state,
            dngSaveMode = runCatching { DngSaveMode.valueOf(root.getString("dngSaveMode")) }.getOrDefault(DngSaveMode.ALL),
            historyName = root.optString("historyName"), historyDirty = root.optBoolean("historyDirty", true),
            historyRevision = root.optLong("historyRevision"), cameraSettings = root.optJSONObject("cameraSettings")?.let(::cameraFromJson),
            projectId = root.optString("projectId"), projectCreatedAtEpochMs = root.optLong("projectCreatedAtEpochMs"),
            profiles = records,
            artifacts = root.optJSONArray("artifacts")?.objects()?.map(::artifactFromJson) ?: emptyList(),
            projectDngFiles = root.optJSONArray("projectDngFiles")?.objects()?.map(::storedDngFromJson)
                ?: (root.optJSONArray("artifacts")?.objects()?.map(::artifactFromJson)?.map { it.storedDng } ?: emptyList())
        )
    }

    private fun stateJson(state: FssaUiState) = JSONObject().apply {
        put("step", state.step.name); put("selectedCapturePurpose", state.selectedCapturePurpose.name)
        put("redWavelengthInput", state.redWavelengthInput); put("greenWavelengthInput", state.greenWavelengthInput)
        put("blueWavelengthInput", state.blueWavelengthInput); put("fluorophoreIndex", Fluorophore.supported.indexOf(state.selectedFluorophore))
        put("spdSource", state.spdSource); put("standardConcentrationInput", state.standardConcentrationInput)
        put("standardDraftConcentration", state.standardDraftConcentration); put("hadConcentrationResult", state.concentrationResult != null)
        put("logs", JSONArray(state.logs))
        state.spdData?.let { put("spd", JSONObject().put("wavelengths", arrayJson(it.wavelengthsNm)).put("intensity", arrayJson(it.intensity))) }
        put("standards", JSONArray().apply { state.standards.forEach { put(JSONObject().put("concentration", it.concentration).put("areas", arrayJson(it.replicateAreas))) } })
    }

    private fun profileJson(profile: SpectralProfile) = JSONObject().apply {
        put("red", arrayJson(profile.red)); put("green", arrayJson(profile.green)); put("blue", arrayJson(profile.blue))
        put("xFirst", profile.xRoi.first); put("xLast", profile.xRoi.last); put("saturatedFraction", profile.saturatedFraction)
        put("metadata", metadataJson(profile.metadata))
    }
    private fun profileFromJson(item: JSONObject) = SpectralProfile(
        doubleArray(item.getJSONArray("red")), doubleArray(item.getJSONArray("green")), doubleArray(item.getJSONArray("blue")),
        item.getInt("xFirst")..item.getInt("xLast"), item.getDouble("saturatedFraction"), metadataFromJson(item.getJSONObject("metadata"))
    )
    private fun metadataJson(value: CaptureMetadata) = JSONObject().apply {
        put("cameraId", value.cameraId); put("exposureTimeNs", value.exposureTimeNs); put("iso", value.iso)
        put("focus", value.focusDistanceDiopters.toDouble()); put("blackLevel", value.blackLevel); put("whiteLevel", value.whiteLevel)
        put("cfa", value.cfaArrangement); put("width", value.width); put("height", value.height)
    }
    private fun metadataFromJson(item: JSONObject) = CaptureMetadata(
        item.getString("cameraId"), item.getLong("exposureTimeNs"), item.getInt("iso"), item.getDouble("focus").toFloat(),
        item.getDouble("blackLevel"), item.getInt("whiteLevel"), item.getInt("cfa"), item.getInt("width"), item.getInt("height")
    )

    private fun recordJson(value: CapturedProfileRecord) = JSONObject().apply {
        put("purpose", value.purpose.name); put("replicate", value.replicate); put("standardConcentration", value.standardConcentration)
        put("profile", profileJson(value.profile))
    }
    private fun recordFromJson(item: JSONObject) = CapturedProfileRecord(
        AnalysisCapturePurpose.valueOf(item.getString("purpose")), item.getInt("replicate"),
        item.optNullableDouble("standardConcentration"), profileFromJson(item.getJSONObject("profile"))
    )
    private fun storedDngJson(value: StoredDng) = JSONObject().apply {
        put("displayName", value.displayName); put("sourceUri", value.sourceUri); put("sizeBytes", value.sizeBytes)
    }
    private fun storedDngFromJson(item: JSONObject) = StoredDng(
        item.getString("displayName"), item.getString("sourceUri"), item.getLong("sizeBytes")
    )
    private fun artifactJson(value: CaptureArchiveArtifact) = JSONObject().apply {
        put("purpose", value.purpose?.name); put("replicate", value.replicate); put("capturedAt", value.capturedAtEpochMs)
        put("standardConcentration", value.standardConcentration); put("displayName", value.storedDng.displayName)
        put("sourceUri", value.storedDng.sourceUri); put("sizeBytes", value.storedDng.sizeBytes)
    }
    private fun artifactFromJson(item: JSONObject) = CaptureArchiveArtifact(
        item.optString("purpose").takeIf { it.isNotEmpty() }?.let(AnalysisCapturePurpose::valueOf), item.getInt("replicate"),
        item.getLong("capturedAt"), item.optNullableDouble("standardConcentration"),
        storedDngFromJson(item)
    )

    private fun cameraJson(value: CameraControlSettings) = JSONObject().apply {
        put("manual", value.manualControlsEnabled); put("exposure", value.exposureTimeNs); put("iso", value.iso); put("focus", value.focusDistanceDiopters.toDouble())
        put("awb", value.autoWhiteBalanceEnabled); put("denoise", value.noiseReductionEnabled); put("edge", value.edgeEnhancementEnabled)
        put("hotPixel", value.hotPixelCorrectionEnabled); put("meterLock", value.meterThenLockEnabled)
    }
    private fun cameraFromJson(item: JSONObject) = CameraControlSettings(
        item.getBoolean("manual"), item.getLong("exposure"), item.getInt("iso"), item.getDouble("focus").toFloat(),
        item.getBoolean("awb"), item.getBoolean("denoise"), item.getBoolean("edge"), item.getBoolean("hotPixel"), item.getBoolean("meterLock")
    )

    private fun arrayJson(values: DoubleArray) = JSONArray().apply { values.forEach { put(it) } }
    private fun doubleArray(array: JSONArray) = DoubleArray(array.length()) { array.getDouble(it) }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONObject.optNullableDouble(key: String): Double? = if (isNull(key) || !has(key)) null else getDouble(key)
}
