package com.example.excelstream.measure

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MeasureTest {

    @Test
    fun `sax-read 는 헤더를 빼고 데이터 행 수를 센다`() {
        assertThat(Measure.run("sax-read", 100, 1000)).isEqualTo(100L)
    }

    @Test
    fun `xssf-read 는 헤더를 빼고 데이터 행 수를 센다`() {
        assertThat(Measure.run("xssf-read", 100, 1000)).isEqualTo(100L)
    }

    @Test
    fun `sxssf-write 는 쓴 행 수를 반환한다`() {
        assertThat(Measure.run("sxssf-write", 100, 1000)).isEqualTo(100L)
    }

    @Test
    fun `xssf-write 는 쓴 행 수를 반환한다`() {
        assertThat(Measure.run("xssf-write", 100, 1000)).isEqualTo(100L)
    }

    @Test
    fun `알 수 없는 모드는 IllegalStateException`() {
        assertThatThrownBy { Measure.run("nope", 1, 1) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `logEvery 가 0 이어도 division by zero 없이 동작한다`() {
        assertThat(Measure.run("sxssf-write", 100, 0)).isEqualTo(100L)
    }

    @Test
    fun `progress 는 처리한 행 수를 반영한다`() {
        Measure.run("sxssf-write", 100, 1000)
        assertThat(Measure.progress.get()).isEqualTo(100L)
    }

    @Test
    fun `prepareSample 로 미리 만든 파일을 읽기 모드가 그대로 읽는다`() {
        Measure.prepareSample(50)
        assertThat(Measure.run("sax-read", 50, 1000)).isEqualTo(50L)
    }
}
