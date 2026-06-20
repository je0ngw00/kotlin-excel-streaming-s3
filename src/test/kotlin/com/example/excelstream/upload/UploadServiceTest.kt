package com.example.excelstream.upload

import com.example.excelstream.domain.MemberBatchRepository
import com.example.excelstream.excel.S3ExcelStorage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockMultipartFile
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path

class UploadServiceTest {

    private val storage = mockk<S3ExcelStorage>(relaxed = true)
    private val repo = mockk<MemberBatchRepository>(relaxed = true)
    private val service = UploadService(storage, repo)

    private fun makeXlsx(path: Path, dataRows: Int) {
        SXSSFWorkbook(100).use { wb ->
            FileOutputStream(path.toFile()).use { out ->
                val sheet = wb.createSheet("data")
                val header = sheet.createRow(0)
                listOf("id", "email", "name", "amount")
                    .forEachIndexed { i, h -> header.createCell(i).setCellValue(h) }
                for (i in 1..dataRows) {
                    val r = sheet.createRow(i)
                    r.createCell(0).setCellValue(i.toDouble())
                    r.createCell(1).setCellValue("user$i@example.com")
                    r.createCell(2).setCellValue("name$i")
                    r.createCell(3).setCellValue((i * 100).toDouble())
                }
                wb.write(out)
            }
        }
    }

    @Test
    fun `1000건 경계로 flush 하고 잔여분도 flush 하며 총 건수를 반환한다`(@TempDir dir: Path) {
        val tmp = dir.resolve("data.xlsx")
        makeXlsx(tmp, 2500)
        every { storage.downloadToTemp(any()) } returns tmp

        val file = MockMultipartFile("file", "x.xlsx", null, byteArrayOf(1, 2, 3))
        val count = service.handle(file)

        assertThat(count).isEqualTo(2500L)
        // 1000 + 1000 + 500 → insertBatch 3회
        verify(exactly = 3) { repo.insertBatch(any()) }
    }

    @Test
    fun `읽기 후 임시 파일을 삭제한다`(@TempDir dir: Path) {
        val tmp = dir.resolve("data.xlsx")
        makeXlsx(tmp, 10)
        every { storage.downloadToTemp(any()) } returns tmp

        val file = MockMultipartFile("file", "x.xlsx", null, byteArrayOf(1, 2, 3))
        service.handle(file)

        assertThat(Files.exists(tmp)).isFalse()
    }
}
