package com.example.excelstream.download

import com.example.excelstream.excel.StreamingCsvWriter
import com.example.excelstream.excel.StreamingXlsxReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

@SpringBootTest
@Testcontainers
class DownloadIntegrationTest @Autowired constructor(
    private val service: DownloadService,
    private val fetcher: MemberPageFetcher,
    private val csvWriter: StreamingCsvWriter,
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

    @BeforeEach
    fun seed() {
        jdbc.update("DELETE FROM members")
        repeat(3) { i ->
            jdbc.update(
                "INSERT INTO members (email, name, amount) VALUES (?, ?, ?)",
                "user$i@example.com", "name$i", (i * 100).toLong(),
            )
        }
    }

    /** RUNNING 동안 폴링하다 DONE|url 또는 FAILED|msg 를 돌려준다. */
    private fun await(jobId: String): String {
        repeat(100) {
            val status = service.status(jobId)
            if (status.startsWith("DONE|") || status.startsWith("FAILED|")) return status
            Thread.sleep(100)
        }
        error("export 가 시간 내 끝나지 않음 jobId=$jobId")
    }

    private fun httpGet(url: String): ByteArray {
        val res = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI(url)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        assertThat(res.statusCode()).isEqualTo(200)
        return res.body()
    }

    @Test
    fun `동기 CSV 는 시드된 멤버를 응답 본문으로 흘려보낸다`() {
        val out = ByteArrayOutputStream()
        csvWriter.write(out, fetcher.rows())
        val csv = out.toString(Charsets.UTF_8)

        assertThat(csv.lines().first()).isEqualTo("email,name,amount")
        assertThat(csv).contains("user0@example.com,name0,0")
        assertThat(csv).contains("user2@example.com,name2,200")
    }

    @Test
    fun `비동기 xlsx 는 S3 업로드 후 presigned URL 로 받아 파싱하면 3행이 나온다`() {
        val jobId = service.startExport(ExportFormat.XLSX)

        val status = await(jobId)
        assertThat(status).startsWith("DONE|")
        val url = status.removePrefix("DONE|")

        val tmp = Files.createTempFile("dl-", ".xlsx")
        Files.write(tmp, httpGet(url))
        val rows = mutableListOf<Map<String, String?>>()
        StreamingXlsxReader().read(tmp) { rows.add(it) }
        Files.deleteIfExists(tmp)

        assertThat(rows).hasSize(3)
        assertThat(rows[0]).containsEntry("email", "user0@example.com")
            .containsEntry("amount", "0")
    }

    @Test
    fun `비동기 csv 는 S3 업로드 후 presigned URL 로 받으면 CSV 본문이 나온다`() {
        val jobId = service.startExport(ExportFormat.CSV)

        val status = await(jobId)
        assertThat(status).startsWith("DONE|")
        val url = status.removePrefix("DONE|")

        val body = String(httpGet(url), Charsets.UTF_8)
        assertThat(body.lines().first()).isEqualTo("email,name,amount")
        assertThat(body).contains("user1@example.com,name1,100")
    }
}
