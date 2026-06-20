package com.example.excelstream.download

import com.example.excelstream.excel.S3MultipartSink
import com.example.excelstream.excel.StreamingXlsxWriter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Duration

@Component
class ExportJobRunner(
    private val fetcher: MemberPageFetcher,
    private val sink: S3MultipartSink,
    private val presigner: S3Presigner,
    private val store: ExportJobStore,
) {
    @Value("\${app.s3.bucket}")
    private lateinit var bucket: String

    private val log = LoggerFactory.getLogger(ExportJobRunner::class.java)

    @Async
    fun run(jobId: String) {
        try {
            val key = "exports/$jobId.xlsx"
            val writer = StreamingXlsxWriter()
            sink.upload(key) { os -> writer.write(os, fetcher.rows()) }
            store.set(jobId, "DONE|${presignedUrl(key)}")
        } catch (e: Exception) {
            // 비동기 작업이라 스택트레이스를 잃지 않도록 반드시 로깅한다.
            // 상태 문자열엔 null 대신 예외 클래스명이라도 남긴다.
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
