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
        batteryStartPct = 87,
        batteryEndPct = 71,
        safetyStopResult = "DONE",
        maxAscentRateMPerMin = 15.21,
        cnsEndFraction = 0.0312,
        appVersion = "0.9.2",
        exitLat = 29.551234,
        exitLon = 34.9501,
        exitFixEpochMs = 70_000L,
    )
    private val samples = listOf(
        SampleEntity(diveId = 7, tOffsetSec = 0, depthM = 0.0, tempC = null, ndlMin = null),
        SampleEntity(diveId = 7, tOffsetSec = 1, depthM = 12.5, tempC = 24.5, ndlMin = 99.5),
    )

    @Test
    fun `headers and rows follow the Subsurface layout`() {
        val lines = DiveCsv.render(dive, samples).trimEnd().lines()
        assertEquals("# DiveMaster dive 7", lines[0])
        assertEquals(
            "# startEpochMs=1000 endEpochMs=61000 maxDepthM=12.34 avgDepthM=5.50 gasO2=0.32 " +
                "gf=40/85 water=EN13319 surfaceMbar=1015.2 lateStart=1",
            lines[1],
        )
        assertEquals("# batteryStart=87 batteryEnd=71 safetyStop=DONE maxAscentMPerMin=15.2 cnsEnd=0.031 app=0.9.2", lines[2])
        assertEquals(
            "# entryLat=- entryLon=- entryFixEpochMs=- exitLat=29.551234 exitLon=34.950100 exitFixEpochMs=70000",
            lines[3],
        )
        assertEquals("time_sec,depth_m,temp_c,ndl_min", lines[4])
        assertEquals("0,0.00,,", lines[5])
        assertEquals("1,12.50,24.5,99.5", lines[6])
        assertEquals(7, lines.size)
    }

    @Test
    fun `missing metadata is written as a dash`() {
        val bare = dive.copy(
            batteryStartPct = null, batteryEndPct = null, safetyStopResult = null,
            maxAscentRateMPerMin = null, cnsEndFraction = null, appVersion = null,
            exitLat = null, exitLon = null, exitFixEpochMs = null,
        )
        val lines = DiveCsv.render(bare, samples).lines()
        assertEquals("# batteryStart=- batteryEnd=- safetyStop=- maxAscentMPerMin=- cnsEnd=- app=-", lines[2])
        assertEquals("# entryLat=- entryLon=- entryFixEpochMs=- exitLat=- exitLon=- exitFixEpochMs=-", lines[3])
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
            val csv = DiveCsv.render(dive, samples)
            assertTrue(csv.contains("1,12.50,24.5,99.5"))
            assertTrue(csv.contains("exitLat=29.551234"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
