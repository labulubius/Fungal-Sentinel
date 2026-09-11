package org.fungalsentinel.app

internal data class CameraCandidate(
    val id: String,
    val backFacing: Boolean,
    val rawOutput: Boolean,
    val manualSensor: Boolean
)

internal object CameraSelection {
    /** Prefer a RAW back camera, then preserve partial support on less capable devices. */
    fun choose(candidates: List<CameraCandidate>): String? = candidates.minWithOrNull(
        compareBy<CameraCandidate> {
            when {
                it.backFacing && it.rawOutput && it.manualSensor -> 0
                it.backFacing && it.rawOutput -> 1
                it.backFacing -> 2
                it.rawOutput && it.manualSensor -> 3
                it.rawOutput -> 4
                else -> 5
            }
        }.thenBy { it.id }
    )?.id
}
