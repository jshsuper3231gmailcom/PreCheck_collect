package com.sks.precheck.collect.parser;

import com.sks.precheck.collect.common.exception.CollectException;
import com.sks.precheck.collect.vo.CollectScheduleVo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 로그 수집 스케줄 설정 파일 파서.
 *
 * ~/cfg/PreCheck_CollectLogs_Schedule.conf 파일을 읽어 [서버구분][IP][파일경로][수집주기기술][Y|N](선택) 포맷을
 * CollectScheduleVo 목록으로 변환한다. 5번째 브라켓은 휴장일(비영업일) 스킵 여부이며, 생략 시 N(스킵 안 함)으로 간주한다.
 *
 * '#'으로 시작하는 라인과 포맷이 맞지 않는 라인은 무시하고 WARN 로그를 남긴다.
 */
public class CollectScheduleParser {

    private static final Logger log = LogManager.getLogger(CollectScheduleParser.class);

    /**
     * 스케줄 설정 파일을 파싱하여 유효한 스케줄 목록을 반환한다.
     *
     * @param filePath 스케줄 설정 파일 경로
     * @return 유효한 스케줄 목록
     * @throws CollectException 파일을 읽을 수 없는 경우
     */
    public List<CollectScheduleVo> parseScheduleFile(String filePath) {
        Path path = Path.of(filePath);
        log.debug("스케줄 설정 파일 파싱 시작 - filePath: {}, absolutePath: {}", filePath, path.toAbsolutePath());

        List<CollectScheduleVo> result = new ArrayList<>();

        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                CollectScheduleVo schedule = parseLine(lines.get(i), i + 1);
                if (schedule != null) {
                    result.add(schedule);
                }
            }
            log.debug("스케줄 설정 파일 파싱 완료 - absolutePath: {}, 유효 스케줄 건수: {}", path.toAbsolutePath(), result.size());
            for (int i = 0; i < result.size(); i++) {
                CollectScheduleVo s = result.get(i);
                log.info("=============================");
                log.info("  [{}] serverId: {}, serverIp: {}, sourceFilePath: {}, scheduleExpression: {}",
                        i + 1, s.getServerId(), s.getServerIp(), s.getSourceFilePath(), s.getScheduleExpression());
            }
        } catch (IOException e) {
            log.error("스케줄 설정 파일 읽기 실패 - filePath: {}, absolutePath: {}, error: {}", filePath, path.toAbsolutePath(), e.getMessage());
            throw new CollectException("스케줄 설정 파일 읽기 실패1: " + filePath, e);
        }

        return result;
    }

    private CollectScheduleVo parseLine(String line, int lineNumber) {
        if (line == null) {
            return null;
        }

        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }

        List<String> tokens = extractBracketTokens(trimmed);
        if (tokens.size() != 4 && tokens.size() != 5) {
            log.warn("스케줄 라인 포맷 오류로 무시 - lineNumber: {}, line: {}", lineNumber, trimmed);
            return null;
        }

        String serverId = tokens.get(0).trim();
        String serverIp = tokens.get(1).trim();
        String sourceFilePath = tokens.get(2).trim();
        String scheduleExpression = tokens.get(3).trim();

        if (serverId.isEmpty() || serverIp.isEmpty() || sourceFilePath.isEmpty() || scheduleExpression.isEmpty()) {
            log.warn("스케줄 라인 필수값 누락으로 무시 - lineNumber: {}, line: {}", lineNumber, trimmed);
            return null;
        }

        if (!isValidScheduleExpression(scheduleExpression)) {
            log.warn("수집주기기술 포맷 오류로 무시 - lineNumber: {}, schedule: {}", lineNumber, scheduleExpression);
            return null;
        }

        boolean holidaySkip = false;
        if (tokens.size() == 5) {
            String holidaySkipToken = tokens.get(4).trim();
            Boolean parsed = parseHolidaySkipFlag(holidaySkipToken);
            if (parsed == null) {
                log.warn("휴장일 스킵 플래그 포맷 오류(Y/N만 허용)로 무시 - lineNumber: {}, value: {}", lineNumber, holidaySkipToken);
                return null;
            }
            holidaySkip = parsed;
        }

        CollectScheduleVo vo = new CollectScheduleVo();
        vo.setServerId(serverId);
        vo.setServerIp(serverIp);
        vo.setSourceFilePath(sourceFilePath);
        vo.setScheduleExpression(scheduleExpression);
        vo.setHolidaySkip(holidaySkip);
        return vo;
    }

    private Boolean parseHolidaySkipFlag(String text) {
        if ("Y".equals(text)) {
            return Boolean.TRUE;
        }
        if ("N".equals(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private List<String> extractBracketTokens(String text) {
        List<String> tokens = new ArrayList<>(4);
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf('[', i);
            if (start < 0) {
                break;
            }
            int end = text.indexOf(']', start + 1);
            if (end < 0) {
                break;
            }
            tokens.add(text.substring(start + 1, end));
            i = end + 1;
        }
        return tokens;
    }

    private boolean isValidScheduleExpression(String scheduleExpression) {
        String[] parts = scheduleExpression.split("\\|", -1);
        if (parts.length == 0) {
            return false;
        }

        String type = parts[0].trim();
        if ("배치".equals(type)) {
            if (parts.length != 3) {
                return false;
            }
            if (!isValidDaySpec(parts[1].trim())) {
                return false;
            }
            return isValidTimeHhmmss(parts[2].trim(), true);
        }

        if ("주기".equals(type)) {
            if (parts.length != 5) {
                return false;
            }
            if (!isValidDaySpec(parts[1].trim())) {
                return false;
            }
            if (!isValidTimeHhmmss(parts[2].trim(), true)) {
                return false;
            }
            if (!isValidIntervalMinutes(parts[3].trim())) {
                return false;
            }
            return isValidTimeHhmmss(parts[4].trim(), false);
        }

        return false;
    }

    private boolean isValidDaySpec(String daySpec) {
        if (daySpec.isEmpty()) {
            return false;
        }

        if (daySpec.contains(",") || daySpec.contains(" ")) {
            return false;
        }

        if ("*".equals(daySpec)) {
            return true;
        }

        if (daySpec.contains("-")) {
            String[] range = daySpec.split("-", -1);
            if (range.length != 2) {
                return false;
            }
            Integer start = parseDay(range[0]);
            Integer end = parseDay(range[1]);
            if (start == null || end == null) {
                return false;
            }
            return start <= end;
        }

        return parseDay(daySpec) != null;
    }

    private Integer parseDay(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        if (text.length() != 1) {
            return null;
        }
        char c = text.charAt(0);
        if (c < '0' || c > '6') {
            return null;
        }
        return c - '0';
    }

    private boolean isValidTimeHhmmss(String timeText, boolean isStartTime) {
        if (timeText == null || timeText.length() != 6) {
            return false;
        }

        int time;
        try {
            time = Integer.parseInt(timeText);
        } catch (NumberFormatException e) {
            return false;
        }

        int hh = time / 10000;
        int mm = (time / 100) % 100;
        int ss = time % 100;
        if (hh < 0 || hh > 23) {
            return false;
        }
        if (mm < 0 || mm > 59) {
            return false;
        }
        if (ss < 0 || ss > 59) {
            return false;
        }

        if (isStartTime) {
            return time >= 1;
        }
        return time <= 235959;
    }

    private boolean isValidIntervalMinutes(String minutesText) {
        if (minutesText == null || minutesText.isEmpty()) {
            return false;
        }
        try {
            int minutes = Integer.parseInt(minutesText);
            return minutes > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}

