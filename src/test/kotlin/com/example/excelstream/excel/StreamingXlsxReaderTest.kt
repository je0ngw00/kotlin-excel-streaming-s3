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
    fun `첫 행을 헤더로 보고 데이터 행을 헤더명 맵으로 전달한다`(@TempDir dir: Path) {
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

        val rows = mutableListOf<Map<String, String?>>()
        reader.read(file) { rows.add(it) }

        assertThat(rows).hasSize(2)
        assertThat(rows[0]).containsEntry("email", "a@example.com")
            .containsEntry("name", "alice").containsEntry("amount", "100")
        assertThat(rows[1]).containsEntry("email", "b@example.com")
            .containsEntry("name", "bob").containsEntry("amount", "200")
    }

    @Test
    fun `컬럼 순서가 달라도 헤더명으로 매핑한다`(@TempDir dir: Path) {
        val file = dir.resolve("reordered.xlsx")
        SXSSFWorkbook(100).use { wb ->
            FileOutputStream(file.toFile()).use { out ->
                val sheet = wb.createSheet("data")
                // 내보내기 파일처럼 id 없이 amount/email/name 순서로 섞어 둔다
                val header = sheet.createRow(0)
                listOf("amount", "email", "name")
                    .forEachIndexed { i, h -> header.createCell(i).setCellValue(h) }
                val r1 = sheet.createRow(1)
                r1.createCell(0).setCellValue(500.0)
                r1.createCell(1).setCellValue("c@example.com")
                r1.createCell(2).setCellValue("carol")
                wb.write(out)
            }
        }

        val rows = mutableListOf<Map<String, String?>>()
        reader.read(file) { rows.add(it) }

        assertThat(rows).hasSize(1)
        assertThat(rows[0]).containsEntry("email", "c@example.com")
            .containsEntry("name", "carol").containsEntry("amount", "500")
    }

    @Test
    fun `행 중간의 빈 셀은 null로 매핑된다`(@TempDir dir: Path) {
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

        val rows = mutableListOf<Map<String, String?>>()
        reader.read(file) { rows.add(it) }

        assertThat(rows).hasSize(1)
        assertThat(rows[0]["email"]).isEqualTo("a@example.com")
        assertThat(rows[0]["name"]).isNull()
        assertThat(rows[0]["amount"]).isEqualTo("100")
    }
}
