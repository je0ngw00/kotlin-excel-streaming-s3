package com.example.excelstream.download

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class DownloadController(private val service: DownloadService) {

    @PostMapping("/export")
    fun export(): String = service.startExport()

    @GetMapping("/export/{jobId}")
    fun status(@PathVariable jobId: String): String = service.status(jobId)
}
