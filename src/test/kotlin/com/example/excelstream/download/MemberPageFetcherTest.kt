package com.example.excelstream.download

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.jdbc.core.JdbcTemplate

@JdbcTest
class MemberPageFetcherTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
) {
    private lateinit var fetcher: MemberPageFetcher

    @BeforeEach
    fun setUp() {
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS members (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "email VARCHAR(255), name VARCHAR(255), amount BIGINT)",
        )
        repeat(5) { i ->
            jdbc.update(
                "INSERT INTO members (email, name, amount) VALUES (?, ?, ?)",
                "user$i@example.com", "name$i", (i * 100).toLong(),
            )
        }
        fetcher = MemberPageFetcher(jdbc)
    }

    @Test
    fun `키셋 페이징으로 모든 행을 id 순서대로 공급한다`() {
        // pageSize를 2로 잡아 여러 페이지에 걸쳐 키셋이 이어지는지 확인
        val emails = fetcher.rows(pageSize = 2).map { it[0] as String? }.toList()

        assertThat(emails).containsExactly(
            "user0@example.com",
            "user1@example.com",
            "user2@example.com",
            "user3@example.com",
            "user4@example.com",
        )
    }
}
