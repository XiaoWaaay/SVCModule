package moe.fuqiuluo.mamu.floating.data.local

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import com.github.doyaaaaaken.kotlincsv.dsl.csvWriter
import moe.fuqiuluo.mamu.data.local.RootFileSystem
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange
import moe.fuqiuluo.mamu.floating.data.model.SavedAddress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

object SavedAddressRepository {
    private const val FILE_EXTENSION = ".csv"

    @SuppressLint("SdCardPath")
    private const val DEFAULT_DIR = "/sdcard/Mamu/addresses"

    // CSV 表头
    private val CSV_HEADERS =
        listOf("address", "name", "type", "value", "frozen", "range", "timestamp")

    /**
     * 保存Address列表To指定Path
     */
    fun saveAddresses(
        context: Context,
        addresses: List<SavedAddress>,
        filePath: String? = null,
        fileName: String = "addresses_${System.currentTimeMillis()}"
    ): Boolean {
        if (!RootFileSystem.isConnected()) {
            return saveAddressesLocal(context, addresses, fileName)
        }

        return try {
            val targetPath = filePath ?: "$DEFAULT_DIR/$fileName$FILE_EXTENSION"
            Log.d("SavedAddressRepository", "Saving addresses to $targetPath")

            // 序列化为 CSV 字符串
            val csvContent = serializeAddresses(addresses)

            // 写入文件
            if (RootFileSystem.writeText(targetPath, csvContent)) {
                true
            } else {
                saveAddressesLocal(context, addresses, fileName)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 降级To本地存储
            saveAddressesLocal(context, addresses, fileName)
        }
    }

    /**
     * From指定Path加载Address列表
     */
    fun loadAddresses(context: Context, fileName: String): List<SavedAddress> {
        if (!RootFileSystem.isConnected()) {
            return loadAddressesLocal(context, fileName)
        }

        return try {
            val targetPath = if (fileName.startsWith("/")) {
                fileName
            } else {
                "$DEFAULT_DIR/$fileName$FILE_EXTENSION"
            }

            val csvContent =
                RootFileSystem.readText(targetPath) ?: return loadAddressesLocal(context, fileName)

            deserializeAddresses(csvContent)
        } catch (e: Exception) {
            e.printStackTrace()
            loadAddressesLocal(context, fileName)
        }
    }

    /**
     * 获取已保存的Address列表文件名
     */
    fun getSavedListNames(context: Context, directory: String? = null): List<String> {
        val names = mutableListOf<String>()

        // From root 文件系统获取
        if (RootFileSystem.isConnected()) {
            try {
                RootFileSystem.listFiles(directory ?: DEFAULT_DIR).forEach { file ->
                    if (file.name.endsWith(FILE_EXTENSION)) {
                        names.add(file.name.removeSuffix(FILE_EXTENSION))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // From本地存储获取
        val localDir = File(context.filesDir, "saved_addresses")
        if (localDir.exists() && localDir.isDirectory) {
            localDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(FILE_EXTENSION)) {
                    val name = file.name.removeSuffix(FILE_EXTENSION)
                    if (name !in names) {
                        names.add("(Local) $name")
                    }
                }
            }
        }

        return names.sorted()
    }

    /**
     * Delete保存的Address列表
     */
    fun deleteAddressList(context: Context, fileName: String): Boolean {
        if (RootFileSystem.isConnected()) {
            val deleted = RootFileSystem.delete("$DEFAULT_DIR/$fileName$FILE_EXTENSION")
            if (deleted) return true
        }

        val localFile = File(context.filesDir, "saved_addresses/$fileName$FILE_EXTENSION")
        if (localFile.exists()) {
            return localFile.delete()
        }

        return false
    }

    private fun saveAddressesLocal(
        context: Context,
        addresses: List<SavedAddress>,
        fileName: String
    ): Boolean {
        return try {
            val dir = File(context.filesDir, "saved_addresses")
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val file = File(dir, "$fileName$FILE_EXTENSION")
            val csvContent = serializeAddresses(addresses)
            file.writeText(csvContent, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun loadAddressesLocal(context: Context, fileName: String): List<SavedAddress> {
        return try {
            val cleanName = fileName.removePrefix("(Local) ")
            val file = File(context.filesDir, "saved_addresses/$cleanName$FILE_EXTENSION")
            if (!file.exists()) {
                return emptyList()
            }

            val csvContent = file.readText(Charsets.UTF_8)
            deserializeAddresses(csvContent)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 将Address列表序列化为 CSV 字符串
     */
    private fun serializeAddresses(addresses: List<SavedAddress>): String {
        val writer = ByteArrayOutputStream()

        csvWriter().open(writer) {
            // 写入表头
            writeRow(CSV_HEADERS)

            // 写入数据行
            addresses.forEach { addr ->
                writeRow(
                    "0x${addr.address.toString(16).uppercase()}",
                    addr.name,
                    addr.valueType.toString(),
                    addr.value,
                    addr.isFrozen.toString(),
                    addr.range.code,
                    addr.timestamp.toString()
                )
            }
        }

        return writer.toByteArray().decodeToString()
    }

    /**
     * From CSV 字符串反序列化Address列表
     */
    private fun deserializeAddresses(csvContent: String): List<SavedAddress> {
        val addresses = mutableListOf<SavedAddress>()
        val reader = ByteArrayInputStream(csvContent.toByteArray())

        try {
            csvReader().open(reader) {
                readAllWithHeaderAsSequence().forEach { row ->
                    try {
                        val addressStr = row["address"] ?: return@forEach
                        val address = if (addressStr.startsWith("0x", ignoreCase = true)) {
                            addressStr.substring(2).toULongOrNull(16)?.toLong()
                        } else {
                            addressStr.toLongOrNull()
                        } ?: return@forEach

                        val name = row["name"] ?: "Unknown"
                        val valueType = row["type"]?.toIntOrNull() ?: 4
                        val value = row["value"] ?: "0"
                        val frozen = row["frozen"]?.toBooleanStrictOrNull() ?: false
                        val rangeCode = row["range"] ?: "An"
                        val range = MemoryRange.fromCode(rangeCode) ?: MemoryRange.An
                        val timestamp =
                            row["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()

                        addresses.add(
                            SavedAddress(
                                address = address,
                                name = name,
                                valueType = valueType,
                                value = value,
                                isFrozen = frozen,
                                range = range,
                                timestamp = timestamp
                            )
                        )
                    } catch (e: Exception) {
                        // 跳过解析失败的行
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return addresses
    }
}
