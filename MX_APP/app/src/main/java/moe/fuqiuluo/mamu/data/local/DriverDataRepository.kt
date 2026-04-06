package moe.fuqiuluo.mamu.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.data.model.DashboardDriverInfo
import moe.fuqiuluo.mamu.data.model.DriverStatus
import moe.fuqiuluo.mamu.driver.WuwaDriver

/**
 * 驱动信息数据源
 * 负责查询驱动相关信息
 */
class DriverDataRepository {

    /**
     * 获取驱动信息
     */
    suspend fun getDriverInfo(): DashboardDriverInfo = withContext(Dispatchers.IO) {
        try {
            val loaded = WuwaDriver.loaded
            val status = if (loaded) DriverStatus.LOADED else DriverStatus.NOT_LOADED

            if (loaded) {
                DashboardDriverInfo(
                    status = status,
                    isProcessBound = WuwaDriver.isProcessBound,
                    boundPid = WuwaDriver.currentBindPid
                )
            } else {
                DashboardDriverInfo(status = status)
            }
        } catch (e: Exception) {
            DashboardDriverInfo(
                status = DriverStatus.ERROR,
                errorMessage = e.message
            )
        }
    }
}
