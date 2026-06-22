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
import java.nio.file.StandardCopyOption
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

    /** 현재까지 처리/생성한 행 수. OOM/에러로 중단돼도 "어디까지 갔는지"를 main이 읽어 기록한다. */
    val progress = AtomicLong(0)

    fun run(mode: String, rows: Int, logEvery: Int): Long {
        progress.set(0)
        val every = logEvery.coerceAtLeast(1) // 0 방어(나머지 연산 division by zero)
        return when (mode) {
            "sax-read" -> saxRead(rows, every)
            "xssf-read" -> xssfRead(rows, every)
            "sxssf-write" -> sxssfWrite(rows, every)
            "xssf-write" -> xssfWrite(rows, every)
            else -> error("unknown mode: $mode (sax-read|xssf-read|sxssf-write|xssf-write)")
        }
    }

    /**
     * 읽기 모드 입력 파일을 측정 시작 전에 미리 준비한다.
     * 측정 구간(PeakSampler 가동) 밖에서 호출해야 생성 스파이크가 read 모드 peak에 섞이지 않는다.
     */
    fun prepareSample(rows: Int) {
        sampleFile(rows)
    }

    /**
     * 읽기 모드용 입력 파일을 SXSSF로 한 번 만들어 tmp에 캐시한다(행수별).
     * 부분 생성 파일이 캐시에 노출되지 않도록 임시 경로에 만든 뒤 원자적으로 이동한다 —
     * 생성 도중 죽으면 최종 경로에는 파일이 없어 다음 실행이 다시 생성한다(손상 파일 재사용 방지).
     */
    private fun sampleFile(rows: Int): Path {
        val path = Path.of(System.getProperty("java.io.tmpdir"), "measure-sample-$rows.xlsx")
        if (!Files.exists(path)) {
            val partial = Files.createTempFile("measure-sample-$rows-", ".xlsx.partial")
            generate(rows, partial.toString())
            Files.move(partial, path, StandardCopyOption.ATOMIC_MOVE)
        }
        return path
    }

    private fun saxRead(rows: Int, logEvery: Int): Long {
        val file = sampleFile(rows)
        var n = 0L
        StreamingXlsxReader().read(file) { _ ->
            n++
            progress.set(n)
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
                    progress.set(n)
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
                fillRow(sheet.createRow(i), i)
                progress.set((i + 1).toLong())
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
                fillRow(sheet.createRow(i), i) // ❗ 행 객체가 계속 누적 — 큰 N에서 OOM
                progress.set((i + 1).toLong())
                if ((i + 1) % logEvery == 0) MemoryProbe.log("xssf-write", (i + 1).toLong())
            }
            wb.write(NullOutputStream)
        }
        return rows.toLong()
    }

    /** 두 쓰기 모드가 동일한 행 데이터를 채운다(워크북 타입만 다르다). */
    private fun fillRow(row: org.apache.poi.ss.usermodel.Row, i: Int) {
        row.createCell(0).setCellValue("user$i@example.com")
        row.createCell(1).setCellValue("name$i")
        row.createCell(2).setCellValue((i * 100).toDouble())
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
 * processed 는 OK면 전체, OOM/에러면 중단 직전 도달 행수다.
 * OOM 도 데이터 포인트라, 잡아서 기록한 뒤 정상 종료한다(스윕이 멈추지 않도록).
 */
fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "sax-read"
    val rows = args.getOrNull(1)?.toInt() ?: 100_000
    val logEvery = (args.getOrNull(2)?.toInt() ?: 10_000).coerceAtLeast(1)

    // 읽기 모드 입력 파일은 측정 구간 밖에서 미리 준비한다(생성 스파이크가 read peak에 섞이지 않게).
    if (mode == "sax-read" || mode == "xssf-read") {
        Measure.prepareSample(rows)
    }

    val sampler = PeakSampler().apply { start() }
    val startMs = System.currentTimeMillis()
    var processed = 0L
    var status = "OK"
    try {
        processed = Measure.run(mode, rows, logEvery)
    } catch (e: OutOfMemoryError) {
        status = "OOM"
        processed = Measure.progress.get() // OOM 직전 도달 행수
    } catch (e: Throwable) {
        status = "ERROR:${e.javaClass.simpleName}"
        processed = Measure.progress.get()
    }
    val peakMB = sampler.stopAndPeakMB()
    val elapsedMs = System.currentTimeMillis() - startMs
    println(
        "[RESULT] mode=$mode rowsTarget=$rows processed=$processed " +
            "status=$status peakHeapMB=$peakMB elapsedMs=$elapsedMs",
    )
}
