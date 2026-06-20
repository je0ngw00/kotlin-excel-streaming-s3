package com.example.excelstream.download

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class MemberPageFetcher(private val jdbc: JdbcTemplate) {

    /**
     * 키셋(seek) 페이징으로 전체 멤버를 지연 시퀀스로 공급한다.
     * `WHERE id > ?` + 인덱스 정렬이라 페이지마다 O(pageSize) 범위 스캔으로 끝나,
     * OFFSET 방식의 "앞 행 전부 스캔 후 폐기"(페이지가 뒤로 갈수록 O(n)) 비용을 피한다.
     * 각 행은 [email, name, amount] 순서다.
     */
    fun rows(pageSize: Int = 1000): Sequence<Array<Any?>> = sequence {
        var afterId = 0L
        while (true) {
            val page = jdbc.query(
                "SELECT id, email, name, amount FROM members WHERE id > ? ORDER BY id LIMIT ?",
                { rs, _ ->
                    rs.getLong(1) to arrayOf<Any?>(rs.getString(2), rs.getString(3), rs.getLong(4))
                },
                afterId, pageSize,
            )
            if (page.isEmpty()) break
            for ((id, row) in page) {
                afterId = id
                yield(row)
            }
        }
    }
}
