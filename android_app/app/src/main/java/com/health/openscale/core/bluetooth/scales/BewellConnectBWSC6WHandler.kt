/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.scales

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.UUID

/**
 * BewellConnect MyScale Analyzer XL / BW-SC6W.
 *
 * Reverse-engineered from real BLE captures:
 * - service 0xFFE0
 * - notify characteristic 0xFFE1
 * - live weight frame:  08 07 07 B0 ss ww ww
 * - final frame:        08 11 07 B1 ... ww ww zz zz ...
 *
 * Confirmed fields:
 * - weight raw is unsigned big-endian and scaled by 1/200 kg
 * - impedance is unsigned big-endian in ohms
 *
 * Example final capture:
 * 08 11 07 B1 02 01 01 02 A0 78 E6 4A B0 01 43 01 F6
 * -> 0x4AB0 / 200 = 95.6 kg
 * -> 0x0143       = 323 ohm
 *
 * Other bytes are intentionally left uninterpreted until they are verified.
 */
class BewellConnectBWSC6WHandler : ScaleDeviceHandler() {

    companion object {
        private const val DEVICE_NAME = "BW-SC6W"

        internal data class FinalMeasurement(
            val weightKg: Float,
            val impedanceOhm: Int,
        )

        internal fun parseLiveWeightKg(data: ByteArray): Float? {
            if (data.size != 7) return null
            if (u8(data[0]) != 0x08 ||
                u8(data[1]) != 0x07 ||
                u8(data[2]) != 0x07 ||
                u8(data[3]) != 0xB0
            ) return null

            val rawWeight = u16be(data, 5)
            if (rawWeight <= 0) return null

            return rawWeight / 200.0f
        }

        internal fun parseFinalMeasurement(data: ByteArray): FinalMeasurement? {
            if (data.size != 17) return null
            if (u8(data[0]) != 0x08 ||
                u8(data[1]) != 0x11 ||
                u8(data[2]) != 0x07 ||
                u8(data[3]) != 0xB1
            ) return null

            val rawWeight = u16be(data, 11)
            val impedance = u16be(data, 13)
            if (rawWeight <= 0 || impedance <= 0) return null

            return FinalMeasurement(
                weightKg = rawWeight / 200.0f,
                impedanceOhm = impedance,
            )
        }

        private fun u16be(data: ByteArray, offset: Int): Int =
            (u8(data[offset]) shl 8) or u8(data[offset + 1])

        private fun u8(value: Byte): Int = value.toInt() and 0xFF
    }

    private val serviceUuid: UUID = uuid16(0xFFE0)
    private val notifyUuid: UUID = uuid16(0xFFE1)

    private var publishedForSession = false

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (!device.name.equals(DEVICE_NAME, ignoreCase = true)) return null

        return DeviceSupport(
            displayName = "BewellConnect MyScale Analyzer XL (BW-SC6W)",
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
            ),
            // We currently store raw impedance, but do not claim the vendor-specific
            // body-composition formulas until they are verified.
            implemented = setOf(DeviceCapability.LIVE_WEIGHT_STREAM),
            linkMode = LinkMode.CONNECT_GATT,
        )
    }

    override fun onConnected(user: ScaleUser) {
        publishedForSession = false
        logI("BW-SC6W connected; enabling FFE1 notifications")
        setNotifyOn(serviceUuid, notifyUuid)
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != notifyUuid) return

        parseFinalMeasurement(data)?.let { decoded ->
            if (publishedForSession) return

            val measurement = ScaleMeasurement(
                userId = user.id,
                dateTime = Date(),
            ).apply {
                this[MeasurementType.WEIGHT] = Kg(decoded.weightKg)
                this[MeasurementType.IMPEDANCE] = Ohm(decoded.impedanceOhm.toFloat())
            }

            logI(
                "BW-SC6W final measurement: " +
                    "weight=${decoded.weightKg} kg, impedance=${decoded.impedanceOhm} ohm"
            )

            publishedForSession = true
            publish(measurement)
            requestDisconnect()
            return
        }

        parseLiveWeightKg(data)?.let { weightKg ->
            logD("BW-SC6W live weight=${weightKg} kg")
            userInfo(R.string.bluetooth_scale_info_measuring_weight, weightKg)
            return
        }

        logD("BW-SC6W unhandled FFE1 packet ${data.toHexPreview(24)}")
    }
}
