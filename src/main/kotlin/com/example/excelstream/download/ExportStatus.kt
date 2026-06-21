package com.example.excelstream.download

/** 작업 상태 문자열 인코딩을 한곳에서 관리한다. 외부 응답 포맷(RUNNING / DONE|url / FAILED|msg)은 그대로 유지. */
object ExportStatus {
    const val RUNNING = "RUNNING"
    fun done(url: String) = "DONE|$url"
    fun failed(message: String) = "FAILED|$message"
    fun isDone(status: String) = status.startsWith("DONE|")
    fun isFailed(status: String) = status.startsWith("FAILED|")
    fun urlOf(status: String): String = status.removePrefix("DONE|")
}
