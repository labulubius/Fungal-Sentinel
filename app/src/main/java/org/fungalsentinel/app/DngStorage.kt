package org.fungalsentinel.app

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

object DngStorage {
    fun save(
        context: Context,
        cameraCharacteristics: CameraCharacteristics,
        fileName: String,
        image: Image,
        captureResult: TotalCaptureResult
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(context, cameraCharacteristics, fileName, image, captureResult)
        } else {
            saveToAppStorage(context, cameraCharacteristics, fileName, image, captureResult)
        }
    }

    private fun saveToMediaStore(
        context: Context,
        cameraCharacteristics: CameraCharacteristics,
        fileName: String,
        image: Image,
        captureResult: TotalCaptureResult
    ) {
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
            resolver.update(uri, values, null, null)
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
    ) {
        val directory = File(context.getExternalFilesDir(null), AppConstants.LEGACY_DNG_DIRECTORY)
        directory.mkdirs()
        FileOutputStream(File(directory, fileName)).use { output ->
            DngCreator(cameraCharacteristics, captureResult).use { creator ->
                creator.writeImage(output, image)
            }
        }
    }
}
