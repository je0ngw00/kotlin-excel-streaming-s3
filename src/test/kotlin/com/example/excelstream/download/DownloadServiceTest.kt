package com.example.excelstream.download

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DownloadServiceTest {

    private val store = mockk<ExportJobStore>(relaxed = true)
    private val runner = mockk<ExportJobRunner>(relaxed = true)
    private val service = DownloadService(store, runner)

    @Test
    fun `startExport 는 RUNNING 을 먼저 세팅하고 runner 에 포맷을 위임한 뒤 jobId 를 반환한다`() {
        val jobId = service.startExport(ExportFormat.CSV)

        assertThat(jobId).isNotBlank()
        verifyOrder {
            store.set(jobId, "RUNNING")
            runner.run(jobId, ExportFormat.CSV)
        }
    }

    @Test
    fun `포맷 미지정 시 기본은 XLSX`() {
        val jobId = service.startExport()
        verify { runner.run(jobId, ExportFormat.XLSX) }
    }

    @Test
    fun `status 는 store 값을 그대로 돌려준다`() {
        every { store.get("j") } returns "DONE|http://x"
        assertThat(service.status("j")).isEqualTo("DONE|http://x")
    }
}
