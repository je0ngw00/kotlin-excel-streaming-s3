package com.example.excelstream.measure

import com.example.excelstream.excel.StreamingXlsxReader
import com.example.excelstream.sample.generate
import com.example.excelstream.support.MemoryProbe
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * 메모리 측정 하니스 — XSSF(전체 적재) vs SAX/SXSSF(스트리밍)의 힙 사용량을 비교한다.
 *
 * "순수 파싱/생성"만 측정한다(DB 적재 없음). 업로드편은 H2 인메모리 DB가 적재 행을 힙에
 * 쌓아 측정을 오염시키므로, 여기서는 읽기/쓰기 자체의 메모리만 본다.
 *
 * 모드:
 *  - sax-read    : StreamingXlsxReader(SAX)로 한 행씩 읽기 → 평탄
 *  - xssf-read   : WorkbookFactory.create(전체 적재)로 읽기 → 우상향/OOM
 *  - sxssf-write : SXSSFWorkbook(윈도우+디스크 flush)로 쓰기 → 평탄
 *  - xssf-write  : XSSFWorkbook(전체 누적)으로 쓰기 → 우상향/OOM
 */
object Measure {

    fun run(mode: String, rows: Int, logEvery: Int): Long = when (mode) {
        "sax-read" -> saxRead(rows, logEvery)
        "xssf-read" -> xssfRead(rows, logEvery)
        "sxssf-write" -> sxssfWrite(rows, logEvery)
        "xssf-write" -> xssfWrite(rows, logEvery)
        else -> error("unknown mode: $mode (sax-read|xssf-read|sxssf-write|xssf-write)")
    }

    /** 읽기 모드용 입력 파일을 SXSSF로 한 번 만들어 tmp에 캐시한다(행수별). */
    private fun sampleFile(rows: Int): Path {
        val path = Path.of(System.getProperty("java.io.tmpdir"), "measure-sample-$rows.xlsx")
        if (!Files.exists(path)) generate(rows, path.toString())
        return path
    }

    private fun saxRead(rows: Int, logEvery: Int): Long {
        val file = sampleFile(rows)
        var n = 0L
        StreamingXlsxReader().read(file) { _ ->
            n++
            if (n % logEvery == 0L) MemoryProbe.log("sax-read", n)
        }
        return n
    }

    private fun xssfRead(rows: Int, logEvery: Int): Long {
        val file = sampleFile(rows)
        var n = 0L
        Files.newInputStream(file).use { ins ->
            // ❗ 전체를 메모리에 객체 모델로 적재 — 큰 N에서 여기서 OOM 난다.
            WorkbookFactory.create(ins).use { wb ->
                MemoryProbe.log("xssf-read", 0) // 적재 직후 스파이크
                for (row in wb.getSheetAt(0)) {
                    if (row.rowNum == 0) continue // 헤더 skip → 데이터 행만 카운트
                    n++
                    if (n % logEvery == 0L) MemoryProbe.log("xssf-read", n)
                }
            }
        }
        return n
    }

    private fun sxssfWrite(rows: Int, logEvery: Int): Long {
        SXSSFWorkbook(100).use { wb ->
            val sheet = wb.createSheet("data")
            for (i in 0 until rows) {
                val r = sheet.createRow(i)
                r.createCell(0).setCellValue("user$i@example.com")
                r.createCell(1).setCellValue("name$i")
                r.createCell(2).setCellValue((i * 100).toDouble())
                if ((i + 1) % logEvery == 0) MemoryProbe.log("sxssf-write", (i + 1).toLong())
            }
            wb.write(NullOutputStream)
        }
        return rows.toLong()
    }

    private fun xssfWrite(rows: Int, logEvery: Int): Long {
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("data")
            for (i in 0 until rows) {
                val r = sheet.createRow(i) // ❗ 행 객체가 계속 누적 — 큰 N에서 OOM
                r.createCell(0).setCellValue("user$i@example.com")
                r.createCell(1).setCellValue("name$i")
                r.createCell(2).setCellValue((i * 100).toDouble())
                if ((i + 1) % logEvery == 0) MemoryProbe.log("xssf-write", (i + 1).toLong())
            }
            wb.write(NullOutputStream)
        }
        return rows.toLong()
    }

    /** 측정만 할 뿐 결과 바이트는 버린다(디스크 I/O 노이즈 제거). */
    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}

/** 백그라운드에서 100ms마다 힙 사용량 최대치를 기록한다(스파이크 포착용). */
private class PeakSampler(private val intervalMs: Long = 100) : Thread() {
    private val peak = AtomicLong(0)
    @Volatile private var running = true

    init {
        isDaemon = true
        name = "peak-sampler"
    }

    override fun run() {
        val rt = Runtime.getRuntime()
        while (running) {
            peak.updateAndGet { maxOf(it, rt.totalMemory() - rt.freeMemory()) }
            try {
                sleep(intervalMs)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    fun stopAndPeakMB(): Long {
        running = false
        return peak.get() / (1024 * 1024)
    }
}

/**
 * 실행: `mode rows [logEvery]`
 * 예) ./gradlew measure -Pxmx=256m --args="xssf-read 200000 10000"
 *
 * 끝에 `[RESULT] mode=.. rowsTarget=.. processed=.. status=OK|OOM|ERROR:.. peakHeapMB=.. elapsedMs=..` 한 줄을 찍는다.
 * OOM 도 데이터 포인트라, 잡아서 기록한 뒤 정상 종료한다(스윕이 멈추지 않도록).
 */
fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "sax-read"
    val rows = args.getOrNull(1)?.toInt() ?: 100_000
    val logEvery = args.getOrNull(2)?.toInt() ?: 10_000

    val sampler = PeakSampler().apply { start() }
    val startMs = System.currentTimeMillis()
    var processed = 0L
    var status = "OK"
    try {
        processed = Measure.run(mode, rows, logEvery)
    } catch (e: OutOfMemoryError) {
        status = "OOM"
    } catch (e: Throwable) {
        status = "ERROR:${e.javaClass.simpleName}"
    }
    val peakMB = sampler.stopAndPeakMB()
    val elapsedMs = System.currentTimeMillis() - startMs
    println(
        "[RESULT] mode=$mode rowsTarget=$rows processed=$processed " +
            "status=$status peakHeapMB=$peakMB elapsedMs=$elapsedMs",
    )
}
