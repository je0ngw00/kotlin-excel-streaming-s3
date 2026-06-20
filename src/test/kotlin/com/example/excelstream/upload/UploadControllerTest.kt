package com.example.excelstream.upload

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class UploadControllerTest {

    private val service = mockk<UploadService>()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(UploadController(service)).build()
    }

    @Test
    fun `multipart 업로드를 처리하고 inserted 건수를 반환한다`() {
        every { service.handle(any()) } returns 1234L

        val file = MockMultipartFile("file", "x.xlsx", null, byteArrayOf(1, 2, 3))

        mockMvc.perform(multipart("/upload").file(file))
            .andExpect(status().isOk)
            .andExpect(content().string("inserted=1234"))
    }
}
