package com.svcmonitor.app

object RuleSets {
    val FILE_IO = intArrayOf(
        56, 437, 63, 64, 66, 65, 62, 48, 49, 35, 38, 36, 40
    )

    val NETWORK = intArrayOf(
        198, 200, 203, 202, 206, 207, 208, 209, 205, 204
    )

    val ANTI_DEBUG = intArrayOf(
        117, 56, 129, 167, 220, 435, 232, 233
    )

    val PROCESS = intArrayOf(
        220, 435, 93, 94, 221, 281, 172, 178
    )

    val MEMORY = intArrayOf(
        222, 215, 226, 234, 270, 271, 272
    )
}
