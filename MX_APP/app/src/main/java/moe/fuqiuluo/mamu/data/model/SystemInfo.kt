package moe.fuqiuluo.mamu.data.model

import android.os.Build

data class SystemInfo(
    val androidVersion: String = Build.VERSION.RELEASE,
    val sdkVersion: Int = Build.VERSION.SDK_INT,
    val deviceModel: String = Build.MODEL,
    val deviceManufacturer: String = Build.MANUFACTURER,
    val deviceBrand: String = Build.BRAND,
    val kernelVersion: String = System.getProperty("os.version") ?: "Unknown",
    val cpuAbi: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown",
    val allCpuAbis: List<String> = Build.SUPPORTED_ABIS.toList()
)