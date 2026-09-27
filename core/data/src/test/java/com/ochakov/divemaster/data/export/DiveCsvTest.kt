package com.ochakov.divemaster.data.export

import com.ochakov.divemaster.data.db.DiveEntity
import com.ochakov.divemaster.data.db.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** The CSV is what Subsurface imports — its layout is a contract. */
class DiveCsvTest {

    private val dive = DiveEntity(
        id = 7,
        startEpochMs = 1_000L,
        endEpochMs = 61_000L,
        maxDepthM = 12.34,
        avgDepthM = 5.5,
        minTempC = null,
        gasO2Fraction = 0.32,
        waterType = "EN13319",
        surfacePressureMbar = 1015.2,
        gfLow = 40,
        gfHigh = 85,
        startedUnderwater = true,
    )
    private val samples = listOf(
        SampleEntity(diveId = 7, tOffsetSec = 0, depthM = 0.0, tempC = null, ndlMin = null),
        SampleEntity(diveId = 7, tOffsetSec = 1, depthM = 12.5, tempC = 24.5, ndlMin = 99.5),
    )

    @Test
    fun `header and rows follow the Subsurface layout`() {
        val lines = DiveCsv.render(dive, samples).trimEnd().lines()
        assertEquals("# DiveMaster dive 7", lines[0])
        assertEquals(
            "# startEpochMs=1000 endEpochMs=61000 maxDepthM=12.34 avgDepthM=5.50 gasO2=0.32 " +
                "gf=40/85 water=EN13319 surfaceMbar=1015.2 lateStart=1",
            lines[1],
        )
        assertEquals("time_sec,depth_m,temp_c,ndl_min", lines[2])
        assertEquals("0,0.00,,", lines[3])
        assertEquals("1,12.50,24.5,99.5", lines[4])
        assertEquals(5, lines.size)
    }

    @Test
    fun `file name carries the dive id and its local start time`() {
        assertEquals("DiveMaster_dive7_19700101_0000.csv", DiveCsv.fileName(dive, ZoneId.of("UTC")))
    }

    @Test
    fun `decimal separator stays a dot under a comma locale`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertTrue(DiveCsv.render(dive, samples).contains("1,12.50,24.5,99.5"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
