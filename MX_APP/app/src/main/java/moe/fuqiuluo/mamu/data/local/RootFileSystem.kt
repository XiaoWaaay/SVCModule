package moe.fuqiuluo.mamu.data.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.fuqiuluo.mamu.service.RootFileSystemService
import kotlin.coroutines.resume

private const val TAG = "RootFileSystem"

object RootFileSystem {
    @Volatile
    private var fileSystemManager: FileSystemManager? = null

    @Volatile
    private var isConnecting = false

    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun connect(context: Context): Boolean = suspendCancellableCoroutine { cont ->
        if (fileSystemManager != null) {
            cont.resume(true)
            return@suspendCancellableCoroutine
        }

        if (isConnecting) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }

        isConnecting = true

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                service?.let {
                    fileSystemManager = FileSystemManager.getRemote(it)
                }
                isConnecting = false
                if (cont.isActive) {
                    Log.d(TAG, "Connected to RootFileSystemService")
                    cont.resume(fileSystemManager != null)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                fileSystemManager = null
                isConnecting = false
                Log.e(TAG, "RootFileSystem disconnected")
            }
        }

        val intent = Intent(context, RootFileSystemService::class.java)

        // RootService.bind() 必须在主线程调用
        mainHandler.post {
            RootService.bind(intent, connection)
        }

        cont.invokeOnCancellation {
            isConnecting = false
        }
    }

    /**
     * 检查是否已连接To Root 文件系统
     */
    fun isConnected(): Boolean = fileSystemManager != null

    /**
     * 获取 FileSystemManager 实例
     * @return FileSystemManager 实例，未连接时返回 null
     */
    fun getManager(): FileSystemManager? = fileSystemManager

    /**
     * 获取指定Path的文件
     * @param path 文件Path
     * @return ExtendedFile 实例，未连接时返回 null
     */
    fun getFile(path: String): ExtendedFile? = fileSystemManager?.getFile(path)

    /**
     * 确保目录存在，如果不存在则创建
     * @param path 目录Path
     * @return 是否成功（目录已存在或创建成功）
     */
    fun ensureDirectory(path: String): Boolean {
        val fs = fileSystemManager ?: return false
        return try {
            val dir = fs.getFile(path)
            if (!dir.exists()) {
                dir.mkdirs()
            } else {
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 读取文件内容
     * @param path 文件Path
     * @return 文件内容，失败返回 null
     */
    fun readFile(path: String): ByteArray? {
        val fs = fileSystemManager ?: return null
        return try {
            val file = fs.getFile(path)
            if (!file.exists()) return null
            file.newInputStream().use { it.readBytes() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 读取文件内容为字符串
     * @param path 文件Path
     * @param charset 字符集，默认 UTF-8
     * @return 文件内容字符串，失败返回 null
     */
    fun readText(path: String, charset: java.nio.charset.Charset = Charsets.UTF_8): String? {
        return readFile(path)?.toString(charset)
    }

    /**
     * 写入文件内容
     * @param path 文件Path
     * @param content 文件内容
     * @param createParentDirs 是否自动创建父目录
     * @return 是否成功
     */
    @JvmStatic
    fun writeFile(path: String, content: ByteArray, createParentDirs: Boolean = true): Boolean {
        val fs = fileSystemManager ?: run {
            Log.e(TAG, "RootFileSystem.writeFile fileSystemManager not init")
            return false
        }
        return try {
            val file = fs.getFile(path)
            if (createParentDirs) {
                file.parentFile?.let { parent ->
                    if (!parent.exists()) {
                        parent.mkdirs()
                    }
                }
            }
            file.newOutputStream().use { it.write(content) }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 写入文本内容
     * @param path 文件Path
     * @param content 文本内容
     * @param charset 字符集，默认 UTF-8
     * @param createParentDirs 是否自动创建父目录
     * @return 是否成功
     */
    fun writeText(
        path: String,
        content: String,
        charset: java.nio.charset.Charset = Charsets.UTF_8,
        createParentDirs: Boolean = true
    ): Boolean {
        return writeFile(path, content.toByteArray(charset), createParentDirs)
    }

    /**
     * 检查文件是否存在
     * @param path 文件Path
     * @return 是否存在，未连接时返回 false
     */
    fun exists(path: String): Boolean {
        val fs = fileSystemManager ?: return false
        return try {
            fs.getFile(path).exists()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Delete文件
     * @param path 文件Path
     * @return 是否成功
     */
    @JvmStatic
    fun delete(path: String): Boolean {
        val fs = fileSystemManager ?: return false
        return try {
            val file = fs.getFile(path)
            if (file.exists()) file.delete() else true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 列出目录下的文件
     * @param path 目录Path
     * @return 文件列表，失败返回空列表
     */
    fun listFiles(path: String): List<ExtendedFile> {
        val fs = fileSystemManager ?: return emptyList()
        return try {
            val dir = fs.getFile(path)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.toList() ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
