package com.clotube.desktop.ui.components

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

object CinemaColorFilter {
    /**
     * Calibrated HDR-to-SDR tone-mapping ColorMatrix:
     * - Black level translation (-0.065f) crushes elevated gray blacks to #000000.
     * - Contrast expansion (1.22x) restores dynamic range.
     * - Saturation boost (1.35x) corrects BT.2020 -> Rec.709 gamut desaturation.
     * Runs directly on the Direct3D 11 GPU shader pipeline with zero CPU overhead.
     */
    val ToneMappedColorFilter: ColorFilter = ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                1.556f, -0.305f, -0.031f, 0f, -0.065f,
                -0.091f,  1.342f, -0.031f, 0f, -0.065f,
                -0.091f, -0.305f,  1.616f, 0f, -0.065f,
                0f,      0f,      0f,     1f,  0f
            )
        )
    )
}

