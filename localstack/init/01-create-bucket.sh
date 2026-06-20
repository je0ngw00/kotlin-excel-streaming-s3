#!/bin/bash
# LocalStack 기동 완료 후 자동 실행되는 hook (ready.d).
# S3 버킷을 미리 만들어 둔다.
awslocal s3 mb s3://excel-bucket || true
echo "[init] bucket 'excel-bucket' ready"
