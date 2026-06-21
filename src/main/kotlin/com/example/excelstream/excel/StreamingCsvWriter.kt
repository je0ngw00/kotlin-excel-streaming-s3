package com.example.excelstream.excel

import org.springframework.stereotype.Component
import java.io.OutputStream

/**
 * 행 시퀀스를 CSV로 한 줄씩 흘려 쓴다. append-only 라 임시파일/랜덤액세스가 필요 없다.
 * 전달받은 OutputStream 은 닫지 않는다(호출자 소유) — flush 만 한다.
 * 각 행은 [email, name, amount] 순서.
 */
@Component
class StreamingCsvWriter {

    fun write(out: OutputStream, rows: Sequence<Array<Any?>>) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.append(header).append('\n')
        for (row in rows) {
            w.append(field(row.getOrNull(0)))
                .append(',').append(field(row.getOrNull(1)))
                .append(',').append(field(row.getOrNull(2)))
                .append('\n')
        }
        w.flush() // 닫지 않는다 — S3MultipartSink/서블릿 컨테이너가 스트림을 소유
    }

    /** RFC4180: 쉼표/따옴표/개행 포함 시 따옴표로 감싸고 내부 따옴표는 중복. */
    private fun field(value: Any?): String {
        val s = value?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }
    }

    companion object {
        private val header = MEMBER_EXPORT_HEADER.joinToString(",")
    }
}
