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

        val os = body.outputStream()
        try {
            writer(os)
            os.close()                          // 정상 종료 신호 → 업로드 완료로 진행
            upload.completionFuture().join()
        } catch (e: Exception) {
            // writer 가 중간에 실패하면 진행 중인 멀티파트 업로드를 취소해
            // 잘린(truncated) 객체가 완성되거나 future 가 방치되는 것을 막는다.
            upload.completionFuture().cancel(true)
            throw e
        }
    }
}
