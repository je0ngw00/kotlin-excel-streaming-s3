package com.example.excelstream.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.transfer.s3.S3TransferManager
import java.net.URI

/**
 * LocalStack S3 접속용 설정.
 * 실제 AWS로 바꾸려면 endpointOverride 만 제거하면 된다.
 */
@Configuration
class S3Config(
    @Value("\${app.s3.endpoint}") private val endpoint: String,
    @Value("\${app.s3.region}") private val region: String,
    @Value("\${app.s3.access-key}") private val accessKey: String,
    @Value("\${app.s3.secret-key}") private val secretKey: String,
) {

    private fun creds() =
        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))

    /** 동기 클라이언트 (단순 putObject/getObject) */
    @Bean
    fun s3Client(): S3Client =
        S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(creds())
            // LocalStack은 path-style 접근이 안전 (가상 호스트 스타일 도메인 미지원)
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()

    /** 비동기 클라이언트 (Transfer Manager 기반 멀티파트 업/다운로드) */
    @Bean
    fun s3AsyncClient(): S3AsyncClient =
        S3AsyncClient.crtBuilder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(creds())
            .forcePathStyle(true)
            .build()

    @Bean
    fun s3TransferManager(s3AsyncClient: S3AsyncClient): S3TransferManager =
        S3TransferManager.builder().s3Client(s3AsyncClient).build()

    /** presigned URL 발급용 (다운로드편 2차에서 사용) */
    @Bean
    fun s3Presigner(): S3Presigner =
        S3Presigner.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(creds())
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
}
