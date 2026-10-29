package com.example.speedtest.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 测试结果持久化。
 *
 * 结果数据量很小（最多保留 [MAX_RECORDS] 条），直接以 JSON 数组写入应用私有目录，
 * 不引入数据库。列表按时间倒序存放，最新的在最前。
 */
class ResultStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    fun load(): List<SpeedTestRecord> = synchronized(this) { readAll() }

    /** 追加一条记录，返回写入后的最新列表 */
    fun add(record: SpeedTestRecord): List<SpeedTestRecord> = synchronized(this) {
        val list = (listOf(record) + readAll()).take(MAX_RECORDS)
        writeAll(list)
        list
    }

    fun clear(): List<SpeedTestRecord> = synchronized(this) {
        file.delete()
        emptyList()
    }

    private fun readAll(): List<SpeedTestRecord> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                SpeedTestRecord(
                    id = o.getLong("id"),
                    connection = o.getString("connection"),
                    server = o.getString("server"),
                    pingMs = o.getDouble("pingMs"),
                    jitterMs = o.getDouble("jitterMs"),
                    downloadMbps = o.getDouble("downloadMbps"),
                    uploadMbps = o.getDouble("uploadMbps")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(records: List<SpeedTestRecord>) {
        val array = JSONArray()
        records.forEach { r ->
            array.put(
                JSONObject()
                    .put("id", r.id)
                    .put("connection", r.connection)
                    .put("server", r.server)
                    .put("pingMs", r.pingMs)
                    .put("jitterMs", r.jitterMs)
                    .put("downloadMbps", r.downloadMbps)
                    .put("uploadMbps", r.uploadMbps)
            )
        }
        runCatching { file.writeText(array.toString()) }
    }

    private companion object {
        const val FILE_NAME = "speedtest_results.json"
        const val MAX_RECORDS = 50
    }
}
