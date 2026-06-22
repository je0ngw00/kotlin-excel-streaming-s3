#!/usr/bin/env bash
# 메모리 측정 스윕: 힙캡 × 모드 × 행수 → build/measure/results.csv
#
# 사용:
#   scripts/measure-sweep.sh
#   HEAPS="256m" ROWS="10000 50000 100000" scripts/measure-sweep.sh
#   MODES="xssf-read sax-read" scripts/measure-sweep.sh
#
# 결과 CSV 컬럼: heapMB,mode,rowsTarget,processed,status,peakHeapMB,elapsedMs
#   status: OK | OOM | ERROR:* | CRASH
# 그래프: 모드별로 (rowsTarget, peakHeapMB) 를 그리면 XSSF는 우상향/OOM, SAX·SXSSF는 평탄.
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/measure
mkdir -p "$OUT"
CSV="$OUT/results.csv"

HEAPS="${HEAPS:-256m 512m}"
MODES="${MODES:-sax-read xssf-read sxssf-write xssf-write}"
ROWS="${ROWS:-10000 50000 100000 250000 500000}"
LOG_EVERY="${LOG_EVERY:-10000}"

echo "heapMB,mode,rowsTarget,processed,status,peakHeapMB,elapsedMs" > "$CSV"

field() { echo "$1" | grep -oE "$2=[^ ]+" | cut -d= -f2; }

for xmx in $HEAPS; do
  for mode in $MODES; do
    for n in $ROWS; do
      echo ">> xmx=$xmx mode=$mode rows=$n" >&2
      result=$(./gradlew -q measure -Pxmx="$xmx" --args="$mode $n $LOG_EVERY" 2>/dev/null \
        | grep '^\[RESULT\]' | tail -1)
      if [ -z "$result" ]; then
        # 태스크가 [RESULT] 를 못 남기고 죽은 경우(진짜 크래시)
        result="[RESULT] mode=$mode rowsTarget=$n processed=0 status=CRASH peakHeapMB=NA elapsedMs=NA"
      fi
      echo "${xmx%m},$(field "$result" mode),$(field "$result" rowsTarget),$(field "$result" processed),$(field "$result" status),$(field "$result" peakHeapMB),$(field "$result" elapsedMs)" >> "$CSV"
    done
  done
done

echo "wrote $CSV" >&2
column -t -s, "$CSV" >&2
