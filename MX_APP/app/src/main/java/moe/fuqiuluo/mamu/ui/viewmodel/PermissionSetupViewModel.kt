package moe.fuqiuluo.mamu.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.utils.AntiKillProtectionManager
import moe.fuqiuluo.mamu.utils.DriverInstaller
import moe.fuqiuluo.mamu.utils.PermissionConfig
import moe.fuqiuluo.mamu.utils.PermissionManager
import moe.fuqiuluo.mamu.utils.RootPermissionUtils

/**
 * Permissions设置状态
 */
sealed class PermissionSetupState {
    /** 初始化中 */
    data object Initializing : PermissionSetupState()

    /** 检查RootPermissions中 */
    data object CheckingRoot : PermissionSetupState()

    /** 没有RootPermissions */
    data object NoRoot : PermissionSetupState()

    /** 等待用户确认是否使用Root授权 */
    data object WaitingUserConfirm : PermissionSetupState()

    /** 正在授予Permissions */
    data class GrantingPermissions(
        val current: Int,
        val total: Int,
        val currentPermission: String
    ) : PermissionSetupState()

    /** 正在应用究极免杀保护 */
    data class ApplyingAntiKillProtection(
        val current: Int,
        val total: Int,
        val currentMeasure: String
    ) : PermissionSetupState()

    /** 正在检查驱动 */
    data object CheckingDriver : PermissionSetupState()

    /** 驱动未安装 */
    data object DriverNotInstalled : PermissionSetupState()

    /** Permissions授予Completed */
    data class Completed(val allGranted: Boolean, val grantedCount: Int, val totalCount: Int) : PermissionSetupState()

    /** Error状态 */
    data class Error(val message: String) : PermissionSetupState()
}

/**
 * Permissions设置ViewModel
 * 只负责UI状态管理，具体业务逻辑由Manager和Installer处理
 */
class PermissionSetupViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<PermissionSetupState>(PermissionSetupState.Initializing)
    val state: StateFlow<PermissionSetupState> = _state.asStateFlow()

    private val _navigateToDriverInstall = MutableStateFlow(false)
    val navigateToDriverInstallEvent: StateFlow<Boolean> = _navigateToDriverInstall.asStateFlow()

    private var hasRoot = false

    companion object {
        private const val TAG = "PermissionSetupVM"
    }

    /**
     * 开始Permissions检查流程
     * 先快速检测所有Permissions和驱动状态，如果全部满足则直接跳转主界面
     */
    fun startSetup() {
        viewModelScope.launch {
            // 快速检测所有状态
            val checkResult = PermissionManager.quickCheck(getApplication())

            if (checkResult.allSatisfied) {
                // 所有条件都满足，直接Completed
                Log.d(TAG, "All permissions and driver already satisfied, skip to main")
                _state.value = PermissionSetupState.Completed(
                    allGranted = true,
                    grantedCount = PermissionConfig.REQUIRED_PERMISSIONS.size + PermissionConfig.REQUIRED_APP_OPS.size,
                    totalCount = PermissionConfig.REQUIRED_PERMISSIONS.size + PermissionConfig.REQUIRED_APP_OPS.size
                )
                return@launch
            }

            // 如果有rootPermissions但Permissions未全部授予且驱动已安装，自动授权
            if (checkResult.hasRoot && !checkResult.allPermissionsGranted && checkResult.driverInstalled) {
                Log.d(TAG, "Has root but missing permissions, auto granting")
                hasRoot = true
                _state.value = PermissionSetupState.WaitingUserConfirm
                grantPermissions()
                return@launch
            }

            // 如果有rootPermissions、Permissions已全部授予但驱动未安装，只需要安装驱动
            if (checkResult.hasRoot && checkResult.allPermissionsGranted && !checkResult.driverInstalled) {
                Log.d(TAG, "Has root and permissions, but driver not installed")
                hasRoot = true
                checkDriver(
                    grantedCount = PermissionConfig.REQUIRED_PERMISSIONS.size + PermissionConfig.REQUIRED_APP_OPS.size,
                    totalCount = PermissionConfig.REQUIRED_PERMISSIONS.size + PermissionConfig.REQUIRED_APP_OPS.size
                )
                return@launch
            }

            // 如果有root且Permissions未授予且驱动未安装，自动授权后检查驱动
            if (checkResult.hasRoot && !checkResult.allPermissionsGranted) {
                Log.d(TAG, "Has root but missing permissions and driver, auto granting then check driver")
                hasRoot = true
                _state.value = PermissionSetupState.WaitingUserConfirm
                grantPermissions()
                return@launch
            }

            // 其他情况，走正常的Permissions检查流程
            checkRootPermission()
        }
    }

    /**
     * 检查RootPermissions
     */
    private suspend fun checkRootPermission() {
        _state.value = PermissionSetupState.CheckingRoot

        withContext(Dispatchers.IO) {
            try {
                // 获取Customroot检查命令
                hasRoot = RootPermissionUtils.checkRootAccess()
                Log.d(TAG, "Root access check, result: $hasRoot")

                withContext(Dispatchers.Main) {
                    if (hasRoot) {
                        // 有rootPermissions，询问用户是否使用root授权
                        _state.value = PermissionSetupState.WaitingUserConfirm
                    } else {
                        // 没有rootPermissions
                        _state.value = PermissionSetupState.NoRoot
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking root access", e)
                withContext(Dispatchers.Main) {
                    _state.value = PermissionSetupState.Error("An error occurred while checking root access: ${e.message}")
                }
            }
        }
    }

    /**
     * 用户确认使用Root授权
     */
    fun confirmUseRoot() {
        viewModelScope.launch {
            grantPermissions()
        }
    }

    /**
     * 授予所有Permissions
     * 委托给PermissionManager处理具体逻辑
     */
    private suspend fun grantPermissions() {
        withContext(Dispatchers.IO) {
            try {
                val (grantedCount, totalCount) = PermissionManager.grantAllPermissions(
                    app = getApplication(),
                    onProgress = { current, total, permissionName ->
                        // Log.d(TAG, "授权进度条: $current/$total - $permissionName")
                        // 更新UI状态
                        viewModelScope.launch(Dispatchers.Main) {
                            _state.value = PermissionSetupState.GrantingPermissions(
                                current = current,
                                total = total,
                                currentPermission = permissionName
                            )
                        }
                    }
                )

                // Permissions授予Completed，检查是否需要应用究极免杀保护
                if (AntiKillProtectionManager.isEnabled()) {
                    applyAntiKillProtection(grantedCount, totalCount)
                } else {
                    // 不需要应用保护，直接检查驱动
                    checkDriver(grantedCount, totalCount)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error granting permissions", e)
                withContext(Dispatchers.Main) {
                    _state.value = PermissionSetupState.Error("An error occurred while granting permissions: ${e.message}")
                }
            }
        }
    }

    /**
     * 应用究极免杀保护
     */
    private suspend fun applyAntiKillProtection(grantedCount: Int = 0, totalCount: Int = 0) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Applying anti-kill protection")
                val (successCount, protectionTotal) = AntiKillProtectionManager.applyProtection(
                    context = getApplication(),
                    onProgress = { current, total, measureName ->
                        viewModelScope.launch(Dispatchers.Main) {
                            _state.value = PermissionSetupState.ApplyingAntiKillProtection(
                                current = current,
                                total = total,
                                currentMeasure = measureName
                            )
                        }
                    }
                )

                Log.d(TAG, "Anti-kill protection applied: $successCount/$protectionTotal")

                // 应用保护Completed后，继续检查驱动
                checkDriver(grantedCount, totalCount)
            } catch (e: Exception) {
                Log.e(TAG, "Error applying anti-kill protection", e)
                // 即使保护应用失败，也继续检查驱动，不阻止流程
                checkDriver(grantedCount, totalCount)
            }
        }
    }

    /**
     * 检查驱动是否已安装
     * 委托给DriverInstaller处理具体逻辑
     */
    private suspend fun checkDriver(grantedCount: Int = 0, totalCount: Int = 0) {
        withContext(Dispatchers.Main) {
            _state.value = PermissionSetupState.CheckingDriver
        }
        withContext(Dispatchers.IO) {
            try {
                val (installed, _) = DriverInstaller.checkAndSetupDriver(getApplication())

                withContext(Dispatchers.Main) {
                    if (installed) {
                        _state.value = PermissionSetupState.Completed(
                            allGranted = grantedCount == totalCount,
                            grantedCount = grantedCount,
                            totalCount = totalCount
                        )
                    } else {
                        _state.value = PermissionSetupState.DriverNotInstalled
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking driver", e)
                withContext(Dispatchers.Main) {
                    _state.value = PermissionSetupState.DriverNotInstalled
                }
            }
        }
    }

    /**
     * 跳Go to驱动安装界面
     */
    fun navigateToDriverInstall() {
        _navigateToDriverInstall.value = true
        Log.d(TAG, "Navigate to driver install")
    }

    /**
     * Reset导航事件
     */
    fun resetNavigationEvent() {
        _navigateToDriverInstall.value = false
    }

    /**
     * 重试Root检查
     */
    fun retryRootCheck() {
        viewModelScope.launch {
            checkRootPermission()
        }
    }
}
