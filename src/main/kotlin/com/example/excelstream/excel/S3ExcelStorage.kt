package com.example.excelstream.excel

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

@Component
class S3ExcelStorage(private val s3: S3Client) {

    @Value("\${app.s3.bucket}")
    private lateinit var bucket: String

    fun put(key: String, input: InputStream, contentLength: Long) {
        s3.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).build(),
            RequestBody.fromInputStream(input, contentLength),
        )
    }

    fun downloadToTemp(key: String): Path {
        // createTempFile 이 빈 파일을 먼저 만드는데, SDK 의 toFile 변환기는
        // 대상 파일이 이미 있으면 실패한다. 유니크한 이름만 확보하고 즉시 지워
        // SDK 가 새로 쓰게 한다.
        val tmp = Files.createTempFile("xlsx-", ".xlsx")
        Files.delete(tmp)
        s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), tmp)
        return tmp
    }
}
