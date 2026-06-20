# 2차 — 다운로드편: DB를 조금씩 읽어 큰 엑셀을 만들고, S3로 흘려보내기

> 목표: 10만 행짜리 엑셀을 **만드는 과정에서도** 메모리/디스크를 안 터뜨린다.
> 흐름: `DB 커서/페이징으로 조금씩 읽기 → SXSSF로 조금씩 쓰기 → S3 멀티파트로 직접 업로드 → presigned URL`
> 코드는 **Kotlin + Spring Boot 3** 기준이다.

---

## 0. 도입부 (블로그용 서술 가이드)

1차편이 "받은 파일을 조금씩 읽기"였다면, 2차는 반대 방향이다:

> "이번엔 내보내기다. 흔히 `XSSFWorkbook`에 행을 다 추가하고 `write()` 하는데, 10만 행이면 그 행들이 전부 메모리에 쌓여 OOM이 난다. 핵심 질문 세 가지로 나눠 풀어보자.
> ① **메모리**가 부족하면? → SXSSF (행을 디스크 임시파일로 flush)
> ② **서버 디스크**조차 부족하면? → 만들면서 바로 S3로 흘려보내기 (멀티파트)
> ③ 생성이 **오래 걸려** HTTP가 타임아웃되면? → 비동기 + presigned URL"

이 세 축을 분리해서 설명하는 게 글의 뼈대다.

---

## 1. (도입 실험) XSSF 내보내기는 왜 터지는가

```kotlin
// ❌ 안티패턴: 10만 행이 전부 메모리에 쌓임
val wb = XSSFWorkbook()
val sheet = wb.createSheet()
for (i in 0 until 100_000) {
    val r = sheet.createRow(i)   // 행 객체가 계속 메모리에 누적
    // ... 셀 채우기 ...
}
wb.write(out)  // 이 시점까지 10만 행 전부 heap에
```

`-Xmx512m`로 띄워 10만 행 내보내기 → heap 폭주 캡처. (1차편과 같은 대조군 패턴)

---

## 2. ★ 핵심 ①: SXSSF로 "메모리 대신 디스크에 두고" 만들기

`SXSSFWorkbook(N)` = 최근 N행만 메모리, 나머지는 OS 임시디렉터리로 자동 flush.

`src/main/kotlin/com/example/excelstream/excel/StreamingXlsxWriter.kt`:

```kotlin
package com.example.excelstream.excel

import com.example.excelstream.support.MemoryProbe
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import java.io.OutputStream

/**
 * 행 시퀀스를 공급받아 SXSSF로 큰 엑셀을 쓴다.
 * 시퀀스는 지연 평가되므로 호출자가 DB 키셋 페이징으로 조금씩 공급해도 전체를 메모리에 올리지 않는다.
 * out 으로 응답 스트림이든 S3 멀티파트 스트림이든 넘길 수 있다. 각 행은 [email, name, amount].
 */
class StreamingXlsxWriter {

    fun write(out: OutputStream, rows: Sequence<Array<Any?>>) {
        // 윈도우 100행만 메모리, 나머지 디스크 flush.
        // use {} 의 close() 가 디스크 임시파일까지 정리한다 (POI 5.x 의 dispose() 는 deprecated).
        SXSSFWorkbook(100).use { wb ->
            val sheet = wb.createSheet("data")
            var rownum = 0

            // 헤더
            val header = sheet.createRow(rownum++)
            header.createCell(0).setCellValue("email")
            header.createCell(1).setCellValue("name")
            header.createCell(2).setCellValue("amount")

            for (data in rows) {
                val r = sheet.createRow(rownum++)
                r.createCell(0).setCellValue(data[0] as String?)
                r.createCell(1).setCellValue(data[1] as String?)
                r.createCell(2).setCellValue((data[2] as Number).toDouble())
                if (rownum % LOG_EVERY == 0) {
                    MemoryProbe.log("download-write", rownum.toLong())  // 평탄해야 성공
                }
            }
            wb.write(out)
            // 임시파일 정리는 use {} 의 close() 가 담당 (명시적 dispose() 는 deprecated)
        }
    }

    companion object {
        private const val LOG_EVERY = 1000
    }
}
```

> 메모리는 항상 윈도우 100행 + 키셋 한 페이지뿐. 10만 행이어도 평탄.
> **여기까지가 "메모리 문제"의 완전한 해결.** S3는 아직 안 나왔다.

---

## 3. DB를 조금씩 읽기 (키셋 페이징)

`src/main/kotlin/com/example/excelstream/download/MemberPageFetcher.kt`:

```kotlin
package com.example.excelstream.download

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class MemberPageFetcher(private val jdbc: JdbcTemplate) {

    /**
     * 키셋(seek) 페이징으로 전체 멤버를 지연 시퀀스로 공급한다.
     * `WHERE id > ?` + 인덱스 정렬이라 페이지마다 O(pageSize) 범위 스캔으로 끝나,
     * OFFSET 방식(뒤로 갈수록 O(n))의 비용을 피한다. 각 행은 [email, name, amount].
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
```

> 실무 팁(글에 각주): 위 코드는 **키셋(seek) 페이지네이션**(`WHERE id > ? ORDER BY id LIMIT ?`)이다. OFFSET 방식은 뒤로 갈수록 앞 행을 전부 스캔/폐기해 느려지므로(페이지가 뒤로 갈수록 O(n)) 대용량 내보내기에선 키셋이 안전하다. 더 끌어올리려면 **JDBC ResultSet 커서**(fetchSize 설정 + 스트리밍)도 선택지다.

---

## 4. 핵심 ②: 서버 디스크도 아끼려면 — S3 멀티파트 직접 스트리밍

SXSSF는 임시파일을 쓴다. 서버 디스크가 부족하면(컨테이너 환경 등) **만들면서 바로 S3로** 보내고 싶다.
AWS SDK v2의 멀티파트 업로드에 SXSSF 출력을 연결한다. 가장 단순한 방법은 **블로킹 OutputStream** 바디:

`src/main/kotlin/com/example/excelstream/excel/S3MultipartSink.kt`:

```kotlin
package com.example.excelstream.excel

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.BlockingOutputStreamAsyncRequestBody
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.transfer.s3.S3TransferManager
import software.amazon.awssdk.transfer.s3.model.UploadRequest
import java.io.OutputStream

/**
 * SXSSF가 쓰는 OutputStream을 S3 멀티파트 업로드에 직결한다.
 * 로컬에 완성 파일을 안 남기고(임시파일 제외) 흘려보낸다.
 */
@Component
class S3MultipartSink(private val tm: S3TransferManager) {

    @Value("\${app.s3.bucket}")
    private lateinit var bucket: String

    /**
     * writer가 outputStream에 쓰면, 그게 그대로 S3로 멀티파트 전송된다.
     * forBlockingOutputStream: length 모르는 스트림을 멀티파트로 올릴 때.
     */
    fun upload(key: String, writer: (OutputStream) -> Unit) {
        // ✅ forBlockingOutputStream(Long?) 의 실제 반환형은 BlockingOutputStreamAsyncRequestBody.
        //    (BlockingInputStreamAsyncRequestBody 가 아니다 — 후자는 outputStream() 이 없다)
        val body: BlockingOutputStreamAsyncRequestBody =
            AsyncRequestBody.forBlockingOutputStream(null) // contentLength unknown

        val upload = tm.upload(
            UploadRequest.builder()
                .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).build())
                .requestBody(body)
                .build(),
        )

        // writer가 OutputStream에 쓰는 동안 SDK가 멀티파트로 빨아들임
        body.outputStream().use { os ->
            writer(os)
        }
        upload.completionFuture().join()
    }
}
```

> 이게 "S3를 임시 작업공간(scratch)으로 쓰는" 패턴이다. 서버엔 SXSSF 윈도우 + 멀티파트 파트 버퍼만 남는다.
> ⚠️ SXSSF는 내부적으로 임시파일을 여전히 쓸 수 있으니(완전 무디스크는 아님), "디스크 사용 최소화"로 표현하는 게 정확하다. 완전 무디스크가 목표면 CSV 스트리밍이 답(아래 8번).
> ⚠️ `forBlockingOutputStream` 바디는 **재시도(retry)를 지원하지 않는다**("does not support retries"). 네트워크 오류 시 자동 재시도가 없으니, 프로덕션이라면 실패 처리/재실행 전략을 따로 둬야 한다(아래 5번의 job 상태로 일부 보완).

---

## 5. 핵심 ③: 생성이 오래 걸리면 — 비동기 + presigned URL

10만 행 생성 + S3 업로드는 수십 초~분이 걸릴 수 있다. HTTP를 붙잡지 말고 비동기로.

먼저 job 상태 저장소(PoC용 인메모리). 실무는 DB/Redis로:

`src/main/kotlin/com/example/excelstream/download/ExportJobStore.kt`:

```kotlin
package com.example.excelstream.download

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class ExportJobStore {
    private val jobs = ConcurrentHashMap<String, String>()   // jobId -> "RUNNING" | "DONE|url" | "FAILED|msg"

    fun set(jobId: String, status: String) { jobs[jobId] = status }
    fun get(jobId: String): String = jobs.getOrDefault(jobId, "NOT_FOUND")
}
```

실제 내보내기는 **별도 빈**에 둔다. `@Async`는 스프링 프록시를 통해 호출돼야 동작하므로, 같은 클래스 안에서 자기 메서드를 부르면(self-invocation) 비동기가 먹지 않는다:

`src/main/kotlin/com/example/excelstream/download/ExportJobRunner.kt`:

```kotlin
package com.example.excelstream.download

import com.example.excelstream.excel.S3MultipartSink
import com.example.excelstream.excel.StreamingXlsxWriter
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Duration

@Component
class ExportJobRunner(
    private val fetcher: MemberPageFetcher,
    private val sink: S3MultipartSink,
    private val presigner: S3Presigner,
    private val store: ExportJobStore,
) {
    @Value("\${app.s3.bucket}")
    private lateinit var bucket: String

    @Async   // ★ DownloadService(다른 빈)에서 호출 → 프록시 경유 → 진짜 비동기
    fun run(jobId: String) {
        try {
            val key = "exports/$jobId.xlsx"
            val writer = StreamingXlsxWriter()
            sink.upload(key) { os -> writer.write(os, fetcher.rows()) }
            store.set(jobId, "DONE|${presignedUrl(key)}")
        } catch (e: Exception) {
            store.set(jobId, "FAILED|${e.message}")
        }
    }

    private fun presignedUrl(key: String): String =
        presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(30))
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build(),
        ).url().toString()
}
```

`src/main/kotlin/com/example/excelstream/download/DownloadService.kt`:

```kotlin
package com.example.excelstream.download

import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DownloadService(
    private val store: ExportJobStore,
    private val runner: ExportJobRunner,
) {
    fun startExport(): String {
        val jobId = UUID.randomUUID().toString()
        store.set(jobId, "RUNNING")
        runner.run(jobId)     // 다른 빈의 @Async 메서드 → 즉시 반환
        return jobId
    }

    fun status(jobId: String): String = store.get(jobId)
}
```

`@Async` 활성화 (`config/AsyncConfig.kt`):

```kotlin
package com.example.excelstream.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync

@Configuration
@EnableAsync
class AsyncConfig
```

> `S3Presigner` 빈은 1차편에서 만든 `S3Config`에 이미 등록돼 있다(엔드포인트/리전/path-style 설정 공유).

컨트롤러:

```kotlin
package com.example.excelstream.download

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class DownloadController(private val service: DownloadService) {

    @PostMapping("/export")            // 비동기 시작 → jobId 반환
    fun export(): String = service.startExport()

    @GetMapping("/export/{jobId}")     // 폴링 → DONE|<presignedUrl>
    fun status(@PathVariable jobId: String): String = service.status(jobId)
}
```

---

## 6. 테스트 절차 (집에서 실행)

```bash
# 0. (1차편에서 이미) LocalStack 기동 + 업로드로 members 10만건 적재돼 있어야 함
docker compose up -d
curl -F "file=@/tmp/sample-100k.xlsx" http://localhost:8080/upload   # inserted=100000

# 1. 비동기 내보내기 시작
JOB=$(curl -s -X POST http://localhost:8080/export); echo $JOB

# 2. 상태 폴링 (DONE 되면 presigned URL 포함)
curl http://localhost:8080/export/$JOB
# RUNNING
# ...
# DONE|http://localhost:4566/excel-bucket/exports/<jobId>.xlsx?X-Amz-...

# 3. presigned URL로 다운로드
curl -o /tmp/export.xlsx "<위 URL>"
open /tmp/export.xlsx   # 10만 행 확인

# 4. 메모리 로그 확인 (앱 콘솔)
# [MEM] phase=download-write rows=10000 heapUsedMB=80
# [MEM] phase=download-write rows=100000 heapUsedMB=82  ← 평탄!
```

### S3 확인
```bash
awslocal s3 ls s3://excel-bucket/exports/
```

---

## 7. XSSF vs SXSSF 메모리 비교 (글의 핵심 그래프)

같은 10만 행 내보내기를:
- (A) `XSSFWorkbook` 으로 → heap 우상향 → `-Xmx256m`이면 OOM
- (B) `SXSSFWorkbook(100)` 으로 → heap 평탄

`[MEM]` 로그를 csv로 긁어 그래프 1장. **이게 2차편 결론.**

```bash
# 앱 로그에서 메모리 추이 추출 예시
grep "\[MEM\]" app.log | sed -E 's/.*rows=([0-9]+) heapUsedMB=([0-9]+).*/\1,\2/' > mem.csv
```

---

## 8. 보너스 — 완전 무디스크가 목표라면 CSV 스트리밍

xlsx(ZIP)는 구조상 임시파일을 완전히 없애기 어렵다. **CSV는 append-only 스트림**이라 임시파일/메모리 거의 없이 응답이나 S3로 한 줄씩 바로 쓸 수 있다.

```kotlin
// CSV는 SXSSF조차 필요 없다 — OutputStream에 직접 라인 쓰기
os.bufferedWriter().use { w ->
    w.write("email,name,amount\n")
    var page = 0
    while (true) {
        val rows = fetcher.fetch(page++, 1000)
        if (rows.isEmpty()) break
        for (r in rows) w.write("${r[0]},${r[1]},${r[2]}\n")
    }
}
```

> 글 마무리: "사용자가 꼭 xlsx를 원하는 게 아니면, 대용량 내보내기는 CSV가 가장 가볍다. 포맷 선택도 성능 설계의 일부다." (실무라면 쉼표/따옴표 이스케이프는 별도 처리)

---

## 9. 2부작 총정리 (마지막 글의 클로징)

| 문제 | 원인 | 해결 | 이 PoC에서 |
|---|---|---|---|
| 업로드 OOM | XSSF가 파일 전체를 메모리에 | **SAX 스트리밍 읽기** | 1차편 |
| 내보내기 OOM | 행을 전부 메모리에 누적 | **SXSSF**(디스크 flush) | 2차 §2 |
| 서버 디스크 부족 | SXSSF 임시파일 | **S3 멀티파트 직결** | 2차 §4 |
| HTTP 타임아웃 | 생성이 오래 걸림 | **비동기 + presigned URL** | 2차 §5 |
| 완전 무디스크 | xlsx=ZIP 한계 | **CSV 스트리밍** | 2차 §8 |

> "Node에만 있는 줄 알았던 스트리밍 대용량 처리, Java/Kotlin + Spring에서도 POI SAX + SXSSF + S3로 동일하게 된다. 그리고 LocalStack 덕분에 AWS 계정 없이 내 노트북에서 전부 검증했다."

## 트러블슈팅
- presigned URL 호스트가 `localhost:4566`이라 컨테이너 밖에서 접근 OK. 만약 도커 네트워크 이슈면 endpoint를 `127.0.0.1`로.
- `@Async`가 안 먹는다(동기로 동작): ① `@EnableAsync` 빠졌는지, ② `@Async` 메서드를 **같은 클래스 안에서** 부르고 있지 않은지 확인. self-invocation은 프록시를 안 거쳐 비동기가 무시된다(그래서 §5처럼 `ExportJobRunner`를 별도 빈으로 분리).
- `BlockingOutputStreamAsyncRequestBody` 관련 API는 SDK 버전마다 시그니처가 조금씩 다르다 — `forBlockingOutputStream` 미존재 시 SDK 버전 확인. (대안: SXSSF를 tmp파일로 쓴 뒤 TransferManager `uploadFile` — 디스크는 쓰지만 가장 안정적이고 재시도도 된다)
