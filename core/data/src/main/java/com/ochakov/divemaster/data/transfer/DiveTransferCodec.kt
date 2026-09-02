package com.ochakov.divemaster.data.transfer

import com.ochakov.divemaster.data.db.SampleEntity

/**
 * Wire format for dive samples crossing the Wearable Data Layer as an
 * Asset: one `tOffsetSec,depthM,tempC,ndlMin` line per sample, empty
 * fields for nulls. Double.toString/toDouble keep it locale-independent.
 */
object DiveTransferCodec {

    fun encodeSamples(samples: List<SampleEntity>): ByteArray {
        val builder = StringBuilder(samples.size * 24)
        for (sample in samples) {
            builder.append(sample.tOffsetSec).append(',')
                .append(sample.depthM).append(',')
                .append(sample.tempC ?: "").append(',')
                .append(sample.ndlMin ?: "").append('\n')
        }
        return builder.toString().toByteArray()
    }

    fun decodeSamples(bytes: ByteArray, diveId: Long): List<SampleEntity> =
        bytes.decodeToString()
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(',')
                val tOffset = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val depth = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
                SampleEntity(
                    diveId = diveId,
                    tOffsetSec = tOffset,
                    depthM = depth,
                    tempC = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
                    ndlMin = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
                )
            }
            .toList()
}
