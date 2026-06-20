package com.example.excelstream.excel

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.BlockingOutputStreamAsyncRequestBody
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.transfer.s3.S3TransferManager
import software.amazon.awssdk.transfer.s3.model.UploadRequest
import java.io.OutputStream

@Component
class S3MultipartSink(private val tm: S3TransferManager) {

    @Value("\${app.s3.bucket}")
    private lateinit var bucket: String

    fun upload(key: String, writer: (OutputStream) -> Unit) {
        val body: BlockingOutputStreamAsyncRequestBody =
            AsyncRequestBody.forBlockingOutputStream(null)

        val upload = tm.upload(
            UploadRequest.builder()
                .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).build())
                .requestBody(body)
                .build(),
        )

        body.outputStream().use { os ->
            writer(os)
        }
        upload.completionFuture().join()
    }
}
