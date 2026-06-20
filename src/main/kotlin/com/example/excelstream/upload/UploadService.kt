package com.example.excelstream.upload

import com.example.excelstream.domain.MemberBatchRepository
import com.example.excelstream.excel.S3ExcelStorage
import com.example.excelstream.excel.StreamingXlsxReader
import com.example.excelstream.support.MemoryProbe
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.util.UUID

@Service
class UploadService(
    private val storage: S3ExcelStorage,
    private val repo: MemberBatchRepository,
) {
    private val reader = StreamingXlsxReader()

    fun handle(file: MultipartFile): Long {
        val key = "uploads/${UUID.randomUUID()}.xlsx"
        storage.put(key, file.inputStream, file.size)

        val tmp = storage.downloadToTemp(key)
        try {
            val buffer = ArrayList<Array<Any?>>(FLUSH_SIZE)
            var count = 0L
            reader.read(tmp) { cells ->
                buffer.add(arrayOf(cells.getOrNull(1), cells.getOrNull(2), cells.getOrNull(3)?.toLong()))
                if (buffer.size >= FLUSH_SIZE) {
                    repo.insertBatch(buffer)
                    count += buffer.size
                    buffer.clear()
                    MemoryProbe.log("upload-insert", count)
                }
            }
            if (buffer.isNotEmpty()) {
                repo.insertBatch(buffer)
                count += buffer.size
            }
            MemoryProbe.log("upload-done", count)
            return count
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    companion object {
        private const val FLUSH_SIZE = 1000
    }
}
