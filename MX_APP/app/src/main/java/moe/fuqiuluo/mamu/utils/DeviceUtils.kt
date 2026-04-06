package moe.fuqiuluo.mamu.utils

import android.annotation.SuppressLint
import android.os.Build
import java.lang.reflect.Method

object DeviceUtils {
    val isXiaomiDevice: Boolean
        /**
         * 判断是否为小米/红米设备
         */
        get() {
            val manufacturer = Build.MANUFACTURER
            val brand = Build.BRAND

            return "xiaomi".equals(manufacturer, ignoreCase = true) ||
                    "xiaomi".equals(brand, ignoreCase = true) ||
                    "redmi".equals(brand, ignoreCase = true)
        }

    val isMIUI: Boolean
        /**
         * 判断是否为 MIUI 系统
         */
        @SuppressLint("PrivateApi")
        get() {
            try {
                val sysClass =
                    Class.forName("android.os.SystemProperties")
                val getMethod = sysClass.getMethod("get", String::class.java)
                val miuiVersion =
                    getMethod.invoke(sysClass, "ro.miui.ui.version.name") as String?
                return miuiVersion != null && !miuiVersion.isEmpty()
            } catch (e: Exception) {
                return false
            }
        }

    val mIUIVersion: String?
        /**
         * 获取 MIUI 版本号
         */
        @SuppressLint("PrivateApi")
        get() {
            try {
                val sysClass =
                    Class.forName("android.os.SystemProperties")
                val getMethod: Method = sysClass.getMethod("get", String::class.java)
                return getMethod.invoke(sysClass, "ro.miui.ui.version.name") as String?
            } catch (e: Exception) {
                return null
            }
        }
}