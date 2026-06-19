package org.fungalsentinel.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConstantsTest {

    @Test
    fun appUsesTeamPackageName() {
        assertEquals("org.fungalsentinel.app", AppConstants.APPLICATION_ID)
    }

    @Test
    fun dngFilesUseFungalSentinelPicturesDirectory() {
        assertEquals("Pictures/FungalSentinel", AppConstants.MEDIASTORE_DNG_DIRECTORY)
        assertEquals("FungalSentinel", AppConstants.LEGACY_DNG_DIRECTORY)
    }
}
