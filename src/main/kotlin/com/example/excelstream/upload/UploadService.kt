package com.example.excelstream.upload

import com.example.excelstream.domain.MemberBatchRepository
import com.example.excelstream.excel.S3ExcelStorage
import com.example.excelstream.excel.StreamingXlsxReader
import com.example.excelstream.support.MemoryProbe
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
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
                val email = cells.getOrNull(1)?.trim()
                val name = cells.getOrNull(2)?.trim()
                val amount = cells.getOrNull(3)
                // 완전히 빈 행(셀이 없거나 모두 공백)은 건너뛴다.
                if (email.isNullOrEmpty() && name.isNullOrEmpty() && amount.isNullOrBlank()) {
                    return@read
                }
                buffer.add(arrayOf(email, name, parseAmount(amount)))
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

    /**
     * POI DataFormatter 가 돌려주는 표시 문자열을 Long 으로 변환한다.
     * "1,000"(천단위), "100.0"(소수 서식), "1E+5"(지수 서식)까지 견고하게 처리한다.
     * 값이 비어 있으면 null, 숫자로 해석 불가하면 예외를 던진다.
     */
    private fun parseAmount(raw: String?): Long? {
        val s = raw?.trim()?.replace(",", "")
        if (s.isNullOrEmpty()) return null
        return BigDecimal(s).toLong()
    }

    companion object {
        private const val FLUSH_SIZE = 1000
    }
}
