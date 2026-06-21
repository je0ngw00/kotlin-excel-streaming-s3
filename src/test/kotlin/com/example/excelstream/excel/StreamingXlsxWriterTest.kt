package com.example.excelstream.excel

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.FileOutputStream
import java.nio.file.Path

class StreamingXlsxWriterTest {

    private val writer = StreamingXlsxWriter()
    private val reader = StreamingXlsxReader()

    @Test
    fun `행 시퀀스를 써서 헤더명 맵으로 되읽으면 값이 보존된다`(@TempDir dir: Path) {
        val rows = sequenceOf<Array<Any?>>(
            arrayOf("a@example.com", "alice", 100L),
            arrayOf("b@example.com", "bob", 200L),
        )

        val file = dir.resolve("out.xlsx")
        FileOutputStream(file.toFile()).use { out -> writer.write(out, rows) }

        val read = mutableListOf<Map<String, String?>>()
        reader.read(file) { read.add(it) }

        assertThat(read).hasSize(2)
        assertThat(read[0]).containsEntry("email", "a@example.com")
            .containsEntry("name", "alice").containsEntry("amount", "100")
        assertThat(read[1]).containsEntry("email", "b@example.com")
            .containsEntry("name", "bob").containsEntry("amount", "200")
    }

    @Test
    fun `빈 시퀀스를 써도 헤더만 있는 유효한 xlsx가 만들어진다`(@TempDir dir: Path) {
        val file = dir.resolve("empty.xlsx")
        FileOutputStream(file.toFile()).use { out -> writer.write(out, emptySequence()) }

        val read = mutableListOf<Map<String, String?>>()
        reader.read(file) { read.add(it) }

        assertThat(read).isEmpty()
    }
}
