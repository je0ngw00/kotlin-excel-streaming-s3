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
            // 컨테이너는 JVM 당 한 번만 뜨지만 @BeforeAll 은 서브클래스마다 호출된다.
            // 두 번째 호출은 이미 있는 버킷이라 BucketAlreadyOwnedByYou 가 날 수 있어 무시한다.
            runCatching { localstack.execInContainer("awslocal", "s3", "mb", "s3://excel-bucket") }
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
