package com.ds.localapi.core

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 进程内环形事件日志（最近 [CAP] 条），供首页日志区展示。
 * 线程安全。替代旧版 MainActivity.appendLog 的无限增长字符串拼接。
 *
 * 写入方：RateGovernor（限额/冷却/排队）、DeepSeekClient（重试/切指纹）、
 * ThreadTracker（线程创建/回收）、ServerService（启动失败原因）。
 */
object EventLog {

    private const val CAP = 300

    private data class Entry(val at: Long, val tag: String, val msg: String)

    private val buf = ArrayDeque<Entry>(CAP)
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(tag: String, msg: String) {
        synchronized(buf) {
            if (buf.size >= CAP) buf.pollFirst()
            buf.addLast(Entry(System.currentTimeMillis(), tag, msg))
        }
    }

    /** 返回当前缓冲快照（旧→新），每行形如 `HH:mm:ss [tag] msg`。 */
    fun snapshot(): List<String> = synchronized(buf) {
        buf.map { "${fmt.format(Date(it.at))} [${it.tag}] ${it.msg}" }
    }
}
