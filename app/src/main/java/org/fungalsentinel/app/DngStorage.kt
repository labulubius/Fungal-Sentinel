package org.fungalsentinel.app

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

object DngStorage {
    fun delete(context: Context, storedDng: StoredDng): Boolean {
        val uri = Uri.parse(storedDng.sourceUri)
        return if (uri.scheme == "file") {
            uri.path?.let(::File)?.let { !it.exists() || it.delete() } ?: false
        } else {
            val resolver = context.contentResolver
            if (resolver.delete(uri, null, null) > 0) true else runCatching {
                resolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null).use { cursor ->
                    cursor?.moveToFirst() == true
                }
            }.fold(onSuccess = { stillExists -> !stillExists }, onFailure = { false })
        }
    }

    fun save(
        context: Context,
        cameraCharacteristics: CameraCharacteristics,
        fileName: String,
        image: Image,
        captureResult: TotalCaptureResult
    ): StoredDng = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveToMediaStore(context, cameraCharacteristics, fileName, image, captureResult)
    } else {
        saveToAppStorage(context, cameraCharacteristics, fileName, image, captureResult)
    }

    private fun saveToMediaStore(
        context: Context,
        cameraCharacteristics: CameraCharacteristics,
        fileName: String,
        image: Image,
        captureResult: TotalCaptureResult
    ): StoredDng {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, AppConstants.MEDIASTORE_DNG_DIRECTORY)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert returned null")

        try {
            resolver.openOutputStream(uri).use { output ->
                requireNotNull(output) { "Could not open output stream" }
                DngCreator(cameraCharacteristics, captureResult).use { creator ->
                    creator.writeImage(output, image)
                }
            }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1) { "Could not publish the saved DNG." }
            val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            return StoredDng(fileName, uri.toString(), size)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun saveToAppStorage(
        context: Context,
        cameraCharacteristics: CameraCharacteristics,
        fileName: String,
        image: Image,
        captureResult: TotalCaptureResult
    ): StoredDng {
        val externalRoot = requireNotNull(context.getExternalFilesDir(null)) {
            "External app storage is unavailable."
        }
        val directory = File(externalRoot, AppConstants.LEGACY_DNG_DIRECTORY)
        directory.mkdirs()
        val file = File(directory, fileName)
        FileOutputStream(file).use { output ->
            DngCreator(cameraCharacteristics, captureResult).use { creator ->
                creator.writeImage(output, image)
            }
        }
        return StoredDng(fileName, Uri.fromFile(file).toString(), file.length())
    }
}
