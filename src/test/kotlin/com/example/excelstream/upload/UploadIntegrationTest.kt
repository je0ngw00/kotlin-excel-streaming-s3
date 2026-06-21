package com.example.excelstream.upload

import com.example.excelstream.sample.generate
import com.example.excelstream.support.LocalStackTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files

@SpringBootTest
class UploadIntegrationTest @Autowired constructor(
    private val service: UploadService,
    private val jdbc: JdbcTemplate,
) : LocalStackTestBase() {

    @BeforeEach
    fun cleanup() {
        jdbc.update("DELETE FROM members")
    }

    @Test
    fun `업로드 전 경로가 실제 S3를 거쳐 DB에 적재된다`() {
        val tmp = Files.createTempFile("it-", ".xlsx")
        generate(100, tmp.toString())
        val bytes = Files.readAllBytes(tmp)
        Files.deleteIfExists(tmp)

        val file = MockMultipartFile("file", "sample.xlsx", null, bytes)
        val count = service.handle(file)

        assertThat(count).isEqualTo(100L)
        val dbCount = jdbc.queryForObject("SELECT COUNT(*) FROM members", Int::class.java)
        assertThat(dbCount).isEqualTo(100)
    }
}
