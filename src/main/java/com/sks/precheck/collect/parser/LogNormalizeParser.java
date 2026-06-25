package com.sks.precheck.collect.parser;

import com.sks.precheck.collect.common.constants.CollectConstants;
import com.sks.precheck.collect.common.util.DateUtil;
import com.sks.precheck.collect.domain.CollectLog;
import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 정규화 로그 형식(@@@...@@@)을 파싱하여 CollectLog 도메인 객체로 변환하는 파서.
 *
 * 정규화 로그 형식(로그포맷정의서 v1.1):
 *   @@@[yyyy/MM/dd HH:mm:ss.SSS][입력타입][LOG_ID]|로그내용|...@@@
 *
 * 값 토큰 규칙:
 *   - 수치/비교/시간/날짜는 $...$ 값 토큰을 사용한다.
 *   - $...$ 안에 콜론(:)이 포함되면 시간값(HH:mm)으로 간주한다.
 *   - $...$ 안이 yyyy/MM/dd 또는 yyyy-MM-dd 형식이면 날짜값으로 간주한다.
 *   - 그 외(콜론, 날짜 구분자 모두 없음)는 수치값(정수/실수)으로 간주한다.
 *
 * 입력 타입(CollectConstants 참조):
 *   문구, 정보, 날짜, 수치, 존재, 비교, 시간
 */
public class LogNormalizeParser {

    private static final Logger log = LogManager.getLogger(LogNormalizeParser.class);

    /**
     * 정규화 로그 헤더(@@@[timestamp][type][logId]) 파싱용 패턴.
     */
    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "^@@@\\[(?<timestamp>\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\]" +
                    "\\[(?<logType>[^\\]]+)\\]" +
                    "\\[(?<logId>[^\\]]+)\\]"
    );

    private static final Pattern LOG_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,30}$");

    private static final Pattern VALUE_TOKEN_PATTERN = Pattern.compile("\\$[^$]+\\$");
    private static final Pattern WRAPPED_VALUE_PATTERN = Pattern.compile("^\\$(?<value>[^$]+)\\$$");
    private static final Pattern DATE_VALUE_PATTERN = Pattern.compile("^\\d{4}(/|-)\\d{2}\\1\\d{2}$");
    private static final DateTimeFormatter DATE_VALUE_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu/MM/dd").withResolverStyle(ResolverStyle.STRICT);

    /**
     * 로컬 파일을 처음부터 끝까지 읽으며 정규화 로그를 추출한다.
     *
     * 테스트·단독 실행용 메서드이다. 운영 수집은 SftpService를 통해 원격 파일을 읽는다.
     * 파일 전체를 읽어 파싱된 CollectLog 목록을 반환한다.
     *
     * @param filePath 로컬 로그 파일 경로
     * @return 파싱된 CollectLog 목록 (정규화 로그가 없으면 빈 리스트)
     * @throws IOException 파일 읽기 실패 시
     */
    public List<CollectLog> parseFile(Path filePath) throws IOException {
        List<CollectLog> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            long lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                CollectLog collectLog = parseNormalizedLogFromLine(line, lineNumber);
                if (collectLog != null) {
                    result.add(collectLog);
                }
            }
        }
        return result;
    }

    /**
     * 로그 한 라인에서 정규화 로그(@@@...@@@)를 찾아 CollectLog로 변환한다.
     *
     * 처리 순서:
     *   1. @@@의 시작 위치를 찾는다. 없으면 null 반환.
     *   2. 종료 @@@의 위치를 찾는다. 없으면 무시(null 반환).
     *   3. 한 라인에 @@@가 3개 이상이면(로그 2건 이상) 무시.
     *   4. 추출한 rawLog 문자열을 정규식으로 파싱하여 각 필드를 추출한다.
     *   5. 로그 타입이 지원 목록에 있는지 검증한다.
     *   6. LOG_ID 형식을 검증한다.
     *   7. timestamp를 LocalDateTime으로 변환한다.
     *   8. NUMERIC 타입이면 수치 토큰을 BigDecimal로 파싱한다.
     *   9. CollectLog를 생성하여 반환한다.
     *
     * @param line       파일의 한 라인 문자열
     * @param lineNumber 파일 내 라인번호 (1-based, 로그·경고 메시지에 사용)
     * @return 파싱 성공 시 CollectLog, 정규화 로그가 없거나 무시 대상이면 null
     */
    public CollectLog parseNormalizedLogFromLine(String line, long lineNumber) {
        return parseNormalizedLogFromLine(line, lineNumber, null);
    }

    public CollectLog parseNormalizedLogFromLine(String line, long lineNumber, List<String> failDetails) {

        // ── Step 1. 빈 라인 및 @@@가 없는 라인 조기 탈출 ──────────────────────
        if (line == null || line.isEmpty()) {
            return null;
        }
        int start = line.indexOf("@@@");
        if (start < 0) {
            return null;
        }

        // ── Step 2. 종료 @@@ 위치 확인 ───────────────────────────────────────
        // start + 3 이후에서 검색하여 시작 @@@와 종료 @@@를 구분한다.
        int end = line.indexOf("@@@", start + 3);
        if (end < 0) {
            log.warn("정규화 로그 종료(@@@) 누락으로 무시 - lineNumber: {}", lineNumber);
            addFailDetail(failDetails, lineNumber, "정규화 로그 종료(@@@) 누락");
            return null;
        }

        // ── Step 3. 한 라인에 @@@가 3개 이상인 경우 무시 ────────────────────
        // 종료 @@@ 이후에 또 @@@가 있으면 로그가 2건 이상 섞인 비정상 라인이다.
        int afterEnd = end + 3;
        if (line.indexOf("@@@", afterEnd) >= 0) {
            log.warn("한 라인에 정규화 로그가 2건 이상으로 무시 - lineNumber: {}", lineNumber);
            addFailDetail(failDetails, lineNumber, "한 라인에 정규화 로그 2건 이상");
            return null;
        }

        // ── Step 4. rawLog 추출 및 정규식 파싱 ──────────────────────────────
        String rawLog = line.substring(start, afterEnd);
        if (!rawLog.startsWith("@@@") || !rawLog.endsWith("@@@")) {
            log.warn("정규화 로그 포맷 불일치로 무시 - lineNumber: {}, rawLog: {}", lineNumber, rawLog);
            addFailDetail(failDetails, lineNumber, "정규화 로그 포맷 불일치 - " + rawLog);
            return null;
        }

        String body = rawLog.substring(0, rawLog.length() - 3);
        Matcher headerMatcher = HEADER_PATTERN.matcher(body);
        if (!headerMatcher.find()) {
            log.warn("정규화 로그 헤더 포맷 불일치로 무시 - lineNumber: {}, rawLog: {}", lineNumber, rawLog);
            addFailDetail(failDetails, lineNumber, "정규화 로그 헤더 포맷 불일치 - " + rawLog);
            return null;
        }

        String timestampText = headerMatcher.group("timestamp");
        String logType = headerMatcher.group("logType");
        String logId = headerMatcher.group("logId");
        String remainder = body.substring(headerMatcher.end());

        if (!remainder.startsWith("|")) {
            log.warn("정규화 로그 내용 구분자(|) 누락으로 무시 - lineNumber: {}, rawLog: {}", lineNumber, rawLog);
            addFailDetail(failDetails, lineNumber, "정규화 로그 내용 구분자(|) 누락");
            return null;
        }

        int secondPipe = remainder.indexOf('|', 1);
        if (secondPipe < 0) {
            log.warn("정규화 로그 내용 종료 구분자(|) 누락으로 무시 - lineNumber: {}, rawLog: {}", lineNumber, rawLog);
            addFailDetail(failDetails, lineNumber, "정규화 로그 내용 종료 구분자(|) 누락");
            return null;
        }

        if (remainder.indexOf('|', secondPipe + 1) >= 0) {
            log.warn("정규화 로그 내용에 '|'가 3개 이상으로 무시 - lineNumber: {}, rawLog: {}", lineNumber, rawLog);
            addFailDetail(failDetails, lineNumber, "정규화 로그 내용 구분자(|) 과다");
            return null;
        }

        String contentPart = remainder.substring(1, secondPipe);
        String tailPart = remainder.substring(secondPipe + 1);

        List<String> valueTokens = new ArrayList<>();
        valueTokens.addAll(extractValueTokens(contentPart));
        valueTokens.addAll(extractValueTokens(tailPart));
        String tailNonTokenText = VALUE_TOKEN_PATTERN.matcher(tailPart).replaceAll("").trim();
        if (!tailNonTokenText.isEmpty()) {
            log.warn("정규화 로그 꼬리 영역에 비토큰 문자가 포함되어 무시 - lineNumber: {}, rawLog: {}", lineNumber, rawLog);
            addFailDetail(failDetails, lineNumber, "정규화 로그 꼬리 영역 포맷 불일치");
            return null;
        }

        // ── Step 5. 로그 타입 검증 ───────────────────────────────────────────
        if (!isSupportedLogType(logType)) {
            log.warn("정규화 로그 타입 불일치로 무시 - lineNumber: {}, logType: {}", lineNumber, logType);
            addFailDetail(failDetails, lineNumber, "지원하지 않는 로그 타입: " + logType);
            return null;
        }

        // ── Step 6. LOG_ID 형식 검증 ─────────────────────────────────────────
        if (!LOG_ID_PATTERN.matcher(logId).matches()) {
            log.warn("LOG_ID 형식 불일치로 무시 - lineNumber: {}, logId: {}", lineNumber, logId);
            addFailDetail(failDetails, lineNumber, "LOG_ID 형식 불일치: " + logId);
            return null;
        }

        // ── Step 7. timestamp 파싱 ───────────────────────────────────────────
        // DateUtil.parseLogTimestamp()가 파싱하며, 실패하면 RuntimeException을 던진다.
        LocalDateTime logTimestamp;
        try {
            logTimestamp = DateUtil.parseLogTimestamp(timestampText);
        } catch (RuntimeException e) {
            log.warn("timestamp 파싱 실패로 무시 - lineNumber: {}, timestamp: {}", lineNumber, timestampText);
            addFailDetail(failDetails, lineNumber, "timestamp 파싱 실패: " + timestampText);
            return null;
        }

        // ── Step 8. 타입별 값 토큰 검증/파싱 ──────────────────────────────────
        BigDecimal logValue = null;
        String logContent = contentPart;

        if (CollectConstants.LOG_TYPE_NUMERIC.equals(logType)) {
            if (valueTokens.size() != 1) {
                log.warn("수치형 로그 값 토큰 개수 오류로 무시 - lineNumber: {}, tokenCount: {}, rawLog: {}",
                        lineNumber, valueTokens.size(), rawLog);
                addFailDetail(failDetails, lineNumber, "수치형 로그 값 토큰 개수 오류: " + valueTokens.size());
                return null;
            }
            String token = valueTokens.get(0);
            if (isTimeToken(token)) {
                log.warn("수치형 로그에 시간 토큰이 포함되어 무시 - lineNumber: {}, token: {}, rawLog: {}",
                        lineNumber, token, rawLog);
                addFailDetail(failDetails, lineNumber, "수치형 로그에 시간 토큰 포함: " + token);
                return null;
            }
            logValue = parseNumericValue(token, lineNumber);
            if (logValue == null) {
                addFailDetail(failDetails, lineNumber, "수치형 로그 값 파싱 실패: " + token);
                return null;
            }
            logContent = normalizeContent(removeTokensFromContent(contentPart));
        } else if (CollectConstants.LOG_TYPE_COMPARE.equals(logType)) {
            if (valueTokens.size() != 2) {
                log.warn("비교형 로그 값 토큰 개수 오류로 무시 - lineNumber: {}, tokenCount: {}, rawLog: {}",
                        lineNumber, valueTokens.size(), rawLog);
                addFailDetail(failDetails, lineNumber, "비교형 로그 값 토큰 개수 오류: " + valueTokens.size());
                return null;
            }
            if (valueTokens.stream().anyMatch(this::isTimeToken)) {
                log.warn("비교형 로그에 시간 토큰이 포함되어 무시 - lineNumber: {}, rawLog: {}", lineNumber, rawLog);
                addFailDetail(failDetails, lineNumber, "비교형 로그에 시간 토큰 포함");
                return null;
            }
            logContent = contentPart;
        } else if (CollectConstants.LOG_TYPE_TIME.equals(logType)) {
            if (valueTokens.size() != 1) {
                log.warn("시간형 로그 값 토큰 개수 오류로 무시 - lineNumber: {}, tokenCount: {}, rawLog: {}",
                        lineNumber, valueTokens.size(), rawLog);
                addFailDetail(failDetails, lineNumber, "시간형 로그 값 토큰 개수 오류: " + valueTokens.size());
                return null;
            }
            String token = valueTokens.get(0);
            if (!isTimeToken(token)) {
                log.warn("시간형 로그에 수치 토큰이 포함되어 무시 - lineNumber: {}, token: {}, rawLog: {}",
                        lineNumber, token, rawLog);
                addFailDetail(failDetails, lineNumber, "시간형 로그에 수치 토큰 포함: " + token);
                return null;
            }
            Integer minutes = parseTimeMinutes(token, lineNumber);
            if (minutes == null) {
                addFailDetail(failDetails, lineNumber, "시간형 로그 값 파싱 실패: " + token);
                return null;
            }
            logValue = BigDecimal.valueOf(minutes);
            logContent = contentPart;
        } else if (CollectConstants.LOG_TYPE_DATE.equals(logType)) {
            if (valueTokens.isEmpty()) {
                log.warn("날짜형 로그에 날짜 값 토큰이 없어 무시 - lineNumber: {}, rawLog: {}", lineNumber, rawLog);
                addFailDetail(failDetails, lineNumber, "날짜형 로그에 날짜 값 토큰 없음");
                return null;
            }
            for (String token : valueTokens) {
                if (!isDateToken(token)) {
                    log.warn("날짜형 로그 값 토큰 포맷 불일치로 무시 - lineNumber: {}, token: {}, rawLog: {}",
                            lineNumber, token, rawLog);
                    addFailDetail(failDetails, lineNumber, "날짜형 로그 값 토큰 포맷 불일치: " + token);
                    return null;
                }
            }
            logContent = contentPart;
        } else {
            if (!valueTokens.isEmpty()) {
                log.warn("값 토큰이 포함된 비수치/비교/시간/날짜 로그로 무시 - lineNumber: {}, logType: {}, rawLog: {}",
                        lineNumber, logType, rawLog);
                addFailDetail(failDetails, lineNumber, "비지원 타입에 값 토큰 포함: " + logType);
                return null;
            }
        }

        // ── Step 9. CollectLog 생성 ──────────────────────────────────────────
        // 파서가 알 수 있는 필드(로그 고유 정보)만 채운다.
        // 서버 ID, 파일 경로, 수집일시 등 수집 맥락 정보는 CollectRetryService에서 보완한다.
        CollectLog collectLog = new CollectLog();
        collectLog.setLogType(logType);
        collectLog.setLogId(logId);
        collectLog.setLogTimestamp(logTimestamp);
        collectLog.setLogContent(logContent);
        collectLog.setLogValue(logValue);
        collectLog.setRawLog(rawLog);
        collectLog.setLineNumber(lineNumber);
        return collectLog;
    }

    /**
     * logType이 시스템에서 지원하는 타입인지 확인한다.
     *
     * 지원 타입: TEXT, INFO, DATE, NUMERIC, EXIST (CollectConstants 상수 참조)
     */
    private boolean isSupportedLogType(String logType) {
        return CollectConstants.LOG_TYPE_TEXT.equals(logType)
                || CollectConstants.LOG_TYPE_INFO.equals(logType)
                || CollectConstants.LOG_TYPE_DATE.equals(logType)
                || CollectConstants.LOG_TYPE_NUMERIC.equals(logType)
                || CollectConstants.LOG_TYPE_EXIST.equals(logType)
                || CollectConstants.LOG_TYPE_COMPARE.equals(logType)
                || CollectConstants.LOG_TYPE_TIME.equals(logType);
    }

    private void addFailDetail(List<String> failDetails, long lineNumber, String reason) {
        if (failDetails != null) {
            failDetails.add("라인 " + lineNumber + ": " + reason);
        }
    }

    /**
     * $값$ 형식의 수치 토큰에서 숫자 문자열을 추출하여 BigDecimal로 변환한다.
     *
     * BigDecimal을 사용하는 이유:
     *   수집 대상 수치 로그는 소수점 자리수가 정해지지 않으므로
     *   정밀도 손실 없이 표현하기 위해 BigDecimal을 사용한다.
     *
     * @param logValueToken "$숫자$" 형식의 원본 토큰 문자열
     * @param lineNumber    경고 로그 출력용 라인번호
     * @return 파싱된 BigDecimal, 실패 시 null
     */
    private BigDecimal parseNumericValue(String valueToken, long lineNumber) {
        Matcher valueMatcher = WRAPPED_VALUE_PATTERN.matcher(valueToken);
        if (!valueMatcher.matches()) {
            log.warn("값 토큰 포맷 불일치로 무시 - lineNumber: {}, valueToken: {}", lineNumber, valueToken);
            return null;
        }

        String valueText = valueMatcher.group("value").trim();
        if (valueText.isEmpty()) {
            log.warn("값 토큰 공백으로 무시 - lineNumber: {}", lineNumber);
            return null;
        }

        try {
            return new BigDecimal(valueText);
        } catch (NumberFormatException e) {
            log.warn("값 토큰 수치 파싱 실패로 무시 - lineNumber: {}, value: {}", lineNumber, valueText);
            return null;
        }
    }

    private List<String> extractValueTokens(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return VALUE_TOKEN_PATTERN.matcher(text)
                .results()
                .map(MatchResult::group)
                .toList();
    }

    private boolean isTimeToken(String valueToken) {
        if (valueToken == null) {
            return false;
        }
        Matcher matcher = WRAPPED_VALUE_PATTERN.matcher(valueToken);
        if (!matcher.matches()) {
            return false;
        }
        return matcher.group("value").contains(":");
    }

    /**
     * $...$ 값이 yyyy/MM/dd 또는 yyyy-MM-dd 형식(구분자 혼용 불가)의 실재하는 날짜인지 확인한다.
     */
    private boolean isDateToken(String valueToken) {
        Matcher matcher = WRAPPED_VALUE_PATTERN.matcher(valueToken);
        if (!matcher.matches()) {
            return false;
        }
        String value = matcher.group("value").trim();
        if (!DATE_VALUE_PATTERN.matcher(value).matches()) {
            return false;
        }
        try {
            LocalDate.parse(value.replace('-', '/'), DATE_VALUE_FORMATTER);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private Integer parseTimeMinutes(String timeToken, long lineNumber) {
        Matcher matcher = WRAPPED_VALUE_PATTERN.matcher(timeToken);
        if (!matcher.matches()) {
            log.warn("시간 토큰 포맷 불일치로 무시 - lineNumber: {}, token: {}", lineNumber, timeToken);
            return null;
        }

        String timeText = matcher.group("value").trim();
        int colon = timeText.indexOf(':');
        if (colon < 0 || colon != timeText.lastIndexOf(':')) {
            log.warn("시간 토큰 포맷 불일치(HH:mm)로 무시 - lineNumber: {}, token: {}", lineNumber, timeToken);
            return null;
        }

        String hhText = timeText.substring(0, colon);
        String mmText = timeText.substring(colon + 1);
        if (hhText.length() != 2 || mmText.length() != 2) {
            log.warn("시간 토큰 포맷 불일치(HH:mm)로 무시 - lineNumber: {}, token: {}", lineNumber, timeToken);
            return null;
        }

        int hh;
        int mm;
        try {
            hh = Integer.parseInt(hhText);
            mm = Integer.parseInt(mmText);
        } catch (NumberFormatException e) {
            log.warn("시간 토큰 숫자 파싱 실패로 무시 - lineNumber: {}, token: {}", lineNumber, timeToken);
            return null;
        }

        if (hh < 0 || hh > 23 || mm < 0 || mm > 59) {
            log.warn("시간 토큰 범위 오류로 무시 - lineNumber: {}, token: {}", lineNumber, timeToken);
            return null;
        }

        return hh * 60 + mm;
    }

    private String removeTokensFromContent(String contentPart) {
        if (contentPart == null || contentPart.isEmpty()) {
            return "";
        }
        return VALUE_TOKEN_PATTERN.matcher(contentPart).replaceAll("");
    }

    private String normalizeContent(String contentPart) {
        if (contentPart == null) {
            return "";
        }
        return contentPart.trim().replaceAll("\\s{2,}", " ");
    }
}
