package com.example.excelstream.download

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExportFormatTest {

    @Test
    fun `확장자 문자열로 포맷을 찾는다 (대소문자 무시)`() {
        assertThat(ExportFormat.from("xlsx")).isEqualTo(ExportFormat.XLSX)
        assertThat(ExportFormat.from("CSV")).isEqualTo(ExportFormat.CSV)
    }

    @Test
    fun `포맷별 확장자와 콘텐트타입을 노출한다`() {
        assertThat(ExportFormat.CSV.ext).isEqualTo("csv")
        assertThat(ExportFormat.CSV.contentType).isEqualTo("text/csv")
        assertThat(ExportFormat.XLSX.ext).isEqualTo("xlsx")
    }

    @Test
    fun `지원하지 않는 포맷은 예외를 던진다`() {
        assertThatThrownBy { ExportFormat.from("pdf") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
