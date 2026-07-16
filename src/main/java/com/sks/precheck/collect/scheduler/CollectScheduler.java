package com.sks.precheck.collect.scheduler;

import com.sks.precheck.collect.common.exception.CollectException;
import com.sks.precheck.collect.parser.CollectScheduleParser;
import com.sks.precheck.collect.parser.CollectServerAuthParser;
import com.sks.precheck.collect.service.CollectService;
import com.sks.precheck.collect.vo.CollectScheduleVo;
import com.sks.precheck.collect.vo.CollectServerAuthVo;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 로그 수집 스케줄러.
 *
 * 수집 스케줄 설정 파일(PreCheck_CollectLogs_Schedule.conf)을 주기적으로 읽고,
 * 배치/주기 스케줄의 실행 조건(요일/시작시간/종료시간/간격)을 검사한 뒤
 * 실행 시점에 CollectService를 호출한다.
 */
@Component
public class CollectScheduler {

    private static final Logger log = LogManager.getLogger(CollectScheduler.class);

    private static final String DEFAULT_SCHEDULE_FILE_RELATIVE_PATH = "/cfg/PreCheck_CollectLogs_Schedule.conf";
    private static final String DEFAULT_SERVER_AUTH_FILE_RELATIVE_PATH = "/cfg/PreCheck_CollectServer_Auth.conf";

    private final CollectService collectService;
    private final CollectScheduleParser collectScheduleParser;

    private final String scheduleFilePath;
    private final String collectMode;
    private final int sftpPort;
    private final String sftpUsername;
    private final String sftpPassword;
    private final Map<String, CollectServerAuthVo> serverAuthMap;

    private final long reloadIntervalMillis;
    private volatile long lastReloadAtMillis;
    private volatile List<CollectScheduleVo> cachedSchedules;

    private final Map<String, String> lastBatchRunDateByKey = new HashMap<>();
    private final Map<String, Long> lastPeriodicRunIndexByKey = new HashMap<>();

    public CollectScheduler(
            CollectService collectService,
            @Value("${precheck.collect.schedule-file-path:}") String scheduleFilePath,
            @Value("${precheck.collect.server-auth-file-path:}") String serverAuthFilePath,
            @Value("${precheck.collect.mode:sftp}") String collectMode,
            @Value("${precheck.sftp.port:22}") int sftpPort,
            @Value("${precheck.sftp.username:}") String sftpUsername,
            @Value("${precheck.sftp.password:}") String sftpPassword,
            @Value("${precheck.collect.scheduler.reload-interval-ms:60000}") long reloadIntervalMillis
    ) {
        // Log loaded configuration values (exclude password for security)
        log.info("<<< CollectScheduler initialized with configuration: >>>");
        log.info("  Schedule file path: {}", scheduleFilePath);
        log.info("  Collect mode: {}", collectMode);
        log.info("  SFTP port(전역 기본값): {}", sftpPort);
        log.info("  SFTP username(전역 기본값): {}", sftpUsername);
        log.info("  Schedule reload interval (ms): {}", reloadIntervalMillis);
        log.info("<<< Configuration initialized >>>");

        this.collectService = collectService;
        this.collectScheduleParser = new CollectScheduleParser();
        this.scheduleFilePath = (scheduleFilePath == null || scheduleFilePath.isBlank())
                ? System.getProperty("user.home") + DEFAULT_SCHEDULE_FILE_RELATIVE_PATH
                : scheduleFilePath;
        this.collectMode = collectMode != null ? collectMode : "sftp";
        this.sftpPort = sftpPort;
        this.sftpUsername = sftpUsername != null ? sftpUsername : "";
        this.sftpPassword = sftpPassword != null ? sftpPassword : "";
        this.reloadIntervalMillis = reloadIntervalMillis;

        String resolvedAuthFilePath = (serverAuthFilePath == null || serverAuthFilePath.isBlank())
                ? System.getProperty("user.home") + DEFAULT_SERVER_AUTH_FILE_RELATIVE_PATH
                : serverAuthFilePath;
        Map<String, CollectServerAuthVo> loadedAuthMap;
        try {
            loadedAuthMap = new CollectServerAuthParser().parseAuthFile(resolvedAuthFilePath);
        } catch (CollectException e) {
            log.error("서버별 접속정보 override 로딩 실패, 전역 기본값만 사용 - file: {}, 사유: {}", resolvedAuthFilePath, e.getMessage());
            loadedAuthMap = Map.of();
        }
        this.serverAuthMap = loadedAuthMap;
        log.info("  서버별 접속정보 override 건수: {}", serverAuthMap.size());
    }

    /**
     * 스케줄을 평가하고, 실행 시점이면 수집을 수행한다.
     *
     * fixedDelay 방식으로 실행하여, 스케줄러 동작 자체가 중복 실행되지 않도록 한다.
     */
    @Scheduled(fixedDelayString = "${precheck.collect.scheduler.fixed-delay-ms:1000}")
    public void run() {
        if (!"local".equals(collectMode) && (sftpUsername.isBlank() || sftpPassword.isBlank()) && serverAuthMap.isEmpty()) {
            log.warn("SFTP 계정/비밀번호가 설정되지 않아 스케줄 실행을 건너뜀");
            return;
        }

        List<CollectScheduleVo> schedules = getSchedules();
        if (schedules == null || schedules.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (CollectScheduleVo schedule : schedules) {
            try {
                if (shouldRun(schedule, now)) {
                    CollectServerAuthVo override = serverAuthMap.get(schedule.getServerId());
                    int port = (override != null && override.getPort() != null) ? override.getPort() : sftpPort;
                    String username = (override != null && override.getUsername() != null) ? override.getUsername() : sftpUsername;
                    String password = (override != null && override.getPassword() != null) ? override.getPassword() : sftpPassword;

                    log.info("SFTP 접속정보 확정 - 서버: {}, port: {} ({}), username: {} ({})",
                            schedule.getServerId(), port,
                            (override != null && override.getPort() != null) ? "override" : "전역 기본값",
                            username,
                            (override != null && override.getUsername() != null) ? "override" : "전역 기본값");

                    if (!"local".equals(collectMode) && (username.isBlank() || password.isBlank())) {
                        log.warn("SFTP 계정/비밀번호 미설정으로 스케줄 건너뜀 - 서버: {}", schedule.getServerId());
                        continue;
                    }

                    collectService.collect(schedule, port, username, password);
                }
            } catch (Exception e) {
                log.error("스케줄 실행 실패 - 서버: {}, 파일: {}, 사유: {}",
                        schedule.getServerId(), schedule.getSourceFilePath(), e.getMessage());
            }
        }
    }

    private List<CollectScheduleVo> getSchedules() {
        long nowMillis = System.currentTimeMillis();
        if (cachedSchedules != null && nowMillis - lastReloadAtMillis < reloadIntervalMillis) {
            return cachedSchedules;
        }

        try {
            List<CollectScheduleVo> schedules = collectScheduleParser.parseScheduleFile(scheduleFilePath);
            cachedSchedules = schedules;
            lastReloadAtMillis = nowMillis;
            return schedules;
        } catch (CollectException e) {
            log.error("스케줄 파일 파싱 실패 - file: {}, 사유: {}", scheduleFilePath, e.getMessage());
            cachedSchedules = List.of();
            lastReloadAtMillis = nowMillis;
            return cachedSchedules;
        }
    }

    private boolean shouldRun(CollectScheduleVo schedule, LocalDateTime now) {
        ScheduleRule rule = parseRule(schedule.getScheduleExpression());
        if (!isTodayMatched(rule.daySpec, now.toLocalDate())) {
            return false;
        }

        int pollWindowSeconds = getPollWindowSeconds();
        int nowSeconds = now.toLocalTime().toSecondOfDay();
        int startSeconds = rule.startTime.toSecondOfDay();

        if ("배치".equals(rule.type)) {
            return shouldRunBatch(schedule, now, nowSeconds, startSeconds, pollWindowSeconds);
        }

        return shouldRunPeriodic(schedule, nowSeconds, startSeconds, rule, pollWindowSeconds);
    }

    private boolean shouldRunBatch(
            CollectScheduleVo schedule,
            LocalDateTime now,
            int nowSeconds,
            int startSeconds,
            int pollWindowSeconds
    ) {
        if (nowSeconds < startSeconds || nowSeconds >= startSeconds + pollWindowSeconds) {
            return false;
        }

        String key = buildScheduleKey(schedule);
        String today = now.toLocalDate().toString();
        String lastRunDate = lastBatchRunDateByKey.get(key);
        if (today.equals(lastRunDate)) {
            return false;
        }

        lastBatchRunDateByKey.put(key, today);
        log.info("배치 수집 실행 - serverId: {}, serverIp: {}, sourceFilePath: {}, schedule: {}",
                schedule.getServerId(), schedule.getServerIp(), schedule.getSourceFilePath(),
                schedule.getScheduleExpression());
        return true;
    }

    private boolean shouldRunPeriodic(
            CollectScheduleVo schedule,
            int nowSeconds,
            int startSeconds,
            ScheduleRule rule,
            int pollWindowSeconds
    ) {
        int endSeconds = rule.endTime.toSecondOfDay();
        if (nowSeconds < startSeconds || nowSeconds > endSeconds) {
            return false;
        }

        long intervalSeconds = (long) rule.intervalMinutes * 60L;
        long offsetSeconds = nowSeconds - startSeconds;

        long runIndex = offsetSeconds / intervalSeconds;
        long remainder = offsetSeconds % intervalSeconds;
        if (remainder < 0 || remainder >= pollWindowSeconds) {
            return false;
        }

        String key = buildScheduleKey(schedule);
        Long lastIndex = lastPeriodicRunIndexByKey.get(key);
        if (lastIndex != null && lastIndex == runIndex) {
            return false;
        }

        lastPeriodicRunIndexByKey.put(key, runIndex);
        log.info("주기 수집 실행 - serverId: {}, serverIp: {}, sourceFilePath: {}, 간격: {}분, {}번째 실행, schedule: {}",
                schedule.getServerId(), schedule.getServerIp(), schedule.getSourceFilePath(),
                rule.intervalMinutes, runIndex + 1, schedule.getScheduleExpression());
        return true;
    }

    private int getPollWindowSeconds() {
        return 2;
    }

    private String buildScheduleKey(CollectScheduleVo schedule) {
        return schedule.getServerId() + "::" + schedule.getServerIp() + "::" + schedule.getSourceFilePath() + "::" + schedule.getScheduleExpression();
    }

    private ScheduleRule parseRule(String scheduleExpression) {
        if (scheduleExpression == null || scheduleExpression.isBlank()) {
            throw new CollectException("수집주기기술이 비어있다");
        }

        String[] parts = scheduleExpression.split("\\|", -1);
        String type = parts[0].trim();
        if ("배치".equals(type)) {
            if (parts.length != 3) {
                throw new CollectException("배치 스케줄 포맷 오류: " + scheduleExpression);
            }
            return new ScheduleRule(type, parts[1].trim(), parseTime(parts[2].trim()), null, null);
        }

        if ("주기".equals(type)) {
            if (parts.length != 5) {
                throw new CollectException("주기 스케줄 포맷 오류: " + scheduleExpression);
            }
            int intervalMinutes;
            try {
                intervalMinutes = Integer.parseInt(parts[3].trim());
            } catch (NumberFormatException e) {
                throw new CollectException("수집간격(분) 포맷 오류: " + scheduleExpression);
            }
            if (intervalMinutes <= 0) {
                throw new CollectException("수집간격(분) 값 오류: " + scheduleExpression);
            }
            return new ScheduleRule(type, parts[1].trim(), parseTime(parts[2].trim()), intervalMinutes, parseTime(parts[4].trim()));
        }

        throw new CollectException("수집주기기술 타입 오류: " + scheduleExpression);
    }

    private boolean isTodayMatched(String daySpec, LocalDate date) {
        if ("*".equals(daySpec)) {
            return true;
        }

        int today = toDayDigit(date.getDayOfWeek());
        if (daySpec.contains("-")) {
            String[] range = daySpec.split("-", -1);
            if (range.length != 2) {
                return false;
            }
            Integer start = parseDayDigit(range[0].trim());
            Integer end = parseDayDigit(range[1].trim());
            if (start == null || end == null) {
                return false;
            }
            if (start == 0 && end == 6) {
                return true;
            }
            return today >= start && today <= end;
        }

        Integer day = parseDayDigit(daySpec.trim());
        return day != null && day == today;
    }

    private Integer parseDayDigit(String text) {
        if (text == null || text.length() != 1) {
            return null;
        }
        char c = text.charAt(0);
        if (c < '0' || c > '6') {
            return null;
        }
        return c - '0';
    }

    private int toDayDigit(DayOfWeek dayOfWeek) {
        int value = dayOfWeek.getValue();
        return value % 7;
    }

    private LocalTime parseTime(String hhmmss) {
        if (hhmmss == null || hhmmss.length() != 6) {
            throw new CollectException("시간 포맷 오류(HHmmss): " + hhmmss);
        }

        int hh;
        int mm;
        int ss;
        try {
            hh = Integer.parseInt(hhmmss.substring(0, 2));
            mm = Integer.parseInt(hhmmss.substring(2, 4));
            ss = Integer.parseInt(hhmmss.substring(4, 6));
        } catch (NumberFormatException e) {
            throw new CollectException("시간 포맷 오류(HHmmss): " + hhmmss);
        }

        return LocalTime.of(hh, mm, ss);
    }

    private static class ScheduleRule {
        private final String type;
        private final String daySpec;
        private final LocalTime startTime;
        private final Integer intervalMinutes;
        private final LocalTime endTime;

        private ScheduleRule(String type, String daySpec, LocalTime startTime, Integer intervalMinutes, LocalTime endTime) {
            this.type = type;
            this.daySpec = daySpec;
            this.startTime = startTime;
            this.intervalMinutes = intervalMinutes;
            this.endTime = endTime;
        }
    }
}

