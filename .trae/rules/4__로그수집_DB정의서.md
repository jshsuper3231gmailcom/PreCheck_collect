# PreCheck 로그 수집 DB 정의서 v1.0

---

## 1. 문서 목적

이 문서는 PreCheck 로그 수집 서버에서 사용하는 DB 테이블 구조를 정의한다.  
테스트 환경은 **PostgreSQL**, 운영 환경은 **Altibase**를 사용하며  
두 DB에서 동일하게 동작할 수 있도록 호환 가능한 타입만 사용한다.

---

## 2. DB 호환성 원칙

| 원칙 | 내용 |
|---|---|
| PK 생성 방식 | SEQUENCE 객체 사용 (양쪽 DB 모두 지원) |
| 문자열 타입 | VARCHAR(n) 사용, TEXT 사용 금지 |
| 날짜/시간 타입 | TIMESTAMP 사용 |
| 실수 타입 | NUMERIC(p,s) 사용 |
| 논리값 타입 | CHAR(1) 사용 ('Y'/'N'), BOOLEAN 사용 금지 |
| 대용량 문자열 | VARCHAR(4000) 이하로 설계, CLOB 사용 금지 |

> 💡 **왜 이렇게 하나?**  
> PostgreSQL은 BOOLEAN, TEXT, SERIAL 같은 편한 타입을 지원하지만  
> Altibase는 이런 타입을 지원하지 않아.  
> 그래서 두 DB가 공통으로 지원하는 타입만 사용해서 SQL을 동일하게 유지하는 거야.  
> Spring Boot + JPA를 쓰면 application.properties의 dialect 설정만 바꾸면  
> 코드 변경 없이 DB를 전환할 수 있어.

---

## 3. SEQUENCE 설계

```sql
-- ============================================================
-- SEQUENCE 정의
-- PK 자동 증가를 위한 시퀀스 객체
-- PostgreSQL과 Altibase 모두 SEQUENCE 문법을 동일하게 지원함
-- ============================================================

-- 수집 로그 테이블용 시퀀스
-- START WITH 1      : 1부터 시작
-- INCREMENT BY 1    : 1씩 증가
-- NOCACHE           : 캐시 미사용 (데이터 손실 방지, 소규모 시스템 기준)
--                     → 운영에서 INSERT 속도가 느리면 CACHE 20 으로 변경 검토
-- NOCYCLE           : 최대값 도달 시 오류 발생 (재사용 방지)
CREATE SEQUENCE SEQ_COLLECT_LOG
    START WITH 1
    INCREMENT BY 1;

-- 수집 실행 이력 테이블용 시퀀스
CREATE SEQUENCE SEQ_COLLECT_HISTORY
    START WITH 1
    INCREMENT BY 1;

-- 수집 제외 대상 테이블용 시퀀스
CREATE SEQUENCE SEQ_COLLECT_EXCLUDE
    START WITH 1
    INCREMENT BY 1;
```

---

## 4. 테이블 설계

### 4-1. TB_COLLECT_LOG (수집 로그 테이블)

**역할**: 레거시 서버에서 수집한 정규화 로그를 저장하는 핵심 테이블  
**INSERT 주체**: 로그 수집 서버  
**SELECT 주체**: 로그 분석 서버, Dashboard  

```sql
-- ============================================================
-- TB_COLLECT_LOG : 수집 로그 저장 테이블
-- ============================================================
-- [설계 의도]
--   - 레거시 서버에서 추출한 @@@...@@@ 정규화 로그 1건 = 1행 저장
--   - 분석 서버가 이 테이블을 읽어서 분석 수행
--   - Dashboard에서 날짜/서버/타입별 조회에 사용
--
-- [성능 고려]
--   - 일일 최대 500건이므로 대용량 처리보다 조회 편의성 우선
--   - 자주 조회되는 컬럼(수집일자, 서버구분, 입력타입)에 인덱스 추가
--   - 날짜 기준 조회가 많으므로 COLLECT_DATE를 인덱스 선두 컬럼으로 설정
-- ============================================================

CREATE TABLE TB_COLLECT_LOG (

    -- --------------------------------------------------------
    -- PK
    -- --------------------------------------------------------
    COLLECT_LOG_ID      NUMERIC(19, 0)      NOT NULL,
    -- 수집 로그 고유 ID (SEQUENCE로 자동 생성)
    -- NUMERIC(19,0) : PostgreSQL/Altibase 공통 사용 가능한 큰 정수 타입
    -- BIGINT 대신 NUMERIC 사용 (Altibase 호환)

    -- --------------------------------------------------------
    -- 수집 대상 서버 정보
    -- --------------------------------------------------------
    SERVER_ID           VARCHAR(100)        NOT NULL,
    -- 서버 구분명 (스케줄 파일의 [서버구분] 필드값)
    -- 예) "dlprem01-테스트개발", "서버자동주문"
    -- 스케줄 정의서의 서버구분과 동일한 값 사용

    SERVER_IP           VARCHAR(15)         NOT NULL,
    -- 대상 서버 IP 주소
    -- 예) "192.168.210.121"
    -- IPv4 기준 최대 15자 (xxx.xxx.xxx.xxx)

    SOURCE_FILE_PATH    VARCHAR(500)        NOT NULL,
    -- 수집한 원본 로그 파일의 절대경로
    -- 예) "/tmp/check.out", "/var/log/app/service.log"
    -- 스케줄 정의서의 [대상파일명] 필드값

    -- --------------------------------------------------------
    -- 로그 내용 (정규화 로그 파싱 결과)
    -- --------------------------------------------------------
    LOG_TYPE            VARCHAR(10)         NOT NULL,
    -- 로그 입력 타입 (로그포맷정의서 기준)
    -- 허용값: '문구', '정보', '날짜', '수치', '존재', '비교', '시간'
    -- 분석 서버에서 이 값을 기준으로 분석 방법을 결정함

    LOG_ID              VARCHAR(30)         NOT NULL,
    -- 로그 식별 코드 (로그포맷정의서 기준)
    -- 형식: 영문 대/소문자 + 숫자 + 언더스코어, 최대 30자
    -- 예) 'DISK_HOME', 'PROC_COUNT', 'PUSH_STATUS'
    -- 분석 서버가 이 값을 기준으로 분석 정책을 매칭함
    -- [서버구분 + LOG_ID] 조합으로 분석 정책이 유일하게 결정됨

    LOG_TIMESTAMP       TIMESTAMP           NOT NULL,
    -- 정규화 로그 내부의 timestamp 값
    -- 원본 형식: yyyy/MM/dd HH:mm:ss.SSS → TIMESTAMP로 파싱하여 저장
    -- 예) @@@[2026/04/21 12:12:32.123]... 에서 추출한 시각

    LOG_CONTENT         VARCHAR(4000)       NOT NULL,
    -- 로그의 내용 텍스트 (|내용| 부분)
    -- 예) "PUSH 전송 실패(PUSH 서버 실패 O)"
    -- 예) "home디스크"
    -- 예) "bday[2026/04/22] frec[12607] krec[3] fpos[07393]"
    -- 4000자 제한: Altibase VARCHAR 최대 권장 크기

    LOG_VALUE           NUMERIC(18, 6),
    -- 수치형 로그의 숫자값 ($숫자$ 부분)
    -- 수치 타입일 때만 값이 있고, 나머지 타입은 NULL
    -- 예) $80$ → 80.000000, $51769$ → 51769.000000
    -- NUMERIC(18,6): 정수 18자리, 소수점 6자리까지 표현 가능
    -- 실수값도 저장 가능하도록 소수점 자리 확보

    RAW_LOG             VARCHAR(4000)       NOT NULL,
    -- 정규화 로그 원문 전체 (@@@...@@@ 포함)
    -- 예) "@@@[2026/04/21 12:12:32.123][수치]|home디스크|$80$@@@"
    -- 파싱 오류 발생 시 재처리, 감사 목적으로 원문 보관
    -- 분석 서버에서 이슈 발생 시 원문 확인 가능

    -- --------------------------------------------------------
    -- 수집 메타 정보
    -- --------------------------------------------------------
    COLLECT_DATE        VARCHAR(8)          NOT NULL,
    -- 수집 실행 날짜 (yyyyMMdd 형식의 문자열)
    -- 예) "20260421"
    -- 왜 VARCHAR? → Dashboard/분석서버에서 날짜 범위 조회 시
    --   TIMESTAMP보다 단순 문자열 비교가 직관적이고 인덱스 효율이 좋음
    -- 또한 '오늘 수집된 로그 전체'를 빠르게 조회하는 주요 키로 활용됨

    COLLECT_DATETIME    TIMESTAMP           NOT NULL,
    -- 수집 서버가 실제로 이 행을 INSERT한 정확한 일시
    -- LOG_TIMESTAMP(로그 내부 시각)와 다를 수 있음
    -- 장애 분석, 수집 지연 파악 시 활용

    SCHEDULE_TYPE       VARCHAR(10)         NOT NULL,
    -- 이 로그를 수집한 스케줄 방식
    -- 허용값: '배치', '주기'
    -- 배치: 해당 시각에 1회 수집
    -- 주기: 정해진 간격으로 반복 수집

    LINE_NUMBER         NUMERIC(10, 0),
    -- 원본 파일에서 이 로그가 있던 라인 번호
    -- 주기 수집에서 '마지막으로 읽은 라인번호' 관리에 활용
    -- 다음 주기 수집 시 이 번호 이후부터만 읽어서 중복 수집 방지
    -- 배치 수집은 전체를 읽으므로 의미 없을 수 있으나 기록은 남김

    -- --------------------------------------------------------
    -- 공통 관리 컬럼
    -- --------------------------------------------------------
    CREATED_AT          TIMESTAMP           NOT NULL,
    -- 행 생성 일시 (INSERT 시 애플리케이션에서 현재 시각 입력)
    -- COLLECT_DATETIME과 동일하게 설정해도 무방
    -- 향후 DB 기본값 설정 가능하면 DEFAULT CURRENT_TIMESTAMP 추가

    -- --------------------------------------------------------
    -- 제약 조건
    -- --------------------------------------------------------
    CONSTRAINT PK_COLLECT_LOG PRIMARY KEY (COLLECT_LOG_ID),
    -- PK: SEQUENCE 값으로 유일성 보장

    CONSTRAINT CK_LOG_TYPE CHECK (
        LOG_TYPE IN ('문구', '정보', '날짜', '수치', '존재', '비교', '시간')
    ),
    -- 로그포맷정의서에 정의된 5가지 타입만 허용
    -- 이 외의 값이 들어오면 DB 레벨에서 차단

    CONSTRAINT CK_SCHEDULE_TYPE CHECK (
        SCHEDULE_TYPE IN ('배치', '주기')
    )
    -- 스케줄 정의서에 정의된 2가지 방식만 허용

);

-- ============================================================
-- TB_COLLECT_LOG 인덱스
-- ============================================================

-- [인덱스 설계 원칙]
--   분석 서버와 Dashboard의 주요 조회 패턴을 기준으로 설계
--   일일 500건 미만이므로 인덱스 과잉은 오히려 INSERT 부담
--   꼭 필요한 인덱스만 최소한으로 생성

-- IDX_CL_01: 날짜 + 서버 + 타입 + LOG_ID 복합 인덱스
-- 사용 케이스: "오늘 특정 서버의 특정 LOG_ID 로그 전체 조회" (분석 서버 주요 패턴)
-- 분석 서버는 LOG_ID 단위로 분석 정책을 매칭하므로 LOG_ID 포함
-- 선두 컬럼을 COLLECT_DATE로 설정 → 날짜 범위 조회 시 인덱스 풀스캔 방지
CREATE INDEX IDX_CL_01 ON TB_COLLECT_LOG (
    COLLECT_DATE,       -- 선두: 날짜별 조회가 가장 빈번
    SERVER_ID,          -- 두번째: 서버별 필터링
    LOG_TYPE,           -- 세번째: 타입별 필터링
    LOG_ID              -- 네번째: 분석 정책 매칭용
);

-- IDX_CL_02: 날짜 + 로그 타임스탬프 인덱스
-- 사용 케이스: "특정 날짜의 시간순 로그 정렬 조회" (Dashboard 히스토리 화면)
CREATE INDEX IDX_CL_02 ON TB_COLLECT_LOG (
    COLLECT_DATE,
    LOG_TIMESTAMP
);

-- IDX_CL_03: 서버 + 날짜 인덱스
-- 사용 케이스: "특정 서버의 최근 N일 로그 조회" (Dashboard 서버별 조회)
CREATE INDEX IDX_CL_03 ON TB_COLLECT_LOG (
    SERVER_ID,
    COLLECT_DATE
);
```

---

### 4-2. TB_COLLECT_HISTORY (수집 실행 이력 테이블)

**역할**: 수집 서버가 각 스케줄을 실행한 이력을 기록하는 테이블  
**INSERT/UPDATE 주체**: 로그 수집 서버  
**SELECT 주체**: 로그 수집 서버 (주기 수집 시 마지막 라인번호 조회), Dashboard  

```sql
-- ============================================================
-- TB_COLLECT_HISTORY : 수집 실행 이력 테이블
-- ============================================================
-- [설계 의도]
--   - 각 스케줄 실행 결과(성공/실패/제외)를 기록
--   - 주기 수집 시 "마지막으로 읽은 라인번호"를 여기서 조회함
--     → 다음 수집 시 이 번호 이후부터만 읽어서 중복 수집 방지
--   - 재시도 횟수, 실패 사유도 기록하여 운영자 모니터링 지원
--   - 수집 서버 재시작 후에도 마지막 수집 위치를 DB에서 복구 가능
--
-- [성능 고려]
--   - 수집 서버가 스케줄 실행 전에 반드시 조회하는 테이블
--   - SERVER_ID + SOURCE_FILE_PATH 기준으로 최신 이력을 빠르게 찾아야 함
--   - 해당 복합 컬럼에 인덱스 설정
-- ============================================================

CREATE TABLE TB_COLLECT_HISTORY (

    -- --------------------------------------------------------
    -- PK
    -- --------------------------------------------------------
    COLLECT_HISTORY_ID  NUMERIC(19, 0)      NOT NULL,
    -- 수집 이력 고유 ID (SEQUENCE로 자동 생성)

    -- --------------------------------------------------------
    -- 수집 대상 식별 정보
    -- --------------------------------------------------------
    SERVER_ID           VARCHAR(100)        NOT NULL,
    -- 수집 대상 서버 구분명 (스케줄 파일의 [서버구분] 필드값)

    SERVER_IP           VARCHAR(15)         NOT NULL,
    -- 수집 대상 서버 IP

    SOURCE_FILE_PATH    VARCHAR(500)        NOT NULL,
    -- 수집한 원본 로그 파일 절대경로

    SCHEDULE_TYPE       VARCHAR(10)         NOT NULL,
    -- 수집 방식: '배치' 또는 '주기'

    -- --------------------------------------------------------
    -- 수집 실행 결과
    -- --------------------------------------------------------
    COLLECT_STATUS      VARCHAR(10)         NOT NULL,
    -- 수집 실행 결과 상태
    -- 허용값:
    --   'SUCCESS' : 수집 성공 (정규화 로그 추출 및 DB 저장 완료)
    --   'FAIL'    : 수집 실패 (재시도 3회 모두 실패)
    --   'SKIP'    : 수집 제외 (파일 사이즈 초과 → 영구 제외 처리됨)

    COLLECTED_COUNT     NUMERIC(10, 0),
    -- 이번 수집에서 추출하여 DB에 저장한 정규화 로그 건수
    -- 성공 시에만 값 있음, 실패/제외 시 NULL 또는 0

    LAST_LINE_NUMBER    NUMERIC(10, 0),
    -- 이번 수집에서 마지막으로 읽은 파일 라인번호
    -- 주기 수집에서 핵심 역할:
    --   다음 수집 시 이 번호 +1 라인부터 읽기 시작
    --   배치 수집은 전체를 읽으므로 실제 마지막 라인번호를 기록

    FILE_SIZE_BYTES     NUMERIC(15, 0),
    -- 수집 시점의 원본 파일 크기 (bytes 단위)
    -- 300MB(배치), 50MB(주기) 초과 여부 판단에 활용
    -- 운영자가 파일 증가 추이 파악 시 참고 가능

    RETRY_COUNT         NUMERIC(2, 0)       DEFAULT 0,
    -- 수집 재시도 횟수 (최초 시도 불포함)
    -- 0: 최초 시도 성공, 1~3: 재시도 후 성공 또는 실패
    -- 명세서 기준: 최초 실패 후 10초 간격 재시도 최대 3회

    FAIL_REASON         VARCHAR(1000),
    -- 수집 실패 사유 (FAIL 상태일 때 기록)
    -- 예) "Connection refused: 192.168.210.121:22"
    -- 예) "File size exceeded: 312MB (limit: 300MB)"
    -- 운영자가 수동 조치 시 참고

    -- --------------------------------------------------------
    -- 수집 실행 시각
    -- --------------------------------------------------------
    COLLECT_START_AT    TIMESTAMP           NOT NULL,
    -- 수집 시작 일시 (최초 시도 시작 시각)

    COLLECT_END_AT      TIMESTAMP,
    -- 수집 종료 일시 (성공 또는 최종 실패 확정 시각)
    -- 수집 진행 중일 때는 NULL

    COLLECT_DATE        VARCHAR(8)          NOT NULL,
    -- 수집 실행 날짜 (yyyyMMdd)
    -- TB_COLLECT_LOG.COLLECT_DATE와 동일한 형식 사용

    -- --------------------------------------------------------
    -- 공통 관리 컬럼
    -- --------------------------------------------------------
    CREATED_AT          TIMESTAMP           NOT NULL,
    -- 행 생성 일시

    UPDATED_AT          TIMESTAMP,
    -- 행 수정 일시 (재시도 후 상태 업데이트 시 갱신)

    -- --------------------------------------------------------
    -- 제약 조건
    -- --------------------------------------------------------
    CONSTRAINT PK_COLLECT_HISTORY PRIMARY KEY (COLLECT_HISTORY_ID),

    CONSTRAINT CK_COLLECT_STATUS CHECK (
        COLLECT_STATUS IN ('SUCCESS', 'FAIL', 'SKIP')
    ),
    -- 허용된 상태값만 저장 가능

    CONSTRAINT CK_CH_SCHEDULE_TYPE CHECK (
        SCHEDULE_TYPE IN ('배치', '주기')
    )

);

-- ============================================================
-- TB_COLLECT_HISTORY 인덱스
-- ============================================================

-- IDX_CH_01: 서버 + 파일경로 + 날짜 복합 인덱스
-- 사용 케이스: "특정 서버/파일의 가장 최근 수집 이력 조회"
--   → 주기 수집 시 LAST_LINE_NUMBER를 가져오기 위한 핵심 조회
--   → SELECT ... WHERE SERVER_ID=? AND SOURCE_FILE_PATH=? ORDER BY COLLECT_START_AT DESC LIMIT 1
CREATE INDEX IDX_CH_01 ON TB_COLLECT_HISTORY (
    SERVER_ID,
    SOURCE_FILE_PATH,
    COLLECT_START_AT DESC    -- 최신 이력을 빠르게 찾기 위해 내림차순
);

-- IDX_CH_02: 날짜 + 상태 인덱스
-- 사용 케이스: "오늘 실패한 수집 이력 전체 조회" (운영자 모니터링)
CREATE INDEX IDX_CH_02 ON TB_COLLECT_HISTORY (
    COLLECT_DATE,
    COLLECT_STATUS
);
```

---

### 4-3. TB_COLLECT_EXCLUDE (수집 영구 제외 대상 테이블)

**역할**: 파일 사이즈 초과로 영구 제외된 수집 대상을 관리하는 테이블  
**INSERT 주체**: 로그 수집 서버 (사이즈 초과 감지 시)  
**SELECT 주체**: 로그 수집 서버 (수집 실행 전 제외 대상 여부 확인)  

```sql
-- ============================================================
-- TB_COLLECT_EXCLUDE : 수집 영구 제외 대상 테이블
-- ============================================================
-- [설계 의도]
--   - 명세서 기준:
--     "최초 수집 사이즈 300MB 이상 → 수집 불가, 영구 제외"
--     "부분 수집 사이즈 50MB 이상 → 수집 불가, 영구 제외"
--   - 수집 서버는 스케줄 실행 전 이 테이블을 조회하여
--     제외 대상이면 수집 시도 자체를 하지 않음
--   - 운영자가 수동 조치 완료 후 RESTORE_YN = 'Y'로 변경하면
--     수집 서버가 다시 수집을 시도할 수 있음 (복구 프로세스)
--
-- [성능 고려]
--   - 건수가 매우 적을 것으로 예상 (영구 제외는 예외적 상황)
--   - 수집 서버가 스케줄마다 조회하므로 인덱스 설정
-- ============================================================

CREATE TABLE TB_COLLECT_EXCLUDE (

    -- --------------------------------------------------------
    -- PK
    -- --------------------------------------------------------
    COLLECT_EXCLUDE_ID  NUMERIC(19, 0)      NOT NULL,
    -- 제외 대상 고유 ID (SEQUENCE로 자동 생성)

    -- --------------------------------------------------------
    -- 제외 대상 식별 정보
    -- --------------------------------------------------------
    SERVER_ID           VARCHAR(100)        NOT NULL,
    -- 제외된 서버 구분명

    SERVER_IP           VARCHAR(15)         NOT NULL,
    -- 제외된 서버 IP

    SOURCE_FILE_PATH    VARCHAR(500)        NOT NULL,
    -- 제외된 파일 절대경로

    -- --------------------------------------------------------
    -- 제외 사유
    -- --------------------------------------------------------
    EXCLUDE_REASON      VARCHAR(10)         NOT NULL,
    -- 제외 사유 구분
    -- 허용값:
    --   'INIT_SIZE' : 최초 수집 시 파일 크기 300MB 초과
    --   'PART_SIZE' : 주기 수집 시 증분 크기 50MB 초과

    EXCLUDE_FILE_SIZE   NUMERIC(15, 0)      NOT NULL,
    -- 제외 처리 당시의 파일 크기 (bytes)
    -- 운영자가 실제 파일 크기 확인 시 참고

    EXCLUDE_DETAIL      VARCHAR(1000),
    -- 제외 상세 내용 (운영자 확인용 메모)
    -- 예) "최초 수집 시 파일 크기 314572800 bytes (300MB 초과)"

    -- --------------------------------------------------------
    -- 복구 관리
    -- --------------------------------------------------------
    RESTORE_YN          CHAR(1)             NOT NULL DEFAULT 'N',
    -- 운영자 수동 조치 후 복구 여부
    -- 'N': 아직 제외 상태 (수집 시도 안함)
    -- 'Y': 운영자 조치 완료 → 수집 서버가 다시 시도 가능
    -- 수집 서버는 RESTORE_YN = 'N' 인 항목만 제외 처리

    RESTORE_AT          TIMESTAMP,
    -- 복구 처리 일시 (RESTORE_YN을 'Y'로 변경한 시각)

    RESTORE_MEMO        VARCHAR(500),
    -- 복구 처리 내용 메모 (운영자 직접 입력)
    -- 예) "파일 사이즈 이슈 해결, 레거시팀 로그 설정 수정 완료"

    -- --------------------------------------------------------
    -- 수집 메타 정보
    -- --------------------------------------------------------
    EXCLUDE_DATE        VARCHAR(8)          NOT NULL,
    -- 제외 처리 날짜 (yyyyMMdd)

    CREATED_AT          TIMESTAMP           NOT NULL,
    -- 행 생성 일시 (제외 처리 시각)

    UPDATED_AT          TIMESTAMP,
    -- 행 수정 일시 (RESTORE_YN 변경 시 갱신)

    -- --------------------------------------------------------
    -- 제약 조건
    -- --------------------------------------------------------
    CONSTRAINT PK_COLLECT_EXCLUDE PRIMARY KEY (COLLECT_EXCLUDE_ID),

    CONSTRAINT CK_EXCLUDE_REASON CHECK (
        EXCLUDE_REASON IN ('INIT_SIZE', 'PART_SIZE')
    ),

    CONSTRAINT CK_RESTORE_YN CHECK (
        RESTORE_YN IN ('Y', 'N')
    ),

    -- 동일한 서버+파일에 대해 중복 제외 방지 (복구 후 재제외는 가능해야 하므로 RESTORE_YN 포함)
    CONSTRAINT UQ_EXCLUDE_TARGET UNIQUE (
        SERVER_ID,
        SOURCE_FILE_PATH,
        RESTORE_YN
    )

);

-- ============================================================
-- TB_COLLECT_EXCLUDE 인덱스
-- ============================================================

-- IDX_CE_01: 서버 + 파일경로 + 복구여부 인덱스
-- 사용 케이스: "수집 전 이 파일이 제외 대상인지 확인"
--   → SELECT COUNT(*) WHERE SERVER_ID=? AND SOURCE_FILE_PATH=? AND RESTORE_YN='N'
CREATE INDEX IDX_CE_01 ON TB_COLLECT_EXCLUDE (
    SERVER_ID,
    SOURCE_FILE_PATH,
    RESTORE_YN
);
```

---

## 5. 테이블 관계 정리

```
TB_COLLECT_HISTORY (수집 실행 이력)
    ↓ 수집 성공 시 로그 저장
TB_COLLECT_LOG (수집 로그 저장)

TB_COLLECT_EXCLUDE (영구 제외 대상)
    ← 수집 서버가 수집 전 여기를 먼저 조회
    ← RESTORE_YN='N' 이면 수집 시도 안함
```

> 💡 **테이블 간 외래키(FK)를 설정하지 않은 이유**  
> 수집 서버는 빠른 INSERT가 중요해.  
> FK 제약이 있으면 INSERT 시마다 부모 테이블 조회가 발생해서 부담이 생겨.  
> 일일 500건 수준이라 FK 없이 애플리케이션 레벨에서 정합성을 관리하는 게 더 단순하고 효율적이야.

---

## 6. 수집 서버 주요 쿼리 패턴

### 6-1. 수집 전 제외 대상 여부 확인

```sql
-- 수집 시도 전 반드시 이 쿼리를 먼저 실행
-- RESTORE_YN = 'N' → 제외 대상 → 수집 건너뜀
SELECT COUNT(*)
FROM TB_COLLECT_EXCLUDE
WHERE SERVER_ID = ?
  AND SOURCE_FILE_PATH = ?
  AND RESTORE_YN = 'N';
```

### 6-2. 주기 수집 시 마지막 라인번호 조회

```sql
-- 주기 수집에서 핵심 쿼리
-- 이전 성공 수집의 마지막 라인번호를 가져와서
-- 그 다음 라인부터 읽기 시작함
SELECT LAST_LINE_NUMBER
FROM TB_COLLECT_HISTORY
WHERE SERVER_ID = ?
  AND SOURCE_FILE_PATH = ?
  AND COLLECT_STATUS = 'SUCCESS'
ORDER BY COLLECT_START_AT DESC
-- LIMIT 1 → PostgreSQL 문법
-- Altibase에서는 → LIMIT 1 동일하게 지원하거나
--                  ROWNUM = 1 방식 사용 (버전 확인 필요)
LIMIT 1;
```

### 6-3. 수집 로그 INSERT

```sql
-- 정규화 로그 1건 INSERT
-- SEQUENCE 값은 애플리케이션에서 nextval로 미리 조회 후 사용
INSERT INTO TB_COLLECT_LOG (
    COLLECT_LOG_ID,
    SERVER_ID,
    SERVER_IP,
    SOURCE_FILE_PATH,
    LOG_TYPE,
    LOG_ID,
    LOG_TIMESTAMP,
    LOG_CONTENT,
    LOG_VALUE,
    RAW_LOG,
    COLLECT_DATE,
    COLLECT_DATETIME,
    SCHEDULE_TYPE,
    LINE_NUMBER,
    CREATED_AT
) VALUES (
    ?,  -- SEQ_COLLECT_LOG.NEXTVAL
    ?,  -- SERVER_ID
    ?,  -- SERVER_IP
    ?,  -- SOURCE_FILE_PATH
    ?,  -- LOG_TYPE
    ?,  -- LOG_ID (예: 'DISK_HOME')
    ?,  -- LOG_TIMESTAMP (파싱된 TIMESTAMP)
    ?,  -- LOG_CONTENT
    ?,  -- LOG_VALUE (수치 타입이 아니면 NULL)
    ?,  -- RAW_LOG (원문 전체)
    ?,  -- COLLECT_DATE (yyyyMMdd)
    ?,  -- COLLECT_DATETIME (현재 시각)
    ?,  -- SCHEDULE_TYPE
    ?,  -- LINE_NUMBER
    ?   -- CREATED_AT (현재 시각)
);
```

### 6-4. 분석 서버용 조회 (참고)

```sql
-- 분석 서버가 오늘 수집된 미분석 로그를 조회하는 패턴
-- IDX_CL_01 인덱스 활용
-- 분석 서버는 LOG_ID 기준으로 분석 정책을 매칭하므로 LOG_ID도 함께 조회
SELECT *
FROM TB_COLLECT_LOG
WHERE COLLECT_DATE = '20260421'
  AND SERVER_ID = ?
  AND LOG_TYPE = ?
ORDER BY LOG_TIMESTAMP ASC;
-- 또는 특정 LOG_ID 단위로 조회 가능
-- AND LOG_ID = ?
```

---

## 7. Spring Boot JPA 연동 시 고려사항

### 7-1. SEQUENCE 설정 예시 (Java Entity)

```java
// 수집 로그 엔티티 PK 생성 예시
// PostgreSQL과 Altibase 모두 SEQUENCE 방식 지원
@Id
@GeneratedValue(
    strategy = GenerationType.SEQUENCE,
    generator = "collectLogSeq"
)
@SequenceGenerator(
    name = "collectLogSeq",
    sequenceName = "SEQ_COLLECT_LOG",  // DB에 생성한 시퀀스명
    allocationSize = 1                  // 캐시 없이 1씩 증가 (NOCACHE와 동일)
)
private Long collectLogId;
```

### 7-2. application.properties 환경별 설정

```properties
# ============================
# 테스트 환경 (PostgreSQL)
# ============================
spring.datasource.url=jdbc:postgresql://localhost:5432/precheck
spring.datasource.driver-class-name=org.postgresql.Driver
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect

# ============================
# 운영 환경 (Altibase)
# ============================
spring.datasource.url=jdbc:Altibase://192.168.x.x:20300/precheck
spring.datasource.driver-class-name=Altibase.jdbc.driver.AltibaseDriver
spring.jpa.database-platform=com.altibase.hibernate.dialect.AltibaseDialect
```

> 💡 **profiles 활용 추천**  
> `application-test.properties` / `application-prod.properties` 로 분리하고  
> 실행 시 `-Dspring.profiles.active=test` 또는 `prod` 로 전환하면  
> 코드 변경 없이 DB를 전환할 수 있어.

---

## 8. 데이터 보관 정책

| 항목 | 정책 |
|---|---|
| TB_COLLECT_LOG | DB 별도 보관 정책 따름 (수동 삭제 예정) |
| TB_COLLECT_HISTORY | DB 별도 보관 정책 따름 |
| TB_COLLECT_EXCLUDE | 복구 완료(RESTORE_YN='Y') 후에도 이력 보존 |
| 원본 로그 파일 | 수집 후 10일 보관 (수집 서버 로컬) |

---

## 9. DDL 전체 실행 순서 (요약)

```sql
-- Step 1: SEQUENCE 생성
CREATE SEQUENCE SEQ_COLLECT_LOG ...
CREATE SEQUENCE SEQ_COLLECT_HISTORY ...
CREATE SEQUENCE SEQ_COLLECT_EXCLUDE ...

-- Step 2: 테이블 생성 (순서 무관, FK 없으므로)
CREATE TABLE TB_COLLECT_LOG ...
CREATE TABLE TB_COLLECT_HISTORY ...
CREATE TABLE TB_COLLECT_EXCLUDE ...

-- Step 3: 인덱스 생성
CREATE INDEX IDX_CL_01 ON TB_COLLECT_LOG ...
CREATE INDEX IDX_CL_02 ON TB_COLLECT_LOG ...
CREATE INDEX IDX_CL_03 ON TB_COLLECT_LOG ...
CREATE INDEX IDX_CH_01 ON TB_COLLECT_HISTORY ...
CREATE INDEX IDX_CH_02 ON TB_COLLECT_HISTORY ...
CREATE INDEX IDX_CE_01 ON TB_COLLECT_EXCLUDE ...
```
