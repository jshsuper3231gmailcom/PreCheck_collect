# CLAUDE.md

## 역할

`@Scheduled` + `@Async` + `@Retryable` 배치 서버. HTTP 포트 없음. 로그 파일을 SFTP(운영/test 프로파일) 또는 로컬 파일(local 프로파일)로 읽어 `TB_COLLECT_LOG`에 INSERT. 상세는 `FLOW.md`.

---

## 명령어

```bash
# 로컬 테스트 (기본 프로파일 = local → LocalFileService + PostgreSQL)
gradlew.bat bootRun

# 명시적 로컬 프로파일
gradlew.bat bootRun --args="--spring.profiles.active=local"

# 빌드 / 테스트
gradlew.bat build
gradlew.bat test
```

---

## 로컬 테스트 사전 준비

1. `test_dataset/` 아래 로그 파일 필요 → 프로젝트 루트에서 `/logdatagen` 실행
2. `schedule_sample/PreCheck_CollectLogs_Schedule.conf`의 `sourceFilePath`가 `test_dataset/` 실제 절대경로와 일치하는지 확인
3. 수집은 conf의 `시작시각` 이후부터 동작 — 기본 샘플이 `08:00:00`이므로 그 전이면 conf 시작시각을 현재보다 이전으로 임시 조정

---

## 핵심 gotcha

- **`@Retryable` 별도 빈 필수**: `CollectService` → `CollectRetryService`. `this.method()` 직접 호출 시 AOP 프록시 우회 → 재시도 불동작
- **`VALUE1`/`VALUE2`는 `BigDecimal`** — Float/Double 사용 금지
- **SFTP `readLines()`는 startLineNumber 이전 줄을 순차 스킵** — SFTP seek 미지원 설계. 대용량 파일에서 초기 줄 수가 많으면 느릴 수 있음
- **크래시 감지**: 수집 시작 직전 `STATUS=FAIL, FAIL_REASON=IN_PROGRESS` INSERT. 재기동 시 해당 이력은 자동 재처리 안 됨 — 수동 확인 필요
- **파일 경로 특수 토큰**: `yyyymmdd`(날짜), `mmdd`(월/일), `$`(요일 0-6), `+` 접미사(날짜 리셋 비활성화)
- 제외 파일 복원: `TB_COLLECT_EXCLUDE.RESTORE_YN='Y'`로 수동 변경

## 스택 참고

- Java 17, Spring Boot 3.5.3, MyBatis 3.0.5 (XML 매퍼), SSHJ 0.39.0
- PostgreSQL (local/test) / Altibase (prod)
- Log4j2 (`spring-boot-starter-logging` 제외)
- `collectExecutor` 스레드풀: core=5, max=20, queue=100
