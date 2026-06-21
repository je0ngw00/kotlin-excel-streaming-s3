package com.example.excelstream.download

import com.example.excelstream.excel.StreamingCsvWriter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class DownloadControllerTest {

    private val service = mockk<DownloadService>()
    private val fetcher = mockk<MemberPageFetcher>()
    private val csvWriter = StreamingCsvWriter() // 실제 writer 로 바디 검증
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(DownloadController(service, fetcher, csvWriter))
            .build()
    }

    @Test
    fun `POST export 는 기본 xlsx 포맷으로 작업을 시작하고 jobId 를 반환한다`() {
        every { service.startExport(ExportFormat.XLSX) } returns "job-1"

        mockMvc.perform(post("/export"))
            .andExpect(status().isOk)
            .andExpect(content().string("job-1"))

        verify { service.startExport(ExportFormat.XLSX) }
    }

    @Test
    fun `POST export format=csv 는 CSV 포맷으로 작업을 시작한다`() {
        every { service.startExport(ExportFormat.CSV) } returns "job-2"

        mockMvc.perform(post("/export").param("format", "csv"))
            .andExpect(status().isOk)
            .andExpect(content().string("job-2"))

        verify { service.startExport(ExportFormat.CSV) }
    }

    @Test
    fun `POST export 지원하지 않는 format 은 400 을 반환한다`() {
        mockMvc.perform(post("/export").param("format", "pdf"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `GET export jobId 는 상태 문자열을 반환한다`() {
        every { service.status("job-1") } returns "DONE|http://x"

        mockMvc.perform(get("/export/job-1"))
            .andExpect(status().isOk)
            .andExpect(content().string("DONE|http://x"))
    }

    @Test
    fun `GET export_csv 는 text-csv 로 CSV 본문을 스트리밍한다`() {
        every { fetcher.rows() } returns sequenceOf<Array<Any?>>(
            arrayOf("a@example.com", "alice", 100L),
        )

        val mvcResult = mockMvc.perform(get("/export.csv"))
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(mvcResult))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", containsString("text/csv")))
            .andExpect(header().string("Content-Disposition", containsString("members.csv")))
            .andExpect(content().string(containsString("email,name,amount")))
            .andExpect(content().string(containsString("a@example.com,alice,100")))
    }
}
