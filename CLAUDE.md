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
- **SFTP `readLines()`는 `TB_COLLECT_HISTORY.LAST_BYTE_OFFSET`으로 바로 seek** — `RemoteFile.RemoteFileInputStream(offset)`으로 열어서 이미 읽은 앞부분은 네트워크로 재전송되지 않음. 신규 데이터가 없으면(오프셋 ≥ 파일크기) SFTP 접속 자체를 생략함(`CollectRetryService` Step 5-1). `LAST_LINE_NUMBER`는 별도로 계속 유지 — `TB_COLLECT_LOG.LINE_NUMBER` 등 절대 라인번호 연속성 때문에 오프셋과 별개로 필요
- **`LAST_BYTE_OFFSET`이 NULL인 레거시 이력**(이 컬럼 도입 이전 수집분)은 1회에 한해 파일 처음부터 다시 읽되 `LAST_LINE_NUMBER`까지는 저장하지 않고 건너뛰는 과도기 경로를 탐 — 그 실행이 끝나면 오프셋이 기록되어 이후부터는 정상 seek 경로로 전환됨
- **크래시 감지**: 수집 시작 직전 `STATUS=FAIL, FAIL_REASON=IN_PROGRESS` INSERT. 재기동 시 해당 이력은 자동 재처리 안 됨 — 수동 확인 필요
- **파일 경로 특수 토큰**: `yyyymmdd`(날짜), `mmdd`(월/일), `$`(요일 0-6), `+` 접미사(날짜 리셋 비활성화)
- 제외 파일 복원: `TB_COLLECT_EXCLUDE.RESTORE_YN='Y'`로 수동 변경
- **휴장일 스킵은 스케쥴 항목 단위**: conf 5번째 브라켓 `[Y]`로 표시된 항목만 오늘이 비영업일(`PreCheck_NotifyHoliday_List.conf`, notify와 물리적으로 동일 파일 공유)이면 건너뜀. `[Y]` 없는 기존 라인은 휴장일과 무관하게 항상 실행 — notify(통보)의 모듈 전체 스킵과 다름. 이제 `TB_COLLECT_HISTORY`에도 `SKIP`/`FAIL_REASON=HOLIDAY_SKIP` 이력을 남겨(하루 1회) dashboard 수집제외 카운터/툴팁에 노출됨

## 스택 참고

- Java 17, Spring Boot 3.5.3, MyBatis 3.0.5 (XML 매퍼), SSHJ 0.39.0
- PostgreSQL (local/test/prod)
- Log4j2 (`spring-boot-starter-logging` 제외)
- `collectExecutor` 스레드풀: core=5, max=20, queue=100
