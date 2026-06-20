# 1차 — 업로드편: 큰 엑셀을 S3에 흘려보내고, 한 행씩 읽어 DB에 적재하기

> 목표: 10만 행짜리 엑셀을 **메모리에 통째로 올리지 않고** 처리한다.
> 흐름: `업로드 → S3 스트리밍 저장 → S3에서 받아 SAX로 한 행씩 읽기 → 1,000건씩 DB INSERT`
> 코드는 **Kotlin + Spring Boot 3** 기준이다.

---

## 0. 글의 도입부 (블로그용 서술 가이드)

이렇게 시작하면 자연스럽다:

> "관리자 시스템을 만들다 보면 엑셀 업로드가 흔하다. 적은 데이터는 `WorkbookFactory.create()` 한 줄이면 끝나지만, 행이 수만을 넘어가면 `OutOfMemoryError`가 난다. 왜일까? POI의 기본 방식(XSSF)은 **엑셀 전체를 메모리에 객체 모델로 올리기** 때문이다. Node 진영에는 이걸 스트리밍으로 푸는 글이 많은데, Java/Kotlin 한국어 자료는 의외로 흩어져 있다. 이 글은 **POI SAX + S3 + LocalStack**으로 끝까지 재현한다."

그리고 "왜 터지는가"를 먼저 보여준다 (아래 1번).

---

## 1. (도입 실험) XSSF는 왜 터지는가 — 안티패턴 먼저 보여주기

```kotlin
// ❌ 안티패턴: 10만 행에서 heap 폭주
WorkbookFactory.create(inputStream).use { wb ->   // 전체를 메모리에 객체 모델로
    val sheet = wb.getSheetAt(0)
    for (row in sheet) { /* ... */ }
}
```

글에서는 `-Xmx512m`로 띄운 뒤 10만 행 파일을 이 방식으로 읽어 **heap 그래프가 치솟다 GC 폭주/OOM** 나는 로그를 캡처한다. (build.gradle의 bootRun jvmArgs가 이미 `-Xmx512m`)

---

## 2. 테스트용 대용량 엑셀 생성기

먼저 실험에 쓸 10만 행 xlsx를 만든다. **생성도 SXSSF로** 해야 생성 단계에서 안 터진다(2차편 복선).

`src/main/kotlin/com/example/excelstream/sample/SampleExcelGenerator.kt`:

```kotlin
package com.example.excelstream.sample

import org.apache.poi.xssf.streaming.SXSSFWorkbook
import java.io.FileOutputStream

/** 테스트용 대용량 xlsx 생성. */
fun generate(rows: Int, path: String) {
    // 윈도우 100행만 메모리 유지, 나머지는 디스크 임시파일로 flush.
    // use {} 가 끝에서 close() 를 호출하고, close() 가 디스크 임시파일까지 정리한다.
    // (POI 5.x 의 SXSSFWorkbook.dispose() 는 deprecated — close()/use 로 대체)
    SXSSFWorkbook(100).use { wb ->
        FileOutputStream(path).use { out ->
            val sheet = wb.createSheet("data")

            val header = sheet.createRow(0)
            header.createCell(0).setCellValue("id")
            header.createCell(1).setCellValue("email")
            header.createCell(2).setCellValue("name")
            header.createCell(3).setCellValue("amount")

            for (i in 1..rows) {
                val r = sheet.createRow(i)
                // POI setCellValue 는 double 오버로드뿐이라 숫자는 toDouble() 필요
                r.createCell(0).setCellValue(i.toDouble())
                r.createCell(1).setCellValue("user$i@example.com")
                r.createCell(2).setCellValue("이름$i")
                r.createCell(3).setCellValue((i * 100).toDouble())
            }
            wb.write(out)
        }
    }
    println("generated $rows rows -> $path")
}

fun main(args: Array<String>) {
    val rows = args.getOrNull(0)?.toInt() ?: 100_000
    val path = args.getOrNull(1) ?: "/tmp/sample-$rows.xlsx"
    generate(rows, path)
}
```

생성 (build.gradle에 등록한 `generateSample` 태스크 사용):
```bash
./gradlew generateSample --args="100000 /tmp/sample-100k.xlsx"
# 또는 IDE에서 main 실행
```
> 참고: 표준 `application` 플러그인 대신 `JavaExec` 태스크(`generateSample`) 하나만 build.gradle에 등록해 두면 `bootRun`과 충돌 없이 보조 main을 돌릴 수 있다. Kotlin의 top-level `main`은 컴파일 시 `SampleExcelGeneratorKt` 클래스가 되므로 태스크의 `mainClass`도 그 이름을 쓴다.

---

## 3. DB 엔티티 (적재 대상)

`src/main/kotlin/com/example/excelstream/domain/Member.kt`:

```kotlin
package com.example.excelstream.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "members")
class Member(
    @Column var email: String,
    @Column var name: String,
    @Column var amount: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
```
> `kotlin-jpa`(no-arg) 플러그인이 JPA용 기본 생성자를 만들어 주므로 엔티티를 일반 `class`로 둬도 된다(build.gradle에 추가해 둠).

INSERT는 JPA 대신 **JDBC batch**로 한다(대량엔 이게 빠르고 메모리 안정적):

`src/main/kotlin/com/example/excelstream/domain/MemberBatchRepository.kt`:

```kotlin
package com.example.excelstream.domain

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class MemberBatchRepository(private val jdbc: JdbcTemplate) {

    /** 1,000건 단위로 호출되는 것을 전제로 한 batch insert */
    fun insertBatch(rows: List<Array<Any?>>) {
        jdbc.batchUpdate(
            "INSERT INTO members (email, name, amount) VALUES (?, ?, ?)",
            rows,
        )
    }
}
```

---

## 4. ★ 핵심: SAX 스트리밍 파서

POI의 `XSSFReader` + `SAXParser` 조합. `<row>`를 만날 때마다 콜백으로 받아 **메모리엔 현재 행만** 둔다.

`src/main/kotlin/com/example/excelstream/excel/StreamingXlsxReader.kt`:

```kotlin
package com.example.excelstream.excel

import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.ss.util.CellReference
import org.apache.poi.util.XMLHelper
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable
import org.apache.poi.xssf.eventusermodel.XSSFReader
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler
import org.apache.poi.xssf.usermodel.XSSFComment
import org.xml.sax.InputSource
import java.nio.file.Path

/**
 * 대용량 xlsx를 SAX로 한 행씩 스트리밍 읽기.
 * onRow 콜백이 한 행(List<String?>)을 받는다. 어떤 시점에도 전체를 메모리에 올리지 않는다.
 */
class StreamingXlsxReader {

    /** 파일 경로로부터 읽기 (S3에서 받은 tmp 파일을 넘긴다) */
    fun read(xlsxPath: Path, onRow: (List<String?>) -> Unit) {
        OPCPackage.open(xlsxPath.toFile()).use { pkg ->
            readPackage(pkg, onRow)
        }
    }

    private fun readPackage(pkg: OPCPackage, onRow: (List<String?>) -> Unit) {
        val strings = ReadOnlySharedStringsTable(pkg)
        val reader = XSSFReader(pkg)
        val styles = reader.stylesTable

        val sheets = reader.sheetsData as XSSFReader.SheetIterator
        if (sheets.hasNext()) {                 // 첫 시트만 처리 (필요 시 루프)
            sheets.next().use { sheetStream ->
                val handler = RowHandler(onRow)
                // ✅ deprecated 된 XMLReaderFactory.createXMLReader() 대신 POI 권장 헬퍼
                val xmlReader = XMLHelper.newXMLReader()
                xmlReader.contentHandler =
                    XSSFSheetXMLHandler(styles, strings, handler, false)
                xmlReader.parse(InputSource(sheetStream))
            }
        }
    }

    /**
     * 행 핸들러: 빈 셀이 있어도 열이 어긋나지 않게 cellReference로 컬럼 인덱스를 잡는다.
     * (POI SAX는 비어 있는 셀에는 cell() 콜백을 호출하지 않으므로, 단순 add 만 하면 컬럼이 밀린다.)
     */
    private class RowHandler(
        private val onRow: (List<String?>) -> Unit,
    ) : SheetContentsHandler {

        private val current = ArrayList<String?>()

        override fun startRow(rowNum: Int) {
            current.clear()
        }

        override fun cell(cellReference: String?, formattedValue: String?, comment: XSSFComment?) {
            // cellReference 예: "C5" → 열 인덱스 2. 건너뛴 빈 셀 자리는 null로 채운다.
            val colIdx = cellReference?.let { CellReference(it).col.toInt() } ?: current.size
            while (current.size < colIdx) current.add(null)
            current.add(formattedValue)
        }

        override fun endRow(rowNum: Int) {
            if (rowNum == 0) return            // 헤더 skip (필요 시 검증에 사용)
            onRow(current.toList())            // 스냅샷(방어적 복사) 전달 → 다음 행에서 덮어써도 안전
        }

        override fun headerFooter(text: String?, isHeader: Boolean, tagName: String?) {}
    }
}
```

> **여기가 글의 하이라이트.** XSSF가 전체를 들고 있는 것과 달리, `RowHandler.current`는 항상 한 행짜리다. `endRow`에서 스냅샷을 넘기고 리스트를 비우므로 다음 행으로 덮어써도 GC 대상이 된다.
> **정직한 함정 하나(글에 넣기):** POI SAX는 *빈 셀을 통째로 건너뛴다.* `cellReference`(A1, C5…)를 무시하고 값만 차곡차곡 담으면 중간에 빈 칸이 있는 행에서 열이 한 칸씩 밀린다. 위 코드는 `CellReference`로 열 위치를 복원해 이 문제를 막는다.

---

## 5. S3 스트리밍 업로드 + 다운로드

`src/main/kotlin/com/example/excelstream/excel/S3ExcelStorage.kt`:

```kotlin
package com.example.excelstream.excel

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

@Component
class S3ExcelStorage(private val s3: S3Client) {

    @Value("\${app.s3.bucket}")
    private lateinit var bucket: String

    /**
     * 업로드 스트림을 그대로 S3에 저장.
     * 주의: putObject(RequestBody.fromInputStream)는 contentLength를 요구한다.
     *   - multipart 파일은 size를 알 수 있어 OK.
     *   - 진짜 length-unknown 스트림이면 Transfer Manager 멀티파트(2차편)에서 다룬다.
     */
    fun put(key: String, input: InputStream, contentLength: Long) {
        s3.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).build(),
            RequestBody.fromInputStream(input, contentLength),
        )
    }

    /** S3 객체를 로컬 tmp 파일로 내려받는다 (SAX 읽기는 ZIP 랜덤액세스가 필요해 파일 경유) */
    fun downloadToTemp(key: String): Path {
        val tmp = Files.createTempFile("xlsx-", ".xlsx")
        s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), tmp)
        return tmp
    }
}
```

> **정직한 트레이드오프 (글에 꼭 넣기)**: "왜 S3에서 받아 곧장 SAX로 안 읽고 tmp 파일을 거치나?" → xlsx는 ZIP이고 POI의 `OPCPackage.open`은 ZIP 중앙 디렉터리를 위해 **랜덤 액세스**가 필요하다. 순수 스트림(`OPCPackage.open(InputStream)`)도 되지만 내부적으로 전체를 메모리/임시파일에 버퍼링한다. 그래서 "S3 → tmp 파일 → SAX"가 가장 안정적이다. (Node의 unzipper도 결국 같은 제약)

---

## 6. 업로드 처리 서비스 (조립)

`src/main/kotlin/com/example/excelstream/upload/UploadService.kt`:

```kotlin
package com.example.excelstream.upload

import com.example.excelstream.domain.MemberBatchRepository
import com.example.excelstream.excel.S3ExcelStorage
import com.example.excelstream.excel.StreamingXlsxReader
import com.example.excelstream.support.MemoryProbe
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.nio.file.Files
import java.util.UUID

@Service
class UploadService(
    private val storage: S3ExcelStorage,
    private val repo: MemberBatchRepository,
) {
    private val reader = StreamingXlsxReader()

    // 업로드 1건을 하나의 트랜잭션으로: 중간 배치가 flush 됐어도 실패 시 전체 롤백.
    @Transactional
    fun handle(file: MultipartFile): Long {
        val key = "uploads/${UUID.randomUUID()}.xlsx"

        // 1) 업로드 스트림을 S3로 (서버 메모리엔 multipart 임계치 버퍼만)
        storage.put(key, file.inputStream, file.size)

        // 2) S3 → tmp 파일
        val tmp = storage.downloadToTemp(key)
        try {
            // 3) SAX로 한 행씩 읽으며 1,000건마다 flush
            val buffer = ArrayList<Array<Any?>>(FLUSH_SIZE)
            var count = 0L
            reader.read(tmp) { cells ->
                // cells: [id, email, name, amount] — 빈 셀 대비해 getOrNull 사용
                val email = cells.getOrNull(1)?.trim()
                val name = cells.getOrNull(2)?.trim()
                val amount = cells.getOrNull(3)
                // 완전히 빈 행(셀이 없거나 모두 공백)은 건너뛴다.
                if (email.isNullOrEmpty() && name.isNullOrEmpty() && amount.isNullOrBlank()) {
                    return@read
                }
                buffer.add(arrayOf(email, name, parseAmount(amount)))
                if (buffer.size >= FLUSH_SIZE) {
                    repo.insertBatch(buffer)
                    count += buffer.size
                    buffer.clear()
                    MemoryProbe.log("upload-insert", count)  // 메모리 관찰 로그
                }
            }
            if (buffer.isNotEmpty()) {
                repo.insertBatch(buffer)
                count += buffer.size
            }
            MemoryProbe.log("upload-done", count)
            return count
        } finally {
            Files.deleteIfExists(tmp)  // tmp 정리
        }
    }

    /**
     * POI DataFormatter 가 돌려주는 표시 문자열을 Long 으로 변환한다.
     * "1,000"(천단위), "100.0"(소수 서식), "1E+5"(지수 서식)까지 견고하게 처리한다.
     */
    private fun parseAmount(raw: String?): Long? {
        val s = raw?.trim()?.replace(",", "")
        if (s.isNullOrEmpty()) return null
        return BigDecimal(s).toLong()
    }

    companion object {
        private const val FLUSH_SIZE = 1000   // 1,000건 모이면 DB로
    }
}
```

> 핵심: `buffer`는 **최대 1,000건**만 유지된다. 10만 건이어도 메모리엔 항상 1,000건 + 현재 SAX 행뿐. (Node 글의 backpressure를 여기선 "버퍼+flush"로 단순화 — 동기 JDBC라 자연스럽게 흐름 제어됨)

---

## 7. 컨트롤러

`src/main/kotlin/com/example/excelstream/upload/UploadController.kt`:

```kotlin
package com.example.excelstream.upload

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
class UploadController(private val service: UploadService) {

    @PostMapping("/upload")
    fun upload(@RequestParam("file") file: MultipartFile): String {
        val inserted = service.handle(file)
        return "inserted=$inserted"
    }
}
```

---

## 8. 테스트 절차 (집에서 실행)

```bash
# 0. LocalStack 기동 (버킷 자동 생성)
docker compose up -d
docker logs excelstream-localstack | grep "bucket"   # ready 확인

# 1. 앱 실행
./gradlew bootRun

# 2. 샘플 10만 행 엑셀 생성
./gradlew generateSample --args="100000 /tmp/sample-100k.xlsx"
#   (또는 IDE에서 SampleExcelGenerator.kt 의 main 실행)

# 3. 업로드
curl -F "file=@/tmp/sample-100k.xlsx" http://localhost:8080/upload
# → inserted=100000

# 4. 메모리 로그 확인 (앱 콘솔)
# [MEM] phase=upload-insert rows=10000 heapUsedMB=70
# [MEM] phase=upload-insert rows=50000 heapUsedMB=72
# [MEM] phase=upload-done   rows=100000 heapUsedMB=71
#   → rows가 늘어도 heapUsedMB가 평탄하면 성공!

# 5. (대조군) StreamingXlsxReader 대신 XSSF(WorkbookFactory)로 바꿔 같은 파일 → heap 폭주/OOM 캡처
```

### S3에 실제로 올라갔는지 확인
```bash
awslocal s3 ls s3://excel-bucket/uploads/
# 또는
aws --endpoint-url=http://localhost:4566 s3 ls s3://excel-bucket/uploads/
```

---

## 9. 글 마무리 포인트

- "rows는 100배 늘었는데 heapUsedMB는 평탄하다" — 이 한 장의 그래프가 글의 결론.
- 다음 편 예고: "그럼 **내보내기(다운로드)**가 클 때는? 이번엔 DB를 조금씩 읽어 SXSSF로 만들고, 서버 디스크조차 아끼려 S3 멀티파트로 직접 흘려보낸다." → 2차편으로.

## 트러블슈팅
- `Connection refused 4566`: LocalStack 미기동. `docker compose up -d` 확인.
- `XMLReaderFactory deprecated`: POI 5.x에서는 위 코드처럼 `org.apache.poi.util.XMLHelper.newXMLReader()`를 쓴다. (구버전 예제의 `org.apache.poi.ooxml.util.SAXHelper.newXMLReader()` 도 내부적으로 `XMLHelper`에 위임하지만 그 자체가 6.0.0에서 제거 예정이라 권장하지 않는다.)
- 업로드 413(Payload Too Large): `application.yml`의 multipart 한도/`max-swallow-size` 확인.
- `aws-crt` 관련 에러: `s3AsyncClient`는 2차편에서 본격 사용. 1차만 할 거면 S3Config에서 async/transferManager 빈을 주석 처리해도 된다.
- 컬럼 매핑이 어긋난다: 시트에 빈 셀이 섞여 있는데 `cellReference`를 무시하고 읽고 있지 않은지 확인(§4 RowHandler 참고).
