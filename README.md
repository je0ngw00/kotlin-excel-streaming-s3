# excel-streaming-poc

대용량 엑셀을 메모리에 통째로 올리지 않고 **스트림으로 조금씩** 처리하는 방법을
Kotlin(Spring Boot 3) + LocalStack(S3) 환경에서 재현한 PoC.

- **업로드**: S3에 스트리밍 저장 후, 거기서 한 행씩 SAX로 읽어 DB에 적재
- **다운로드**: DB를 페이징으로 조금씩 읽어 SXSSF로 엑셀을 만들고 S3 멀티파트로 직송 + presigned URL

## 핵심 아이디어

> 파일이든 DB든 전체를 메모리에 올리지 않고 스트림으로 흘려보낸다.
> POI SAX(읽기) + SXSSF(쓰기) + S3 스트리밍으로 OOM을 피한다.

## 기술 스택

| 영역 | 선택 | 이유 |
|---|---|---|
| 언어/프레임워크 | Kotlin + Spring Boot 3 (JVM 17) | 간결함, 널 안전성 |
| 엑셀 읽기 | Apache POI `XSSFReader` + SAX | 행 단위 스트리밍, OOM 회피 |
| 엑셀 쓰기 | Apache POI `SXSSFWorkbook` | 슬라이딩 윈도우, 디스크 flush |
| 오브젝트 스토리지 | LocalStack S3 | AWS 계정/비용 없이 로컬 재현 |
| AWS SDK | AWS SDK for Java v2 (`s3`, `s3-transfer-manager`) | 스트리밍 업/다운로드, 멀티파트 |
| DB | H2 (인메모리) | PoC용 페이징 시연 |

## 사전 준비물

- JDK 17+
- Docker / Docker Compose (LocalStack 구동)
- (선택) `awslocal` 또는 `aws --endpoint-url=http://localhost:4566`

## 빠른 시작

```bash
# 1. LocalStack(S3) 기동 — 버킷은 init hook이 자동 생성
docker compose up -d

# 2. 앱 실행 (힙을 작게 잡아 메모리 거동 관찰)
./gradlew bootRun

# 3. 샘플 대용량 엑셀 생성
./gradlew generateSample --args="100000 /tmp/sample-100k.xlsx"

# 4. 업로드
curl -F "file=@/tmp/sample-100k.xlsx" http://localhost:8080/upload   # → inserted=100000

# 5. 다운로드(내보내기)
# 5-1. 비동기 내보내기(xlsx 또는 CSV) — S3 업로드 + presigned URL
curl -X POST http://localhost:8080/export                            # → {jobId}
curl http://localhost:8080/export/{jobId}                            # → DONE|{presigned-url}

# 5-2. 동기 스트리밍 CSV 다운로드 — 응답으로 실시간 스트림
curl http://localhost:8080/export.csv > /tmp/exported.csv
```

## 내보내기 엔드포인트

포맷(xlsx/CSV)과 전달 전략(동기 스트리밍 / 비동기+S3)은 직교한다.
CSV는 가벼워 두 전략 모두를 제공한다.

| 엔드포인트 | 메서드 | 설명 |
|-----------|--------|------|
| `/export` | POST | 비동기 내보내기. 폴링으로 상태 확인. 포맷: `?format=xlsx\|csv` (기본값 xlsx). S3에 업로드 후 presigned URL 반환. |
| `/export/{jobId}` | GET | 비동기 작업 상태 폴링. `RUNNING` → `DONE\|<presignedUrl>` 또는 `FAILED\|<msg>` |
| `/export.csv` | GET | 동기 스트리밍 CSV 다운로드. 응답으로 한 줄씩 흘려보냄(서버 디스크/메모리 평탄). |

**비동기 vs. 동기:**
- **비동기** (`POST /export`): 대용량 xlsx에 적합. S3로 오프로드. 클라이언트는 폴링으로 완료 대기.
- **동기** (`GET /export.csv`): 가벼운 CSV용. 스트림으로 즉시 응답. S3 거치지 않음.

## 테스트

```bash
./gradlew test
```

**테스트 구성:**
- **업로드 테스트:**
  - 단위: `StreamingXlsxReader`, `S3ExcelStorage` 라운드트립
  - 통합: `UploadIntegrationTest` (Testcontainers LocalStack)
- **다운로드 테스트:**
  - 단위: CSV/XLSX 작성자(writer) 라운드트립, `ExportJobRunner`·`DownloadService` MockK
  - 통합: `DownloadController` WebMvc, Testcontainers LocalStack 라운드트립

**실행 환경:**
- 단위/슬라이스 테스트는 Docker 없이 동작한다.
- `UploadIntegrationTest`, `DownloadIntegrationTest` 등은 Testcontainers로 실제 LocalStack 컨테이너를 띄우므로 **Docker 데몬이 필요**하다.

## 디렉터리 구조

```
excel-streaming-poc/
├── docker-compose.yml              LocalStack(S3)
├── localstack/init/01-create-bucket.sh
├── build.gradle
├── guide/                          따라하기 가이드
│   ├── 01-upload.md
│   └── 02-download.md
└── src/main/kotlin/com/example/excelstream/
    ├── config/        S3Config, AsyncConfig
    ├── support/       MemoryProbe
    ├── sample/        SampleExcelGenerator
    ├── domain/        Member, MemberBatchRepository
    ├── excel/         StreamingXlsxReader, StreamingXlsxWriter, S3ExcelStorage, S3MultipartSink
    ├── upload/        UploadService, UploadController
    └── download/      DownloadService, ExportJobRunner, ExportJobStore, MemberPageFetcher, DownloadController
```

## 알려진 한계

- xlsx는 ZIP(내부 XML) 구조라 읽기에 ZIP 랜덤 액세스가 필요해 `S3 → 로컬 tmp → SAX` 경로를 기본으로 한다.
- `SharedStringsTable`이 크면 메모리를 먹는다. `ReadOnlySharedStringsTable`로 완화하되 한계가 있다.
- LocalStack ≠ 실제 S3: 멀티파트/일관성에 미세 차이가 있을 수 있어 프로덕션 적용 전 실 S3 재검증을 권장한다.
- 인증/권한/에러복구/체크포인트는 PoC 범위에서 최소화했다.
