package org.fungalsentinel.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes an ordinary ZIP archive; no custom container or extension is used. */
object ExperimentArchiveWriter {
    const val SCHEMA_VERSION = 1

    data class Request(
        val id: String,
        val name: String,
        val createdAtEpochMs: Long,
        val appVersion: String,
        val dngSaveMode: DngSaveMode,
        val enforceCalibrationGate: Boolean,
        val state: FssaUiState,
        val profiles: List<CapturedProfileRecord>,
        val rawArtifacts: List<CaptureArchiveArtifact>
    )

    data class Result(val file: File, val includedRawCount: Int, val missingRawFiles: List<String>)

    fun write(context: Context, request: Request): Result {
        require(request.state.sampleAnalysis != null) { "A sample analysis is required." }
        val directory = File(context.filesDir, "experiments").apply { mkdirs() }
        val target = File(directory, "${request.id}.zip")
        val temporary = File(directory, "${request.id}.zip.tmp")
        val backup = File(directory, "${request.id}.zip.bak")
        temporary.delete()
        val missingRaw = mutableListOf<String>()
        var includedRaw = 0
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { zip ->
                putText(zip, "result.json", resultJson(request).toString(2))
                putText(zip, "profiles.csv", profilesCsv(request))
                putText(zip, "standards.csv", standardsCsv(request.state))
                putText(zip, "spd.csv", spdCsv(request.state.spdData))

                request.rawArtifacts.forEachIndexed { index, artifact ->
                    val safeName = artifact.storedDng.displayName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                    val entryName = "raw/${index + 1}_${artifact.purpose?.name?.lowercase() ?: "capture"}_$safeName"
                    val staged = File.createTempFile("fssa_raw_", ".dng", context.cacheDir)
                    val stagedSuccessfully = try {
                        context.contentResolver.openInputStream(Uri.parse(artifact.storedDng.sourceUri)).use { input ->
                            requireNotNull(input) { "RAW source is unavailable" }
                            staged.outputStream().use { output -> input.copyTo(output) }
                        }
                        true
                    } catch (_: Exception) {
                        missingRaw += artifact.storedDng.displayName
                        false
                    }
                    if (stagedSuccessfully) {
                        try {
                            zip.putNextEntry(ZipEntry(entryName))
                            staged.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                            includedRaw++
                        } finally {
                            staged.delete()
                        }
                    } else {
                        staged.delete()
                    }
                }
                putText(zip, "manifest.json", manifestJson(request, includedRaw, missingRaw).toString(2))
            }
            backup.delete()
            if (target.exists()) check(target.renameTo(backup)) { "Could not back up the existing experiment ZIP." }
            if (!temporary.renameTo(target)) {
                backup.renameTo(target)
                error("Could not finalize the experiment ZIP.")
            }
            backup.delete()
            return Result(target, includedRaw, missingRaw)
        } catch (error: Exception) {
            temporary.delete()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            throw error
        }
    }

    private fun manifestJson(request: Request, includedRaw: Int, missingRaw: List<String>) = JSONObject().apply {
        put("format", "fungal-sentinel-session")
        put("schemaVersion", SCHEMA_VERSION)
        put("appVersion", request.appVersion)
        put("algorithmVersion", "FSSA-1.3.4")
        put("sessionId", request.id)
        put("createdAt", isoTimestamp(request.createdAtEpochMs))
        put("name", request.name)
        put("dngSaveMode", request.dngSaveMode.name)
        put("calibrationProtectionEnabled", request.enforceCalibrationGate)
        put("containsDng", includedRaw > 0)
        put("includedRawCount", includedRaw)
        put("missingRawFiles", JSONArray(missingRaw))
        put("spdSource", request.state.spdSource)
        put("profileCount", request.profiles.size)
        put("rawArtifacts", JSONArray().apply {
            request.rawArtifacts.forEach { artifact ->
                put(JSONObject().apply {
                    put("purpose", artifact.purpose?.name ?: "CAPTURE")
                    put("replicate", artifact.replicate)
                    put("capturedAt", isoTimestamp(artifact.capturedAtEpochMs))
                    put("standardConcentration", artifact.standardConcentration?.let(::finite) ?: JSONObject.NULL)
                    put("displayName", artifact.storedDng.displayName)
                    put("sourceUri", artifact.storedDng.sourceUri)
                    put("sizeBytes", artifact.storedDng.sizeBytes)
                    put("included", artifact.storedDng.displayName !in missingRaw)
                })
            }
        })
        request.state.lockedXRoi?.let { put("xRoi", JSONArray(listOf(it.first, it.last))) }
        request.state.lockedMetadata?.let { metadata ->
            put("captureMetadata", JSONObject().apply {
                put("cameraId", metadata.cameraId)
                put("exposureTimeNs", metadata.exposureTimeNs)
                put("iso", metadata.iso)
                put("focusDistanceDiopters", finite(metadata.focusDistanceDiopters.toDouble()))
                put("blackLevel", finite(metadata.blackLevel))
                put("whiteLevel", metadata.whiteLevel)
                put("cfaArrangement", metadata.cfaArrangement)
                put("width", metadata.width)
                put("height", metadata.height)
            })
        }
    }

    private fun resultJson(request: Request): JSONObject {
        val state = request.state
        val sample = requireNotNull(state.sampleAnalysis)
        return JSONObject().apply {
            put("fluorophore", JSONObject().apply {
                put("name", sample.fluorophore.displayName)
                put("peakWavelengthNm", sample.fluorophore.peakWavelengthNm)
                put("channel", sample.fluorophore.channel.name)
                put("integrationWidthNm", sample.fluorophore.integrationWidthNm)
            })
            put("sample", JSONObject().apply {
                put("integratedArea", finite(sample.area))
                put("sampleStandardDeviation", finite(sample.sd))
                put("replicateCount", sample.replicateAreas.size)
                put("replicateAreas", JSONArray(sample.replicateAreas.map(::finite)))
                put("peak", finite(sample.peak))
            })
            state.wavelengthCalibration?.let { calibration ->
                put("wavelengthCalibration", JSONObject().apply {
                    put("slopePixelsPerNm", finite(calibration.slopePixelsPerNm))
                    put("interceptPixels", finite(calibration.interceptPixels))
                    put("validationErrorNm", finite(calibration.validationErrorNm))
                    put("quality", calibration.quality.name)
                })
            }
            state.concentrationResult?.let { concentration ->
                put("concentration", JSONObject().apply {
                    put("slope", finite(concentration.slope))
                    put("intercept", finite(concentration.intercept))
                    put("rSquared", finite(concentration.rSquared))
                    put("predicted", finite(concentration.sampleConcentration))
                    put("outsideCalibrationRange", concentration.outsideCalibrationRange)
                })
            }
            put("warnings", JSONArray(state.concentrationResult?.let {
                AnalysisQualityPolicy.regressionWarnings(state.standards, it)
            } ?: emptyList<String>()))
        }
    }

    private fun profilesCsv(request: Request): String = buildString {
        append("purpose,standard_concentration,replicate,index,wavelength_nm,red,green,blue,saturated_fraction\r\n")
        request.profiles.forEach { record ->
            val profile = record.profile
            for (index in 0 until profile.size) {
                val wavelength = request.state.wavelengthCalibration?.pixelToWavelength(index.toDouble())
                append(record.purpose.name).append(',')
                append(record.standardConcentration?.let(::number) ?: "").append(',')
                append(record.replicate).append(',').append(index).append(',')
                append(wavelength?.let(::number) ?: "").append(',')
                append(number(profile.red[index])).append(',')
                append(number(profile.green[index])).append(',')
                append(number(profile.blue[index])).append(',')
                append(number(profile.saturatedFraction)).append("\r\n")
            }
        }
    }

    private fun standardsCsv(state: FssaUiState): String = buildString {
        append("standard,concentration,replicate,area,group_mean,group_sd\r\n")
        state.standards.forEachIndexed { group, standard ->
            standard.replicateAreas.forEachIndexed { replicate, area ->
                append(group + 1).append(',').append(number(standard.concentration)).append(',')
                    .append(replicate + 1).append(',').append(number(area)).append(',')
                    .append(number(standard.area)).append(',').append(number(standard.sd)).append("\r\n")
            }
        }
    }

    private fun spdCsv(spd: SpectralAlgorithms.SpdData?): String = buildString {
        append("wavelength_nm,intensity\r\n")
        if (spd != null) for (index in spd.wavelengthsNm.indices) {
            append(number(spd.wavelengthsNm[index])).append(',')
                .append(number(spd.intensity[index])).append("\r\n")
        }
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    private fun finite(value: Double): Any = if (value.isFinite()) value else JSONObject.NULL
    private fun number(value: Double): String = if (value.isFinite()) String.format(Locale.US, "%.12g", value) else ""
    private fun isoTimestamp(epochMs: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(epochMs))
}
