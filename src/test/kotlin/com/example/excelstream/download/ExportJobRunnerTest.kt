package com.example.excelstream.download

import com.example.excelstream.excel.S3MultipartSink
import com.example.excelstream.excel.StreamingCsvWriter
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest
import java.io.OutputStream
import java.net.URI

class ExportJobRunnerTest {

    private val fetcher = mockk<MemberPageFetcher>(relaxed = true)
    private val sink = mockk<S3MultipartSink>()
    private val presigner = mockk<S3Presigner>()
    private val store = mockk<ExportJobStore>(relaxed = true)
    private val csvWriter = mockk<StreamingCsvWriter>(relaxed = true)
    private val runner = ExportJobRunner(fetcher, sink, presigner, store, csvWriter).also {
        ReflectionTestUtils.setField(it, "bucket", "excel-bucket")
    }

    private fun stubPresigner(url: String) {
        val presigned = mockk<PresignedGetObjectRequest>()
        every { presigned.url() } returns URI(url).toURL()
        every { presigner.presignGetObject(any<GetObjectPresignRequest>()) } returns presigned
    }

    @Test
    fun `xlsx 성공 시 exports 키에 xlsx 확장자를 쓰고 DONE에 presigned URL을 남긴다`() {
        val keySlot = slot<String>()
        every { sink.upload(capture(keySlot), any()) } just Runs
        stubPresigner("http://localstack/excel-bucket/exports/job1.xlsx?sig")

        runner.run("job1", ExportFormat.XLSX)

        assertThat(keySlot.captured).isEqualTo("exports/job1.xlsx")
        verify { store.set("job1", match { it.startsWith("DONE|http") }) }
    }

    @Test
    fun `csv 성공 시 csv 확장자 키를 쓴다`() {
        val keySlot = slot<String>()
        every { sink.upload(capture(keySlot), any()) } just Runs
        stubPresigner("http://localstack/excel-bucket/exports/job2.csv?sig")

        runner.run("job2", ExportFormat.CSV)

        assertThat(keySlot.captured).isEqualTo("exports/job2.csv")
    }

    @Test
    fun `업로드 실패 시 FAILED 상태로 메시지를 남긴다`() {
        every { sink.upload(any(), any()) } throws RuntimeException("boom")

        runner.run("job3", ExportFormat.XLSX)

        verify { store.set("job3", match { it.startsWith("FAILED|") && it.contains("boom") }) }
    }
}
