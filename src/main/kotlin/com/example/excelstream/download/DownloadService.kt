package com.example.excelstream.download

import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DownloadService(
    private val store: ExportJobStore,
    private val runner: ExportJobRunner,
) {
    fun startExport(format: ExportFormat = ExportFormat.XLSX): String {
        val jobId = UUID.randomUUID().toString()
        store.set(jobId, ExportStatus.RUNNING)
        runner.run(jobId, format)
        return jobId
    }

    fun status(jobId: String): String = store.get(jobId)
}
