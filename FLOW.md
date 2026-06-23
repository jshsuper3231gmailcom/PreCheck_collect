# precheck-collect 개발자 참고 문서

## 1. 프로젝트 개요

### 목적 및 역할

`precheck-collect`는 대상 서버의 로그 파일을 SFTP로 수집하여 DB에 적재하는 Spring Boot 배치 서버다. HTTP 서버를 구동하지 않고, `@Scheduled` + `@Async` + `@Retryable` 조합으로 스케줄 기반 비동기 수집을 수행한다.

스케줄 파일(`PreCheck_CollectLogs_Schedule.conf`)을 1초마다 폴링하여 실행 시점이 된 수집 작업을 실행한다. 수집된 로그 라인은 정규화 포맷(`@@@...@@@`)으로 파싱된 뒤 `TB_COLLECT_LOG`에 INSERT되며, 이후 `precheck-analyze` 서버가 이 테이블을 분석한다.

수집 방식은 증분(주기) 또는 전체(배치) 두 가지로 구분되며, 파일 크기 초과 시 `TB_COLLECT_EXCLUDE`에 등록하여 해당 파일을 영구 제외한다.

### 기술 스택

| 항목 | 내용 |
|------|------|
| 런타임 | Java 17, Spring Boot 3.5.3 |
| 스케줄링 | `@Scheduled(fixedDelay)` + `@Async` (collectExecutor) |
| 재시도 | Spring Retry (`@Retryable` / `@Recover`) |
| SFTP | SSHJ 0.39.0 (`PromiscuousVerifier`) |
| DB | PostgreSQL (local/test) / Altibase 8.1.0.0.1 (prod) |
| ORM | MyBatis 3.0.5 (XML mapper) |
| 로깅 | Log4j2 (spring-boot-starter-logging 제외) |
| 빌드 | Gradle |

### 실행 방식

HTTP 포트 없음. JVM 프로세스로 기동 후 `CollectScheduler`가 매초 스케줄 파일을 읽어 수집 대상을 판별한다. 수집 대상 발견 시 `collectExecutor` 스레드풀에 비동기 작업을 제출한다.

---

## 2. 데이터 흐름

### 전체 흐름도

```
[PreCheck_CollectLogs_Schedule.conf]
        │  (1초마다 폴링, 60초 캐시)
        ▼
[CollectScheduler] ──── 스케줄 매칭 (pollWindowSeconds=2) ────┐
                                                               │
        ┌──────────────────────────────────────────────────────┘
        │  @Async("collectExecutor")
        ▼
[CollectService.collect()]
        │  INSERT TB_COLLECT_HISTORY (STATUS=FAIL / failReason=IN_PROGRESS) ← crash-safe
        │  AOP proxy → CollectRetryService
        ▼
[CollectRetryService.collectWithRetry()]  @Retryable(maxAttempts=4, delay=10s)
        │
        ├── Step 1: retryCount 갱신 (RetrySynchronizationManager)
        ├── Step 2: TB_COLLECT_EXCLUDE 체크 → SKIP
        ├── Step 3: SFTP getFileSizeBytes()
        ├── Step 4: 배치 + fileSize ≥ 300MB → registerExclude + SKIP
        ├── Step 5: 주기 → findLastLineNumber(), 날짜 리셋 판정
        ├── Step 6: readLines() → logNormalizeParser.parseNormalizedLogFromLine()
        ├── Step 7: 주기 + totalReadBytes ≥ 50MB → registerExclude + SKIP
        ├── Step 8: INSERT TB_COLLECT_LOG (SEQ_COLLECT_LOG)
        └── Step 9: UPDATE TB_COLLECT_HISTORY → SUCCESS (LAST_LINE_NUMBER 저장)
                                                               │
        @Recover → UPDATE TB_COLLECT_HISTORY → FAIL           │
                                                               ▼
                                                    [TB_COLLECT_LOG]
                                                               │
                                                    (precheck-analyze 분석)
```

### 주요 시나리오별 흐름

#### A. 주기 수집 (증분)

```
1. LAST_LINE_NUMBER 조회 (TB_COLLECT_HISTORY)
2. COLLECT_DATE ≠ 오늘 AND '+' 미사용 → startLineNumber=0 (날짜 리셋)
3. SFTP readLines(startLineNumber=LAST_LINE_NUMBER+1)
4. 각 라인: @@@...@@@ 파싱 → 파싱 성공 시 로그 리스트에 추가
5. totalReadBytes ≥ 50MB → PART_SIZE 제외 등록, SKIP
6. TB_COLLECT_LOG INSERT (파싱 성공한 라인만)
7. LAST_LINE_NUMBER = 마지막 읽은 라인 번호로 UPDATE
```

#### B. 배치 수집 (전체)

```
1. SFTP getFileSizeBytes()
2. fileSize ≥ 300MB → INIT_SIZE 제외 등록, SKIP
3. startLineNumber=0 으로 전체 파일 읽기
4. TB_COLLECT_LOG INSERT
5. LAST_LINE_NUMBER 갱신 안 함 (배치는 매번 전체 읽기)
```

#### C. 제외 파일 처리

```
isExcluded() → TB_COLLECT_EXCLUDE WHERE RESTORE_YN='N' 존재
→ STATUS=SKIP, SKIP_REASON="EXCLUDED" 로 종료
(관리자가 RESTORE_YN='Y' 로 변경하면 다음 수집부터 재개)
```

#### D. 파일 경로 특수 처리

```
sourceFilePath에 "yyyymmdd" 포함 → today 날짜(yyyyMMdd)로 치환
  예: /logs/app_yyyymmdd.log → /logs/app_20260616.log

sourceFilePath에 "mmdd" 포함 → today 월/일(MMdd)로 치환
  예: /logs/app_mmdd.log → /logs/app_0616.log

sourceFilePath에 "$" 포함 → today 요일 숫자(0=일요일~6=토요일)로 치환
  예: /logs/sys0$.log → (월요일) /logs/sys01.log

sourceFilePath가 "+" 로 끝남 → 날짜 리셋 비활성화
  예: /logs/app.log+  → 날짜 바뀌어도 LAST_LINE_NUMBER 유지
```

---

## 3. 디렉토리 및 파일 구조

### 디렉토리 역할

```
collect/
├── src/main/java/com/precheck/collect/
│   ├── CollectApplication.java          메인 클래스
│   ├── scheduler/                        스케줄 폴링 및 실행 트리거
│   ├── service/                          수집 비즈니스 로직, SFTP, 파일 읽기
│   ├── parser/                           로그 정규화 포맷 파싱
│   ├── mapper/                           MyBatis 매퍼 인터페이스
│   ├── dto/                              데이터 전송 객체
│   ├── constants/                        상수 정의
│   └── config/                           Async 설정
├── src/main/resources/
│   ├── application.yml                   공통 설정
│   ├── application-local.yml             로컬 (PostgreSQL + LocalFileService)
│   ├── application-test.yml              테스트 (PostgreSQL + SftpService)
│   ├── application-prod.yml              운영 (Altibase + SftpService)
│   ├── mapper/                           MyBatis XML 매퍼
│   └── log4j2.xml                        로그 설정
└── build.gradle
```

### 주요 파일 목록

| 파일 | 역할 |
|------|------|
| `CollectScheduler.java` | 매 1초 스케줄 파일 읽기, 실행 시점 매칭, `collectService.collect()` 호출 |
| `CollectService.java` | `@Async` 진입점, TB_COLLECT_HISTORY 초기 INSERT, AOP proxy 경유 |
| `CollectRetryService.java` | `@Retryable` 9단계 수집 로직, `@Recover` 실패 처리 |
| `SftpService.java` | SSHJ 기반 SFTP 연결, `getFileSizeBytes()`, `readLines()` |
| `LocalFileService.java` | 로컬 파일 시스템 읽기 (테스트 전용) |
| `FileReadService.java` | `SftpService`/`LocalFileService` 공통 인터페이스 |
| `LogNormalizeParser.java` | `@@@[timestamp][logType][logId]\|content\|$tokens$@@@` 파싱 |
| `ExcludeService.java` | TB_COLLECT_EXCLUDE 조회/등록 |
| `AsyncConfig.java` | `collectExecutor` 스레드풀 빈 정의 |
| `CollectConstants.java` | 크기 제한, 재시도 설정, 경로 특수 문자 상수 |
| `CollectHistoryMapper.java` / `.xml` | TB_COLLECT_HISTORY CRUD |
| `CollectLogMapper.java` / `.xml` | TB_COLLECT_LOG INSERT / 분석용 SELECT |
| `PreCheck_CollectLogs_Schedule.conf` | 수집 스케줄 정의 파일 (serverId/serverIp/path/expr) |

---

## 4. 소스별 주요 함수/메서드

### `scheduler/CollectScheduler.java`

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `scheduledTask()` | 없음 | void | `@Scheduled(fixedDelay=1000ms)` 진입점, 스케줄 파일 로드 후 매칭 |
| `loadScheduleRules()` | 없음 | `List<ScheduleRule>` | 스케줄 파일 파싱 (60초 캐시), `[serverId][serverIp][path][expr]` 4필드 |
| `isTimeToRun(rule)` | `ScheduleRule` | boolean | pollWindowSeconds=2 이내 시작시각 매칭 |
| `buildScheduleKey(rule)` | `ScheduleRule` | String | serverId+serverIp+path+expr 조합으로 중복 실행 방지 키 생성 |
| `isValidSftpConfig(rule)` | `ScheduleRule` | boolean | sftp 모드에서 자격증명 빈 값 체크 |

**내부 클래스 `ScheduleRule`**

| 필드 | 타입 | 설명 |
|------|------|------|
| `type` | String | `주기` / `배치` |
| `daySpec` | String | 요일 (`*` / `월화수` 등) |
| `startTime` | LocalTime | 시작 시각 |
| `intervalMinutes` | int | 반복 간격 (분) — 주기 전용 |
| `endTime` | LocalTime | 종료 시각 |

---

### `service/CollectService.java`

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `collect(rule)` | `ScheduleRule` | void | `@Async("collectExecutor")`, TB_COLLECT_HISTORY 초기 INSERT 후 `collectRetryService.collectWithRetry()` 위임 |

> `CollectRetryService`를 별도 빈으로 분리한 이유: Spring AOP 프록시 우회 방지.
> `this.method()` 자기 호출 시 `@Retryable` AOP가 동작하지 않으므로 반드시 별도 빈 경유.

---

### `service/CollectRetryService.java`

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `collectWithRetry(rule, historyId)` | `ScheduleRule`, `Long` | void | `@Retryable(CollectException, maxAttempts=4, delay=10000ms)` — 9단계 수집 흐름 |
| `recover(e, rule, historyId)` | `CollectException`, `ScheduleRule`, `Long` | void | `@Recover` — 최대 재시도 소진 후 FAIL 처리 |

**9단계 수집 흐름 상세**

| 단계 | 동작 | 관련 메서드/클래스 |
|------|------|-------------------|
| 1 | retryCount 갱신 | `RetrySynchronizationManager.getContext().getRetryCount()` |
| 2 | 제외 파일 체크 | `excludeService.isExcluded(serverId, filePath)` |
| 3 | SFTP 파일 크기 조회 | `fileReadService.getFileSizeBytes(...)` |
| 4 | 배치 + ≥300MB → 영구 제외 | `excludeService.registerExclude(..., "INIT_SIZE")` |
| 5 | 주기: 마지막 라인 번호 조회, 날짜 리셋 판정 | `collectHistoryMapper.findLastLineNumber(...)` |
| 6 | 라인 읽기 + 정규화 파싱 | `fileReadService.readLines(...)`, `logNormalizeParser.parseNormalizedLogFromLine(...)` |
| 7 | 주기 + ≥50MB → 영구 제외 | `excludeService.registerExclude(..., "PART_SIZE")` |
| 8 | TB_COLLECT_LOG INSERT | `collectLogMapper.insert(...)` (SEQ_COLLECT_LOG) |
| 9 | TB_COLLECT_HISTORY 성공 UPDATE | `collectHistoryMapper.updateCollectStatus(...)` |

**`LineReadState` 내부 클래스**

| 필드 | 설명 |
|------|------|
| `lastReadLineNumber` | 마지막으로 읽은 라인 번호 |
| `totalReadBytes` | 이번 실행에서 읽은 누적 바이트 |
| `totalLineCount` | 이번 실행에서 읽은 라인 수 |
| `exceededPartSizeLimit` | 50MB 초과 여부 플래그 |

---

### `service/SftpService.java`

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `getFileSizeBytes(host, port, user, pass, path)` | String, int, String, String, String | long | SFTP stat으로 파일 크기 조회 |
| `readLines(host, port, user, pass, path, startLine, consumer)` | ..., long, `BiConsumer<Long,String>` | void | startLine 이전 라인 순차 스킵, 이후 라인 consumer 콜백 |

- `@ConditionalOnProperty(name="precheck.collect.mode", havingValue="sftp", matchIfMissing=true)`
- SSHJ `PromiscuousVerifier` — 내부망 전용, 호스트 키 검증 생략
- 호출마다 새 SSHClient 생성/종료 (커넥션 재사용 없음, 스레드 안전)

---

### `service/LocalFileService.java`

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `getFileSizeBytes(...)` | (serverIp/port/user/pass 무시) | long | 로컬 파일 크기 조회 |
| `readLines(...)` | (serverIp/port/user/pass 무시) | void | 로컬 파일 순차 읽기 |

- `@ConditionalOnProperty(name="precheck.collect.mode", havingValue="local")`
- 로컬 개발/테스트 전용

---

### `parser/LogNormalizeParser.java`

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `parseNormalizedLogFromLine(line, failDetails)` | String, `List<String>` | `NormalizedLog?` | 운영 파싱 메서드, 실패 시 failDetails에 사유 추가 후 null 반환 |
| `parseFile(filePath)` | String | `List<NormalizedLog>` | 테스트/단독 실행용 파일 전체 파싱 |

**정규화 로그 포맷**

```
@@@[yyyy/MM/dd HH:mm:ss.SSS][logType][LOG_ID]|content|$token1$$token2$@@@
```

**logType별 파싱 규칙**

| logType | 토큰 조건 | 처리 |
|---------|-----------|------|
| 문구 | 없음 | content만 저장 |
| 수치 | 숫자 토큰 정확히 1개 | BigDecimal 변환 |
| 날짜 | 없음 | content를 날짜로 해석 |
| 존재 | 없음 | content를 Y/N으로 해석 |
| 정보 | 없음 | content 그대로 |
| 비교 | 숫자 토큰 정확히 2개 | BigDecimal 두 값 저장 |
| 시간 | HH:mm 토큰 1개 | 분(minute) 수로 BigDecimal 변환 |

**패턴 상수**

| 패턴 | 정규식 | 설명 |
|------|--------|------|
| `HEADER_PATTERN` | `^@@@\[timestamp\]\[logType\]\[logId\]` | 라인 시작 구조 검증 |
| `LOG_ID_PATTERN` | `^[A-Z0-9_]{1,30}$` | LOG_ID 형식 검증 |
| `VALUE_TOKEN_PATTERN` | `\$[^$]+\$` | `$...$` 토큰 추출 |

---

### `service/ExcludeService.java`

| 함수명 | 파라미터 | 반환값 | 설명 |
|--------|----------|--------|------|
| `isExcluded(serverId, filePath)` | String, String | boolean | TB_COLLECT_EXCLUDE WHERE RESTORE_YN='N' 존재 여부 |
| `registerExclude(serverId, filePath, reason)` | String, String, String | void | TB_COLLECT_EXCLUDE INSERT |

---

### `config/AsyncConfig.java`

| 빈 이름 | 설정값 | 설명 |
|---------|--------|------|
| `collectExecutor` | core=5, max=20, queue=100, prefix="collect-async-" | 수집 비동기 스레드풀 |

---

### `constants/CollectConstants.java`

| 상수 | 값 | 설명 |
|------|-----|------|
| `INIT_COLLECT_SIZE_LIMIT_BYTES` | 300MB | 배치 수집 최대 파일 크기 |
| `PART_COLLECT_SIZE_LIMIT_BYTES` | 50MB | 주기 수집 1회 최대 읽기 크기 |
| `MAX_RETRY_COUNT` | 3 | 최대 재시도 횟수 |
| `RETRY_DELAY_MILLISECONDS` | 10,000L (10초) | 재시도 간격 |
| `FILE_PATH_DATE_PLACEHOLDER` | `"yyyymmdd"` | 날짜(yyyyMMdd) 치환 플레이스홀더 |
| `FILE_PATH_DATE_PLACEHOLDER_MMDD` | `"mmdd"` | 월/일(MMdd) 치환 플레이스홀더 |
| `FILE_PATH_DATE_PLACEHOLDER_DOW` | `"$"` | 요일 숫자(0~6) 치환 플레이스홀더 |
| `FILE_PATH_NO_DATE_RESET_SUFFIX` | `"+"` | 날짜 리셋 비활성화 접미사 |
| `STATUS_SUCCESS` | `"SUCCESS"` | 수집 성공 상태 |
| `STATUS_FAIL` | `"FAIL"` | 수집 실패 상태 |
| `STATUS_SKIP` | `"SKIP"` | 수집 건너뜀 상태 |
| `EXCLUDE_REASON_INIT_SIZE` | `"INIT_SIZE"` | 배치 크기 초과 제외 사유 |
| `EXCLUDE_REASON_PART_SIZE` | `"PART_SIZE"` | 주기 크기 초과 제외 사유 |

---

## 5. 리소스 및 DB 환경

### DB 연결 정보

| 환경 | DB | JDBC URL | 비고 |
|------|-----|---------|------|
| local | PostgreSQL | `jdbc:postgresql://localhost:5432/postgres` | LocalFileService 사용 |
| test | PostgreSQL | `jdbc:postgresql://[테스트서버]:5432/precheck` | SftpService 사용 |
| prod | Altibase | `jdbc:Altibase://192.168.0.1:20300/precheck` | SftpService 사용 |

### 사용 테이블 목록

| 테이블 | 역할 |
|--------|------|
| `TB_COLLECT_HISTORY` | 수집 작업 이력 (STATUS, LAST_LINE_NUMBER, retryCount 등) |
| `TB_COLLECT_LOG` | 수집된 정규화 로그 라인 (SEQ_COLLECT_LOG 시퀀스) |
| `TB_COLLECT_EXCLUDE` | 크기 초과 등으로 영구 제외된 파일 목록 |

**TB_COLLECT_HISTORY 주요 컬럼**

| 컬럼 | 설명 |
|------|------|
| `HISTORY_ID` | PK |
| `SERVER_ID` | 대상 서버 ID |
| `SOURCE_FILE_PATH` | 수집 대상 파일 경로 |
| `STATUS` | SUCCESS / FAIL / SKIP |
| `LAST_LINE_NUMBER` | 다음 수집 시작 라인 (주기 수집) |
| `COLLECT_DATE` | 수집 일자 (날짜 리셋 판단 기준) |
| `RETRY_COUNT` | 현재 재시도 횟수 |
| `FAIL_REASON` | 실패 사유 (`IN_PROGRESS` → 크래시 감지용) |

**TB_COLLECT_LOG 주요 컬럼**

| 컬럼 | 설명 |
|------|------|
| `LOG_SEQ` | PK (SEQ_COLLECT_LOG) |
| `SERVER_ID` | 대상 서버 ID |
| `LOG_ID` | 로그 식별자 (`[A-Z0-9_]{1,30}`) |
| `LOG_TYPE` | 문구 / 수치 / 날짜 / 존재 / 정보 / 비교 / 시간 |
| `LOG_TIMESTAMP` | 로그 발생 시각 |
| `CONTENT` | 로그 내용 |
| `VALUE1`, `VALUE2` | 수치/비교형 파싱값 (BigDecimal) |
| `COLLECT_DATE` | 수집 일자 |

**TB_COLLECT_EXCLUDE 주요 컬럼**

| 컬럼 | 설명 |
|------|------|
| `SERVER_ID` | 대상 서버 ID |
| `FILE_PATH` | 제외된 파일 경로 |
| `EXCLUDE_REASON` | INIT_SIZE / PART_SIZE |
| `RESTORE_YN` | N: 제외 중 / Y: 복원 (관리자 수동 변경) |

### 외부 리소스

| 리소스 | 상세 |
|--------|------|
| SFTP 서버 | 대상 서버 (port 22, SSHJ PromiscuousVerifier) |
| 스케줄 파일 | `PreCheck_CollectLogs_Schedule.conf` (환경별 경로 다름) |

### MyBatis Mapper

**`CollectHistoryMapper.xml` 주요 쿼리**

| 쿼리 ID | 설명 |
|---------|------|
| `insert` | TB_COLLECT_HISTORY 초기 INSERT |
| `updateCollectStatus` | STATUS / LAST_LINE_NUMBER / RETRY_COUNT 동적 UPDATE (`<if>` 조건) |
| `findLastLineNumber` | 최근 SUCCESS 이력의 LAST_LINE_NUMBER 조회 (`FETCH FIRST 1 ROWS ONLY`) |

**`CollectLogMapper.xml` 주요 쿼리**

| 쿼리 ID | 설명 |
|---------|------|
| `insert` | TB_COLLECT_LOG 15개 필드 INSERT |
| `findForAnalyze` | analyze 서버용 SELECT (날짜/serverId/logType/logId 필터) |

---

## 6. 설정 파일 분석

### `application.yml` (공통)

| 항목 | 기본값 | 설명 |
|------|--------|------|
| `spring.application.name` | `precheck-collect` | 앱 이름 |
| `spring.profiles.active` | `local` | 기본 활성 프로파일 |
| `precheck.collect.async.core-pool-size` | `5` | collectExecutor 코어 스레드 수 |
| `precheck.collect.async.max-pool-size` | `20` | collectExecutor 최대 스레드 수 |
| `precheck.collect.async.queue-capacity` | `100` | collectExecutor 큐 용량 |
| `mybatis.mapper-locations` | `classpath:mapper/*.xml` | XML 매퍼 위치 |
| `mybatis.type-aliases-package` | `com.precheck.collect.dto` | 타입 별칭 패키지 |
| `mybatis.configuration.map-underscore-to-camel-case` | `true` | 스네이크 → 카멜 자동 변환 |

---

### `application-local.yml` (로컬 개발)

| 항목 | 값 | 설명 |
|------|-----|------|
| DB | PostgreSQL localhost:5432/postgres | 로컬 개발 DB |
| `precheck.collect.mode` | `local` | LocalFileService 활성화 (SFTP 사용 안 함) |
| `schedule-file-path` | 로컬 sample conf 경로 | 로컬 테스트용 스케줄 파일 |
| `banner-mode` | `off` | 스프링 배너 비활성화 |

---

### `application-test.yml` (테스트 서버)

| 항목 | 값 | 설명 |
|------|-----|------|
| DB | PostgreSQL 테스트 서버 | 테스트 환경 DB |
| SFTP | port 22, `/home/precheck/cfg/...` | SftpService 활성화 |
| `precheck.collect.mode` | `sftp` | SftpService 활성화 |

---

### `application-prod.yml` (운영)

| 항목 | 값 | 설명 |
|------|-----|------|
| DB | Altibase 192.168.0.1:20300/precheck | 운영 DB |
| SFTP | port 22 | SftpService 활성화 |
| `schedule-file-path` | `/home/precheck/cfg/PreCheck_CollectLogs_Schedule.conf` | 운영 스케줄 파일 경로 |
| `precheck.collect.mode` | `sftp` | SftpService 활성화 |

---

### `PreCheck_CollectLogs_Schedule.conf` (스케줄 파일)

**파일 형식**

```
[serverId][serverIp][sourceFilePath][scheduleExpression]
```

**scheduleExpression 형식**

| 유형 | 형식 | 예시 |
|------|------|------|
| 주기 | `주기\|요일\|시작시각\|간격(분)\|종료시각` | `주기\|*\|080000\|1\|235959` |
| 배치 | `배치\|요일\|시각` | `배치\|월수금\|020000` |

**파일 경로 특수 처리**

| 규칙 | 예시 |
|------|------|
| `yyyymmdd` 플레이스홀더 | `/logs/app_yyyymmdd.log` → `/logs/app_20260616.log` |
| `+` 접미사 | `/logs/app.log+` → 날짜 리셋 비활성화 |

**현재 활성 항목 (운영 예시)**

```
[pjpsap01-주파수클럽][127.0.0.1][/var/log/app/monitoring_jpc.log][주기|*|080000|1|235959]
[dcoodb01-주문체결][127.0.0.1][/var/log/app/monitoring_jcm.log][주기|*|080000|1|235959]
```

---

## 7. 주요 아키텍처 결정 및 주의사항

### Spring Retry AOP 우회 방지

`@Retryable`은 AOP 프록시 기반이므로 `this.method()` 자기 호출 시 동작하지 않는다.  
`CollectService` → `CollectRetryService` 구조로 분리하여 스프링 컨텍스트 프록시를 경유한다.

### SFTP 연결 전략

매 수집 호출마다 새 `SSHClient`를 생성하고 종료한다. 커넥션 풀 없음.  
`PromiscuousVerifier`로 호스트 키 검증을 생략한다 (내부망 전용 가정).  
`readLines()`에서 `startLineNumber` 이전 라인은 순차적으로 스킵한다 (SFTP seek 미지원으로 인한 설계 결정).

### Crash-safe 이력 관리

수집 시작 직전 `STATUS=FAIL, failReason=IN_PROGRESS`로 INSERT한다.  
프로세스 비정상 종료 시 `IN_PROGRESS` 상태가 남아 미완료 수집을 감지할 수 있다.

### `@Async` + `@Retryable` 조합 주의

`collectExecutor` 스레드에서 `@Retryable`이 동작한다.  
재시도는 같은 스레드에서 delay 후 재실행되므로, 재시도 대기 중 해당 스레드가 점유된다.  
max=20 스레드 모두 재시도 대기 상태가 되면 신규 수집 작업이 큐에 적재된다 (queue=100).

### 스케줄러 동작 방식

- **폴링 주기**: 매 1초 (`fixedDelay=1000ms`)
- **스케줄 파일 캐시**: 60초 (`reloadIntervalMillis=60000`)
- **시간 매칭 윈도우**: ±2초 (`pollWindowSeconds=2`)
- 같은 스케줄 키가 윈도우 내 중복 실행되지 않도록 실행 중인 키를 추적

### analyze 서버와의 연계

`TB_COLLECT_LOG`의 `findForAnalyze` 쿼리는 `precheck-analyze` 서버가 직접 호출한다.  
두 서버가 같은 DB를 공유하며, collect가 INSERT → analyze가 SELECT 하는 단방향 흐름이다.
