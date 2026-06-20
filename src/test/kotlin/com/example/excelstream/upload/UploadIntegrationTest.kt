package com.example.excelstream.upload

import com.example.excelstream.sample.generate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files

@SpringBootTest
@Testcontainers
class UploadIntegrationTest @Autowired constructor(
    private val service: UploadService,
    private val jdbc: JdbcTemplate,
) {
    companion object {
        @Container
        @JvmStatic
        val localstack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:3"))
                .withServices(Service.S3)

        @JvmStatic
        @BeforeAll
        fun createBucket() {
            localstack.execInContainer("awslocal", "s3", "mb", "s3://excel-bucket")
        }

        @JvmStatic
        @DynamicPropertySource
        fun s3Props(registry: DynamicPropertyRegistry) {
            registry.add("app.s3.endpoint") { localstack.getEndpointOverride(Service.S3).toString() }
            registry.add("app.s3.region") { localstack.region }
            registry.add("app.s3.access-key") { localstack.accessKey }
            registry.add("app.s3.secret-key") { localstack.secretKey }
            registry.add("app.s3.bucket") { "excel-bucket" }
        }
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
