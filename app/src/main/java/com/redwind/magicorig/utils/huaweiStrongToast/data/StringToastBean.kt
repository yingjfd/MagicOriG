package com.redwind.magicorig.utils.huaweiStrongToast.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HuaweiStrongToastBean(
    @SerialName("left_text") val leftText: String = "",
    @SerialName("left_text_color") val leftTextColor: Int = 0xFFFFFFFF.toInt(),
    @SerialName("right_text") val rightText: String = "",
    @SerialName("right_text_color") val rightTextColor: Int = 0xFFFFFFFF.toInt(),
    @SerialName("category") val category: Int = 0
)

object HuaweiStrongToastCategory {
    const val TEXT_ONLY = 0
    const val BATTERY_VIDEO_TEXT = 1
    const val VIDEO_TEXT = 2
}
