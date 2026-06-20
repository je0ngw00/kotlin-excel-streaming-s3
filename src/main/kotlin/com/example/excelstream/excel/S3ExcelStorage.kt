package com.example.excelstream.excel

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.InputStream

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
}
