package moe.fuqiuluo.mamu.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable

object ApplicationUtils {
    fun getAppNameByUid(ctx: Context, uid: Int): String? {
        val pkm = ctx.packageManager
        return runCatching {
            val packages = pkm.getPackagesForUid(uid)
            packages?.firstNotNullOfOrNull {
                val appInfo = pkm.getApplicationInfo(it, 0)
                pkm.getApplicationLabel(appInfo).toString()
            }
        }.getOrNull()
    }

    fun getAppNameByPackageName(ctx: Context, packageName: String): String? {
        val pkm = ctx.packageManager
        return runCatching {
            val appInfo = pkm.getApplicationInfo(packageName, 0)
            pkm.getApplicationLabel(appInfo).toString()
        }.getOrNull()
    }

    fun getAppIconByUid(ctx: Context, uid: Int): Drawable? {
        val pkm = ctx.packageManager
        val packages = pkm.getPackagesForUid(uid) ?: return null

        // 直接获取应用图标
        packages.firstNotNullOfOrNull { packageName ->
            runCatching {
                pkm.getApplicationIcon(packageName)
            }.getOrNull()
        }?.let { return it }

        // 通过ApplicationInfo加载图标
        packages.firstNotNullOfOrNull { packageName ->
            runCatching {
                val appInfo = pkm.getApplicationInfo(packageName, 0)
                appInfo.loadIcon(pkm)
            }.getOrNull()
        }?.let { return it }

        return null
    }

    fun getAppIconByPackageName(ctx: Context, packageName: String): Drawable? {
        val pkm = ctx.packageManager
        // 尝试方法1: 直接获取应用图标
        runCatching {
            pkm.getApplicationIcon(packageName)
        }.getOrNull()?.let { return it }

        // 尝试方法2: 通过ApplicationInfo加载
        runCatching {
            val appInfo = pkm.getApplicationInfo(packageName, 0)
            appInfo.loadIcon(pkm)
        }.getOrNull()?.let { return it }

        // 尝试方法3: 返回默认图标
        return runCatching {
            pkm.defaultActivityIcon
        }.getOrNull()
    }

    fun getAndroidIcon(ctx: Context): Drawable {
        val pkm = ctx.packageManager
        return runCatching {
            pkm.getApplicationIcon("android")
        }.getOrNull() ?: ctx.applicationInfo.loadIcon(pkm)
    }

    fun isSystemApp(ctx: Context, uid: Int): Boolean {
        val pkm = ctx.packageManager
        return runCatching {
            val packages = pkm.getPackagesForUid(uid)
            packages?.any {
                val appInfo = pkm.getApplicationInfo(it, 0)
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            } ?: false
        }.getOrNull() ?: false
    }
}