package com.example.excelstream.excel

import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.FileOutputStream
import java.nio.file.Path

class StreamingXlsxReaderTest {

    private val reader = StreamingXlsxReader()

    @Test
    fun `헤더 행은 건너뛰고 데이터 행만 콜백으로 전달한다`(@TempDir dir: Path) {
        val file = dir.resolve("simple.xlsx")
        SXSSFWorkbook(100).use { wb ->
            FileOutputStream(file.toFile()).use { out ->
                val sheet = wb.createSheet("data")
                val header = sheet.createRow(0)
                listOf("id", "email", "name", "amount")
                    .forEachIndexed { i, h -> header.createCell(i).setCellValue(h) }
                val r1 = sheet.createRow(1)
                r1.createCell(0).setCellValue(1.0)
                r1.createCell(1).setCellValue("a@example.com")
                r1.createCell(2).setCellValue("alice")
                r1.createCell(3).setCellValue(100.0)
                val r2 = sheet.createRow(2)
                r2.createCell(0).setCellValue(2.0)
                r2.createCell(1).setCellValue("b@example.com")
                r2.createCell(2).setCellValue("bob")
                r2.createCell(3).setCellValue(200.0)
                wb.write(out)
            }
        }

        val rows = mutableListOf<List<String?>>()
        reader.read(file) { rows.add(it) }

        assertThat(rows).hasSize(2)
        assertThat(rows[0]).containsExactly("1", "a@example.com", "alice", "100")
        assertThat(rows[1]).containsExactly("2", "b@example.com", "bob", "200")
    }

    @Test
    fun `행 중간의 빈 셀은 null로 채워 컬럼이 밀리지 않는다`(@TempDir dir: Path) {
        val file = dir.resolve("blank.xlsx")
        SXSSFWorkbook(100).use { wb ->
            FileOutputStream(file.toFile()).use { out ->
                val sheet = wb.createSheet("data")
                val header = sheet.createRow(0)
                listOf("id", "email", "name", "amount")
                    .forEachIndexed { i, h -> header.createCell(i).setCellValue(h) }
                // name(컬럼 2)을 비운다: 셀 0, 1, 3만 생성
                val r1 = sheet.createRow(1)
                r1.createCell(0).setCellValue(1.0)
                r1.createCell(1).setCellValue("a@example.com")
                r1.createCell(3).setCellValue(100.0)
                wb.write(out)
            }
        }

        val rows = mutableListOf<List<String?>>()
        reader.read(file) { rows.add(it) }

        assertThat(rows).hasSize(1)
        assertThat(rows[0]).containsExactly("1", "a@example.com", null, "100")
    }
}
