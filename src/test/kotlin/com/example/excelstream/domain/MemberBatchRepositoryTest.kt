package com.example.excelstream.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.jdbc.core.JdbcTemplate

@JdbcTest
class MemberBatchRepositoryTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
) {
    private lateinit var repo: MemberBatchRepository

    @BeforeEach
    fun setUp() {
        jdbc.execute(
            "CREATE TABLE members (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "email VARCHAR(255), name VARCHAR(255), amount BIGINT)",
        )
        repo = MemberBatchRepository(jdbc)
    }

    @Test
    fun `여러 건을 batch로 insert 한다`() {
        val rows = listOf(
            arrayOf<Any?>("a@example.com", "alice", 100L),
            arrayOf<Any?>("b@example.com", "bob", 200L),
        )

        repo.insertBatch(rows)

        val count = jdbc.queryForObject("SELECT COUNT(*) FROM members", Int::class.java)
        assertThat(count).isEqualTo(2)
        val firstEmail = jdbc.queryForObject(
            "SELECT email FROM members ORDER BY id LIMIT 1", String::class.java,
        )
        assertThat(firstEmail).isEqualTo("a@example.com")
    }
}
