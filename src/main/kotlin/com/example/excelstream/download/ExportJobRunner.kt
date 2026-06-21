package com.example.excelstream.download

import com.example.excelstream.excel.S3MultipartSink
import com.example.excelstream.excel.StreamingCsvWriter
import com.example.excelstream.excel.StreamingXlsxWriter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.io.OutputStream
import java.time.Duration

@Component
class ExportJobRunner(
    private val fetcher: MemberPageFetcher,
    private val sink: S3MultipartSink,
    private val presigner: S3Presigner,
    private val store: ExportJobStore,
    private val csvWriter: StreamingCsvWriter,
    private val xlsxWriter: StreamingXlsxWriter,
) {
    @Value("\${app.s3.bucket}")
    private lateinit var bucket: String

    private val log = LoggerFactory.getLogger(ExportJobRunner::class.java)

    @Async
    fun run(jobId: String, format: ExportFormat = ExportFormat.XLSX) {
        try {
            val key = "exports/$jobId.${format.ext}"
            val writeRows: (OutputStream) -> Unit = when (format) {
                ExportFormat.XLSX -> { os -> xlsxWriter.write(os, fetcher.rows()) }
                ExportFormat.CSV -> { os -> csvWriter.write(os, fetcher.rows()) }
            }
            sink.upload(key, writeRows)
            store.set(jobId, "DONE|${presignedUrl(key)}")
        } catch (e: Exception) {
            // 비동기 작업이라 스택트레이스를 잃지 않도록 반드시 로깅한다.
            log.error("export 실패 jobId={}", jobId, e)
            store.set(jobId, "FAILED|${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun presignedUrl(key: String): String =
        presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(30))
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build(),
        ).url().toString()
}
