/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.health.openscale.core.bluetooth.scales

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BewellConnectBWSC6WHandlerTest {

    @Test
    fun `parses captured live weight frame`() {
        val frame = hex("08 07 07 B0 01 4A B0")

        val weight = BewellConnectBWSC6WHandler.parseLiveWeightKg(frame)

        assertThat(weight).isNotNull()
        assertThat(weight!!).isWithin(0.001f).of(95.6f)
    }

    @Test
    fun `parses captured final weight and impedance frame`() {
        val frame = hex("08 11 07 B1 02 01 01 02 A0 78 E6 4A B0 01 43 01 F6")

        val measurement = BewellConnectBWSC6WHandler.parseFinalMeasurement(frame)

        assertThat(measurement).isNotNull()
        assertThat(measurement!!.weightKg).isWithin(0.001f).of(95.6f)
        assertThat(measurement.impedanceOhm).isEqualTo(323)
    }

    @Test
    fun `rejects unrelated frames`() {
        assertThat(
            BewellConnectBWSC6WHandler.parseLiveWeightKg(hex("08 03 07"))
        ).isNull()
        assertThat(
            BewellConnectBWSC6WHandler.parseFinalMeasurement(hex("08 07 07 B0 01 4A B0"))
        ).isNull()
    }

    private fun hex(value: String): ByteArray =
        value.filterNot { it.isWhitespace() }
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
}
