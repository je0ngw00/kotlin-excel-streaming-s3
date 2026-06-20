package com.example.excelstream.download

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class ExportJobStore {
    private val jobs = ConcurrentHashMap<String, String>()

    fun set(jobId: String, status: String) { jobs[jobId] = status }
    fun get(jobId: String): String = jobs.getOrDefault(jobId, "NOT_FOUND")
}
