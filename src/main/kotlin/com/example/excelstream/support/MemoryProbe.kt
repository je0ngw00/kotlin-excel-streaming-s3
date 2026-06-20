package com.example.excelstream.support

import org.slf4j.LoggerFactory

/**
 * 메모리 사용량 관찰용 로거.
 * 블로그에서 XSSF(터짐) vs SAX/SXSSF(평탄) 비교 그래프를 그릴 근거 로그를 남긴다.
 * 출력 형식: [MEM] phase=read rows=50000 heapUsedMB=48
 */
object MemoryProbe {

    private val log = LoggerFactory.getLogger(MemoryProbe::class.java)

    fun heapUsedMB(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
    }

    fun log(phase: String, rows: Long) {
        log.info("[MEM] phase={} rows={} heapUsedMB={}", phase, rows, heapUsedMB())
    }
}
