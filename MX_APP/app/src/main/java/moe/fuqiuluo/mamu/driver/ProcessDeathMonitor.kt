package moe.fuqiuluo.mamu.driver

import android.util.Log
import kotlinx.coroutines.*

private const val TAG = "ProcessDeathMonitor"

object ProcessDeathMonitor {
    interface Callback {
        fun onProcessDied(pid: Int)
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitorJob: Job? = null
    private var currentPid: Int = 0
    private val lock = Any()

    fun start(pid: Int, callback: Callback, intervalMs: Long = 1000) {
        synchronized(lock) {
            // Cancel旧的监听任务（非阻塞，立即返回）
            monitorJob?.cancel()

            currentPid = pid

            // 启动新的协程监听任务
            monitorJob = scope.launch {
                Log.d(TAG, "Process death monitor started for PID: $pid with interval: ${intervalMs}ms")

                try {
                    while (isActive) {
                        val alive = try {
                            WuwaDriver.isProcessAlive(pid)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error checking process alive status", e)
                            false
                        }

                        if (!alive) {
                            Log.i(TAG, "Process $pid died, invoking callback")

                            // 在主线程回调
                            withContext(Dispatchers.Main) {
                                callback.onProcessDied(pid)
                            }

                            synchronized(lock) {
                                currentPid = 0
                            }
                            break
                        }

                        delay(intervalMs.coerceAtLeast(100))
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Process death monitor cancelled for PID: $pid")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error in process death monitor", e)
                } finally {
                    synchronized(lock) {
                        currentPid = 0
                    }
                    Log.d(TAG, "Process death monitor stopped for PID: $pid")
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            val pid = currentPid
            if (pid == 0) {
                Log.d(TAG, "No process death monitor is running")
                return
            }

            Log.i(TAG, "Stopping process death monitor for PID: $pid")

            // Cancel协程（非阻塞，立即返回）
            monitorJob?.cancel()
            monitorJob = null
            currentPid = 0

            Log.i(TAG, "Stopped process death monitor for PID: $pid")
        }
    }

    val isMonitoring: Boolean
        get() = synchronized(lock) {
            monitorJob?.isActive == true
        }

    val monitoredPid: Int
        get() = synchronized(lock) {
            if (monitorJob?.isActive == true) currentPid else 0
        }
}
