package com.example.excelstream.download

import com.example.excelstream.excel.StreamingCsvWriter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody

@RestController
class DownloadController(
    private val service: DownloadService,
    private val fetcher: MemberPageFetcher,
    private val csvWriter: StreamingCsvWriter,
) {

    /** 비동기 내보내기: xlsx(기본) 또는 csv → S3 업로드 후 presigned URL. */
    @PostMapping("/export")
    fun export(@RequestParam(defaultValue = "xlsx") format: String): String =
        service.startExport(ExportFormat.from(format))

    @GetMapping("/export/{jobId}")
    fun status(@PathVariable jobId: String): String = service.status(jobId)

    /** 동기 스트리밍 내보내기: CSV를 응답으로 한 줄씩 흘려보낸다(서버 디스크/메모리 평탄). */
    @GetMapping("/export.csv")
    fun exportCsv(): ResponseEntity<StreamingResponseBody> =
        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "${ExportFormat.CSV.contentType}; charset=UTF-8")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"members.csv\"")
            .body(StreamingResponseBody { os -> csvWriter.write(os, fetcher.rows()) })

    /** 지원하지 않는 format 등 잘못된 요청 파라미터는 400으로 응답한다. */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleBadRequest(e: IllegalArgumentException): String = e.message ?: "잘못된 요청"
}
