package com.sks.precheck.collect.common.util;

import com.sks.precheck.collect.common.constants.CollectConstants;
import com.sks.precheck.collect.common.exception.CollectException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 수집 서버에서 사용하는 날짜/시간 파싱 및 포맷 변환 유틸리티.
 */
public final class DateUtil {

    private static final DateTimeFormatter LOG_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern(CollectConstants.LOG_TIMESTAMP_FORMAT);

    private static final DateTimeFormatter COLLECT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern(CollectConstants.COLLECT_DATE_FORMAT);

    private DateUtil() {
    }

    /**
     * 정규화 로그의 timestamp 문자열을 LocalDateTime으로 파싱한다.
     *
     * @param timestampText 로그 timestamp 문자열 (yyyy/MM/dd HH:mm:ss.SSS)
     * @return 파싱된 LocalDateTime
     * @throws CollectException timestamp 형식이 올바르지 않은 경우
     */
    public static LocalDateTime parseLogTimestamp(String timestampText) {
        try {
            return LocalDateTime.parse(timestampText, LOG_TIMESTAMP_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new CollectException("로그 timestamp 파싱 실패: " + timestampText, e);
        }
    }

    /**
     * 수집 실행 날짜를 yyyyMMdd 문자열로 포맷한다.
     *
     * @param date LocalDate
     * @return yyyyMMdd 형식 문자열
     */
    public static String formatCollectDate(LocalDate date) {
        return date.format(COLLECT_DATE_FORMATTER);
    }

    /**
     * 오늘 날짜를 yyyyMMdd 문자열로 반환한다.
     *
     * @return yyyyMMdd 형식 문자열
     */
    public static String todayCollectDate() {
        return formatCollectDate(LocalDate.now());
    }

    /**
     * 수집 대상 파일 경로 안의 날짜 자리표시자를 수집 날짜로 치환한다.
     *
     * 지원하는 자리표시자:
     *   - {@value CollectConstants#FILE_PATH_DATE_PLACEHOLDER} (yyyymmdd) → 수집 날짜 전체(yyyyMMdd)
     *   - {@value CollectConstants#FILE_PATH_DATE_PLACEHOLDER_MMDD} (mmdd) → 수집 날짜의 월/일(MMdd)
     *   - {@value CollectConstants#FILE_PATH_DATE_PLACEHOLDER_DOW} ($) → 수집 날짜의 요일 숫자(0=일요일 ~ 6=토요일)
     *
     * "yyyymmdd"는 "mmdd"를 부분 문자열로 포함하므로, yyyymmdd를 먼저 치환한 뒤
     * 남은 mmdd를 치환해야 yyyymmdd 치환 결과가 잘못 덮어써지지 않는다.
     *
     * 예) "/logs/test.yyyymmdd" + "20260612" → "/logs/test.20260612"
     * 예) "/logs/test.mmdd" + "20260612" → "/logs/test.0612"
     * 예) "sys0$.log" + "20260615"(월요일) → "sys01.log"
     * 자리표시자가 없으면 원본 경로를 그대로 반환한다.
     *
     * @param filePathTemplate 스케줄에 정의된 파일 경로 (자리표시자 포함 가능)
     * @param collectDate 수집 날짜 (yyyyMMdd)
     * @return 날짜가 치환된 실제 파일 경로
     */
    public static String resolveFilePath(String filePathTemplate, String collectDate) {
        String resolved = filePathTemplate.replace(CollectConstants.FILE_PATH_DATE_PLACEHOLDER, collectDate);
        String monthDay = collectDate.substring(4);
        resolved = resolved.replace(CollectConstants.FILE_PATH_DATE_PLACEHOLDER_MMDD, monthDay);
        String dayOfWeekDigit = String.valueOf(dayOfWeekDigit(collectDate));
        return resolved.replace(CollectConstants.FILE_PATH_DATE_PLACEHOLDER_DOW, dayOfWeekDigit);
    }

    /**
     * 수집 날짜(yyyyMMdd)의 요일을 숫자로 변환한다.
     *
     * CollectScheduler의 요일 스케줄 표현식(0=일요일 ~ 6=토요일)과 동일한 기준이다.
     * DayOfWeek.getValue()는 월요일=1 ~ 일요일=7이므로 %7 연산으로 일요일을 0으로 맞춘다.
     *
     * @param collectDate 수집 날짜 (yyyyMMdd)
     * @return 요일 숫자 (0=일요일, 1=월요일, ..., 6=토요일)
     */
    private static int dayOfWeekDigit(String collectDate) {
        LocalDate date = LocalDate.parse(collectDate, COLLECT_DATE_FORMATTER);
        return date.getDayOfWeek().getValue() % 7;
    }

    /**
     * 스케줄에 정의된 파일 경로 원본(날짜 미치환, '+' 접미사 포함 가능)을
     * 실제 수집에 사용하는 경로(날짜 치환 완료, 접미사 제거)로 변환한다.
     *
     * TB_COLLECT_HISTORY.SOURCE_FILE_PATH와 TB_COLLECT_LOG.SOURCE_FILE_PATH는
     * 항상 이 메서드의 반환값으로 저장해야 한다. 한쪽만 원본 템플릿을 저장하면
     * findLastLineNumber 조회 시 경로가 일치하지 않아 증분 수집 기준점을 찾지 못하고
     * 매번 파일을 처음부터 재수집하게 된다.
     *
     * @param filePathTemplate 스케줄에 정의된 원본 경로 (날짜 자리표시자, '+' 접미사 포함 가능)
     * @param collectDate 수집 날짜 (yyyyMMdd)
     * @return 실제 수집/조회에 사용할 파일 경로
     */
    public static String resolveActualFilePath(String filePathTemplate, String collectDate) {
        String template = filePathTemplate.endsWith(CollectConstants.FILE_PATH_NO_DATE_RESET_SUFFIX)
                ? filePathTemplate.substring(
                        0, filePathTemplate.length() - CollectConstants.FILE_PATH_NO_DATE_RESET_SUFFIX.length())
                : filePathTemplate;
        return resolveFilePath(template, collectDate);
    }
}
