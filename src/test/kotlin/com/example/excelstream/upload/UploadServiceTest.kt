package com.example.excelstream.upload

import com.example.excelstream.domain.MemberBatchRepository
import com.example.excelstream.excel.S3ExcelStorage
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

    /** id,email,name,amount 헤더 + dataRows 건의 표준 xlsx 바이트를 만든다. */
    private fun xlsxBytes(dir: Path, dataRows: Int): ByteArray {
        val f = dir.resolve("src-$dataRows.xlsx")
        SXSSFWorkbook(100).use { wb ->
            FileOutputStream(f.toFile()).use { out ->
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
        return Files.readAllBytes(f)
    }

    @Test
    fun `1000건 경계로 flush 하고 잔여분도 flush 하며 총 건수를 반환한다`(@TempDir dir: Path) {
        val file = MockMultipartFile("file", "x.xlsx", null, xlsxBytes(dir, 2500))

        val count = service.handle(file)

        assertThat(count).isEqualTo(2500L)
        // 1000 + 1000 + 500 → insertBatch 3회
        verify(exactly = 3) { repo.insertBatch(any()) }
    }

    @Test
    fun `소수 서식 금액도 파싱하고 완전히 빈 행은 건너뛴다`(@TempDir dir: Path) {
        val src = dir.resolve("mixed.xlsx")
        SXSSFWorkbook(100).use { wb ->
            FileOutputStream(src.toFile()).use { out ->
                val sheet = wb.createSheet("data")
                val header = sheet.createRow(0)
                listOf("id", "email", "name", "amount")
                    .forEachIndexed { i, h -> header.createCell(i).setCellValue(h) }
                // row1: 소수 서식 금액 1000.5 → 1000 으로 파싱
                val r1 = sheet.createRow(1)
                r1.createCell(0).setCellValue(1.0)
                r1.createCell(1).setCellValue("a@example.com")
                r1.createCell(2).setCellValue("alice")
                r1.createCell(3).setCellValue(1000.5)
                // row2: 완전히 빈 행 → 스킵
                sheet.createRow(2)
                wb.write(out)
            }
        }
        val file = MockMultipartFile("file", "x.xlsx", null, Files.readAllBytes(src))

        val captured = mutableListOf<List<Array<Any?>>>()
        val count = service.handle(file)

        // 빈 행은 빠지고 1건만
        assertThat(count).isEqualTo(1L)
        verify { repo.insertBatch(capture(captured)) }
        val row = captured.single().single()
        assertThat(row[0]).isEqualTo("a@example.com")
        assertThat(row[1]).isEqualTo("alice")
        assertThat(row[2]).isEqualTo(1000L) // 1000.5 → 1000
    }
}
