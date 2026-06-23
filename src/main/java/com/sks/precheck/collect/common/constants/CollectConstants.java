package com.sks.precheck.collect.common.constants;

/**
 * 로그 수집 서버 공통 상수.
 *
 * 파일 크기 제한, 재시도 정책, 날짜 포맷, 로그 타입/상태, Y/N 값, 제외 사유 등을 정의한다.
 */
public final class CollectConstants {

    public static final long INIT_COLLECT_SIZE_LIMIT_BYTES = 300L * 1024 * 1024;
    public static final long PART_COLLECT_SIZE_LIMIT_BYTES = 50L * 1024 * 1024;

    public static final int MAX_RETRY_COUNT = 3;
    public static final long RETRY_DELAY_MILLISECONDS = 10_000L;

    public static final String LOG_TIMESTAMP_FORMAT = "yyyy/MM/dd HH:mm:ss.SSS";
    public static final String COLLECT_DATE_FORMAT = "yyyyMMdd";

    /** 수집 대상 파일 경로에서 당일 날짜(yyyyMMdd)로 치환되는 자리표시자 */
    public static final String FILE_PATH_DATE_PLACEHOLDER = "yyyymmdd";

    /** 수집 대상 파일 경로에서 당일 월/일(MMdd)로 치환되는 자리표시자 */
    public static final String FILE_PATH_DATE_PLACEHOLDER_MMDD = "mmdd";

    /** 수집 대상 파일 경로에서 당일 요일 숫자(0=일요일 ~ 6=토요일)로 치환되는 자리표시자 */
    public static final String FILE_PATH_DATE_PLACEHOLDER_DOW = "$";

    /** 수집 대상 파일 경로 끝에 붙으면 날짜 변경에 따른 라인번호 리셋을 비활성화하는 접미사 */
    public static final String FILE_PATH_NO_DATE_RESET_SUFFIX = "+";

    public static final String LOG_TYPE_TEXT = "문구";
    public static final String LOG_TYPE_INFO = "정보";
    public static final String LOG_TYPE_DATE = "날짜";
    public static final String LOG_TYPE_NUMERIC = "수치";
    public static final String LOG_TYPE_EXIST = "존재";
    public static final String LOG_TYPE_COMPARE = "비교";
    public static final String LOG_TYPE_TIME = "시간";

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAIL = "FAIL";
    public static final String STATUS_SKIP = "SKIP";

    public static final String YN_YES = "Y";
    public static final String YN_NO = "N";

    public static final String EXCLUDE_REASON_INIT_SIZE = "INIT_SIZE";
    public static final String EXCLUDE_REASON_PART_SIZE = "PART_SIZE";

    private CollectConstants() {
    }
}

