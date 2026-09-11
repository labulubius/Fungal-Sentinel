package org.fungalsentinel.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("org.fungalsentinel.app", appContext.packageName)
    }

    @Test
    fun builtInTrueSpdResourceParses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val spd = context.resources.openRawResource(R.raw.true_spd).bufferedReader().use {
            SpectralAlgorithms.parseSpdCsv(it.readText())
        }

        assertEquals(401, spd.wavelengthsNm.size)
        assertEquals(350.0, spd.wavelengthsNm.first(), 0.0)
        assertEquals(750.0, spd.wavelengthsNm.last(), 0.0)
        assertTrue(spd.intensity.all { it >= 0.0 })
        assertTrue(spd.wavelengthsNm.indices.count {
            spd.wavelengthsNm[it] in 420.0..680.0 && spd.intensity[it] > 0.0
        } >= 250)
    }
}
