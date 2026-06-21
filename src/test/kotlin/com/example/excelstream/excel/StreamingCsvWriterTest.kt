package com.example.excelstream.excel

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class StreamingCsvWriterTest {

    private val writer = StreamingCsvWriter()

    private fun csv(rows: Sequence<Array<Any?>>): String {
        val out = ByteArrayOutputStream()
        writer.write(out, rows)
        return out.toString(Charsets.UTF_8)
    }

    @Test
    fun `빈 시퀀스면 헤더 한 줄만 쓴다`() {
        assertThat(csv(emptySequence())).isEqualTo("email,name,amount\n")
    }

    @Test
    fun `일반 행을 헤더 아래 한 줄씩 쓴다`() {
        val rows = sequenceOf<Array<Any?>>(
            arrayOf("a@example.com", "alice", 100L),
            arrayOf("b@example.com", "bob", 200L),
        )
        assertThat(csv(rows)).isEqualTo(
            "email,name,amount\n" +
                "a@example.com,alice,100\n" +
                "b@example.com,bob,200\n",
        )
    }

    @Test
    fun `쉼표 따옴표 개행이 든 값은 따옴표로 감싸고 내부 따옴표는 중복한다`() {
        val rows = sequenceOf<Array<Any?>>(
            arrayOf("a@example.com", "Doe, John", 1L),
            arrayOf("b@example.com", "She said \"hi\"", 2L),
            arrayOf("c@example.com", "line1\nline2", 3L),
        )
        assertThat(csv(rows)).isEqualTo(
            "email,name,amount\n" +
                "a@example.com,\"Doe, John\",1\n" +
                "b@example.com,\"She said \"\"hi\"\"\",2\n" +
                "c@example.com,\"line1\nline2\",3\n",
        )
    }

    @Test
    fun `널 값은 빈 문자열로 쓴다`() {
        val rows = sequenceOf<Array<Any?>>(arrayOf(null, null, null))
        assertThat(csv(rows)).isEqualTo("email,name,amount\n,,\n")
    }
}
