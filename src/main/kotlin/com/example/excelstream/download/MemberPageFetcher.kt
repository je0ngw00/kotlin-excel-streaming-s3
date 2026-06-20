package com.example.excelstream.download

import com.example.excelstream.excel.StreamingXlsxWriter.PageFetcher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class MemberPageFetcher(private val jdbc: JdbcTemplate) : PageFetcher {

    override fun fetch(pageNumber: Int, pageSize: Int): List<Array<Any?>> {
        return jdbc.query(
            "SELECT email, name, amount FROM members ORDER BY id LIMIT ? OFFSET ?",
            { rs, _ -> arrayOf<Any?>(rs.getString(1), rs.getString(2), rs.getLong(3)) },
            pageSize, pageNumber.toLong() * pageSize,
        )
    }
}
