package moe.fuqiuluo.mamu.utils

import android.util.Log

object RootPermissionUtils {
    private const val TAG = "RootPermissionManager"

    /**
     * 使用 root Permissions授予指定应用的Permissions（同步方法，建议在子线程调用）
     * @param packageName 包名
     * @param permission Permissions名称（如 android.permission.QUERY_ALL_PACKAGES）
     * @return 是否授权成功
     */
    fun grantPermission(packageName: String, permission: String): Boolean {
        return when (val result = RootShellExecutor.exec(suCmd = RootConfigManager.getCustomRootCommand(), "pm grant $packageName $permission")) {
            is ShellResult.Success -> {
                Log.d(TAG, "Successfully granted $permission to $packageName")
                true
            }
            is ShellResult.Error -> {
                Log.e(TAG, "Failed to grant $permission to $packageName: ${result.message}")
                false
            }
            is ShellResult.Timeout -> {
                Log.e(TAG, "Timeout granting $permission to $packageName")
                false
            }
        }
    }

    /**
     * 批量授予Permissions（同步方法，建议在子线程调用）
     * @param packageName 包名
     * @param permissions Permissions列表
     * @return 成功授予的Permissions数量
     */
    fun grantAllPermissions(packageName: String, permissions: List<String>): Int {
        var successCount = 0
        permissions.forEach { permission ->
            if (grantPermission(packageName, permission)) {
                successCount++
            }
        }
        Log.d(TAG, "Granted $successCount/${permissions.size} permissions to $packageName")
        return successCount
    }

    /**
     * 异步授予Permissions
     * @param packageName 包名
     * @param permission Permissions名称
     * @param callback 结果回调
     */
    fun grantPermissionAsync(packageName: String, permission: String, callback: ((Boolean) -> Unit)? = null) {
        Thread {
            val result = grantPermission(packageName, permission)
            callback?.invoke(result)
        }.start()
    }

    /**
     * 异步批量授予Permissions
     * @param packageName 包名
     * @param permissions Permissions列表
     * @param callback 结果回调（成功授予的Permissions数量）
     */
    fun grantAllPermissionsAsync(packageName: String, permissions: List<String>, callback: ((Int) -> Unit)? = null) {
        Thread {
            val successCount = grantAllPermissions(packageName, permissions)
            callback?.invoke(successCount)
        }.start()
    }

    /**
     * 撤销指定应用的Permissions（同步方法，建议在子线程调用）
     * @param packageName 包名
     * @param permission Permissions名称
     * @return 是否撤销成功
     */
    fun revokePermission(packageName: String, permission: String): Boolean {
        return when (val result = RootShellExecutor.exec(suCmd = RootConfigManager.getCustomRootCommand(), "pm revoke $packageName $permission")) {
            is ShellResult.Success -> {
                Log.d(TAG, "Successfully revoked $permission from $packageName")
                true
            }
            is ShellResult.Error -> {
                Log.e(TAG, "Failed to revoke $permission from $packageName: ${result.message}")
                false
            }
            is ShellResult.Timeout -> {
                Log.e(TAG, "Timeout revoking $permission from $packageName")
                false
            }
        }
    }

    /**
     * 检查应用是否有 root Permissions（同步方法，建议在子线程调用）
     * @return 是否有 root Permissions
     */
    fun checkRootAccess(): Boolean {
        return try {
            val result = RootShellExecutor.exec(
                suCmd = RootConfigManager.getCustomRootCommand(),
                "id -u"
            )

            when (result) {
                is ShellResult.Success -> {
                    val uid = result.output.trim()
                    val hasRoot = uid == "0"
                    Log.d(TAG, "Root access check - UID: $uid, hasRoot: $hasRoot")
                    hasRoot
                }
                is ShellResult.Error -> {
                    Log.e(TAG, "Root check failed: ${result.message}")
                    false
                }
                is ShellResult.Timeout -> {
                    Log.e(TAG, "Root check timeout")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking root access", e)
            false
        }
    }

    /**
     * 使用 appops 设置应用操作Permissions（同步方法，建议在子线程调用）
     * @param packageName 包名
     * @param op 操作名称（如 SYSTEM_ALERT_WINDOW）
     * @param mode 模式（allow/deny/ignore/default）
     * @return 是否设置成功
     */
    fun setAppOp(packageName: String, op: String, mode: String = "allow"): Boolean {
        return when (val result = RootShellExecutor.exec(suCmd = RootConfigManager.getCustomRootCommand(), "appops set $packageName $op $mode")) {
            is ShellResult.Success -> {
                Log.d(TAG, "Successfully set appop $op to $mode for $packageName")
                true
            }
            is ShellResult.Error -> {
                Log.e(TAG, "Failed to set appop $op for $packageName: ${result.message}")
                false
            }
            is ShellResult.Timeout -> {
                Log.e(TAG, "Timeout setting appop $op for $packageName")
                false
            }
        }
    }

    /**
     * 异步设置 appops
     * @param packageName 包名
     * @param op 操作名称
     * @param mode 模式
     * @param callback 结果回调
     */
    fun setAppOpAsync(packageName: String, op: String, mode: String = "allow", callback: ((Boolean) -> Unit)? = null) {
        Thread {
            val result = setAppOp(packageName, op, mode)
            callback?.invoke(result)
        }.start()
    }
}
