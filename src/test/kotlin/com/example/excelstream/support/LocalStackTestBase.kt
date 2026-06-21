package com.example.excelstream.support

import org.junit.jupiter.api.BeforeAll
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
abstract class LocalStackTestBase {
    companion object {
        @JvmStatic
        val localstack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:3"))
                .withServices(Service.S3)
                .also { it.start() }

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
}
