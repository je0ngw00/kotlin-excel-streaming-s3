package com.example.excelstream.domain

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class MemberBatchRepository(private val jdbc: JdbcTemplate) {
    fun insertBatch(rows: List<Array<Any?>>) {
        jdbc.batchUpdate(
            "INSERT INTO members (email, name, amount) VALUES (?, ?, ?)",
            rows,
        )
    }
}
