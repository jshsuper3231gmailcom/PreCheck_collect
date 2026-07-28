package com.sks.precheck.collect.service;

import com.sks.precheck.collect.common.constants.CollectConstants;
import com.sks.precheck.collect.common.exception.CollectException;
import com.sks.precheck.collect.common.util.DateUtil;
import com.sks.precheck.collect.common.util.SequenceHelper;
import com.sks.precheck.collect.domain.CollectHistory;
import com.sks.precheck.collect.domain.CollectLog;
import com.sks.precheck.collect.domain.CollectResumePoint;
import com.sks.precheck.collect.mapper.CollectHistoryMapper;
import com.sks.precheck.collect.mapper.CollectLogMapper;
import com.sks.precheck.collect.parser.LogNormalizeParser;
import com.sks.precheck.collect.vo.CollectScheduleVo;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Service;

/**
 * 재시도 로직이 적용된 실질적인 수집 처리 서비스.
 *
 * 역할:
 *   원격 서버에서 SFTP로 로그 파일을 읽어 정규화 로그를 파싱하고,
 *   TB_COLLECT_LOG에 저장한 뒤 TB_COLLECT_HISTORY를 최종 상태로 갱신한다.
 *
 * 설계 이유:
 *   @Retryable / @Recover는 Spring AOP 프록시를 통해서만 동작한다.
 *   CollectService와 같은 클래스에 두면 self-invocation 문제로 재시도가 무력화되므로
 *   별도 빈으로 분리한다. CollectService에서 이 빈을 주입받아 호출한다.
 *
 * 재시도 정책:
 *   - 대상 예외 : CollectException
 *   - 최대 시도 횟수 : CollectConstants.MAX_RETRY_COUNT + 1 (최초 1회 + 재시도 N회)
 *   - 재시도 간격 : CollectConstants.RETRY_DELAY_MILLISECONDS (고정 대기)
 *   - 최종 실패 시 : recover() 호출 → 이력을 FAIL 상태로 갱신
 */
@Service
public class CollectRetryService {

    private static final Logger log = LogManager.getLogger(CollectRetryService.class);

    @Value("${precheck.collect.file-charset:UTF-8}")
    private Charset fileCharset;

    private final SequenceHelper sequenceHelper;
    private final CollectLogMapper collectLogMapper;
    private final CollectHistoryMapper collectHistoryMapper;
    private final ExcludeService excludeService;
    private final FileReadService fileReadService;

    // LogNormalizeParser는 상태를 갖지 않으므로 빈 주입 없이 직접 생성하여 재사용한다.
    private final LogNormalizeParser logNormalizeParser;

    public CollectRetryService(
            SequenceHelper sequenceHelper,
            CollectLogMapper collectLogMapper,
            CollectHistoryMapper collectHistoryMapper,
            ExcludeService excludeService,
            FileReadService fileReadService
    ) {
        this.sequenceHelper = sequenceHelper;
        this.collectLogMapper = collectLogMapper;
        this.collectHistoryMapper = collectHistoryMapper;
        this.excludeService = excludeService;
        this.fileReadService = fileReadService;
        this.logNormalizeParser = new LogNormalizeParser();
    }

    /**
     * 실제 수집을 수행하며, CollectException 발생 시 자동으로 재시도된다.
     *
     * 처리 흐름:
     *   1. 현재 재시도 횟수를 조회하여 수집 이력에 갱신한다.
     *   2. 영구 제외 대상이면 수집을 건너뛴다(SKIP).
     *   3. 원격 파일 크기를 조회한다.
     *   4. 배치 수집에서 파일이 초기 수집 크기 한도를 초과하면 영구 제외 등록 후 건너뛴다.
     *   5. 주기 수집이면 직전 성공 수집의 마지막 라인번호/바이트오프셋을 조회해 증분 시작점을 계산한다.
     *      신규 데이터가 없으면(오프셋이 파일 크기 이상) SFTP 접속 자체를 생략하고 바로 성공 처리한다.
     *   6. SFTP로 원격 파일을 시작 오프셋부터 seek해서 읽으며 정규화 로그를 파싱한다.
     *   7. 주기 수집에서 증분 읽기량이 증분 크기 한도를 초과하면 영구 제외 등록 후 건너뛴다.
     *   8. 파싱된 로그를 TB_COLLECT_LOG에 저장한다.
     *   9. 수집 성공 상태로 이력을 갱신하고 저장 건수를 반환한다.
     *
     * @param historyId    TB_COLLECT_HISTORY PK (CollectService에서 선등록한 이력)
     * @param schedule     스케줄 정보 (서버 IP, 파일 경로 등)
     * @param scheduleType "배치" 또는 "주기"
     * @param port         SSH 포트
     * @param username     SFTP 계정
     * @param password     SFTP 비밀번호
     * @return TB_COLLECT_LOG에 저장된 정규화 로그 건수
     */
    @Retryable(
            retryFor = {CollectException.class},
            maxAttemptsExpression = "#{T(com.sks.precheck.collect.common.constants.CollectConstants).MAX_RETRY_COUNT + 1}",
            backoff = @Backoff(delayExpression = "#{T(com.sks.precheck.collect.common.constants.CollectConstants).RETRY_DELAY_MILLISECONDS}")
    )
    public int collectWithRetry(
            Long historyId,
            CollectScheduleVo schedule,
            String scheduleType,
            int port,
            String username,
            String password
    ) {

        // ── Step 1. 현재 재시도 횟수 조회 및 이력 갱신 ────────────────────────────
        // RetrySynchronizationManager에서 Spring Retry가 관리하는 현재 시도 컨텍스트를 가져온다.
        // 첫 번째 시도(재시도 아님)일 때 getRetryCount()는 0을 반환한다.
        org.springframework.retry.RetryContext retryContext = RetrySynchronizationManager.getContext();
        int retryCount = retryContext != null ? retryContext.getRetryCount() : 0;
        updateHistoryRetryCount(historyId, retryCount);

        String serverId = schedule.getServerId();
        String serverIp = schedule.getServerIp();

        // 스케줄에 정의된 파일 경로 끝에 '+'가 붙어있으면 날짜 변경에 따른 라인번호 리셋을 비활성화한다.
        // 예) "/logs/test.log+" → 실제 경로 "/logs/test.log", 날짜가 바뀌어도 증분 수집 유지
        boolean dateResetDisabled = schedule.getSourceFilePath().endsWith(CollectConstants.FILE_PATH_NO_DATE_RESET_SUFFIX);

        // 스케줄에 정의된 파일 경로의 '+' 접미사를 제거하고 날짜 자리표시자(yyyymmdd)를 오늘 날짜로 치환한다.
        // 예) "/logs/test.yyyymmdd" → "/logs/test.20260612"
        // CollectService가 이력을 선등록할 때도 동일한 메서드로 경로를 치환하므로
        // findResumePoint 조회 시 경로가 항상 일치한다.
        String collectDate = DateUtil.todayCollectDate();
        String sourceFilePath = DateUtil.resolveActualFilePath(schedule.getSourceFilePath(), collectDate);

        // ── Step 2. 영구 제외 대상 확인 ───────────────────────────────────────────
        // TB_COLLECT_EXCLUDE에 RESTORE_YN='N' 레코드가 있으면 제외 대상이다.
        // 한 번 제외된 파일은 관리자가 복원 처리하기 전까지 수집하지 않는다.
        if (excludeService.isExcluded(serverId, sourceFilePath)) {
            updateHistorySkip(historyId, "EXCLUDED");
            log.warn("영구 제외 대상이라 수집을 건너뜀 - 서버: {}, 파일: {}", serverId, sourceFilePath);
            return 0;
        }

        // ── Step 3. 원격 파일 크기 조회 ───────────────────────────────────────────
        // SFTP stat 명령으로 파일 크기(bytes)를 가져온다.
        // 이후 크기 초과 여부 판단과 수집 이력 기록에 사용된다.
        long fileSizeBytes = fileReadService.getFileSizeBytes(serverIp, port, username, password, sourceFilePath);

        // ── Step 4. 배치 수집 — 초기 파일 크기 한도 초과 검사 ───────────────────
        // 배치 수집은 파일 전체를 읽으므로, 파일이 너무 크면 수집 자체가 불가하다.
        // 한도를 초과한 경우 영구 제외로 등록하여 이후 스케줄에서도 시도하지 않는다.
        if ("배치".equals(scheduleType) && fileSizeBytes >= CollectConstants.INIT_COLLECT_SIZE_LIMIT_BYTES) {
            registerExclude(historyId, schedule, sourceFilePath, CollectConstants.EXCLUDE_REASON_INIT_SIZE, fileSizeBytes,
                    "배치 수집 파일 크기 초과");
            updateHistorySkip(historyId, "INIT_SIZE_EXCEEDED");
            return 0;
        }

        // ── Step 5. 주기 수집 — 시작 라인번호/바이트오프셋 계산 ──────────────────
        // 주기 수집은 직전 성공 수집이 마지막으로 읽은 위치 이후부터 읽는다(증분 수집).
        // 단, 직전 성공 수집의 COLLECT_DATE가 오늘과 다르면(날짜가 바뀌면) 처음부터 다시 읽는다.
        // 이력이 없으면(첫 수집이거나 이력 없음) 1번 라인/오프셋 0부터 시작한다.
        // 배치 수집은 항상 파일 전체를 읽으므로 resumePoint를 조회하지 않는다.
        CollectResumePoint resumePoint = null;
        if ("주기".equals(scheduleType)) {
            String lastLineCollectDate = dateResetDisabled ? null : collectDate;
            resumePoint = collectHistoryMapper.findResumePoint(serverId, sourceFilePath, lastLineCollectDate);
        }

        Long legacyLastLineNumber = resumePoint == null ? null : resumePoint.getLastLineNumber();
        Long knownByteOffset = resumePoint == null ? null : resumePoint.getLastByteOffset();

        long startLineNumber;
        long startByteOffset;
        // suppressBelowLineNumber보다 작거나 같은 라인번호는 실제로는 예전(라인번호 기반) 방식으로
        // 이미 수집이 끝난 구간이므로 파싱·저장하지 않고 건너뛴다. 정상 상황에서는 항상 0이다.
        long suppressBelowLineNumber = 0;

        if (knownByteOffset != null) {
            // 정상 경로: LAST_BYTE_OFFSET을 알고 있으므로 그 위치로 바로 seek해서 읽는다.
            startLineNumber = (legacyLastLineNumber == null ? 0 : legacyLastLineNumber) + 1;
            startByteOffset = knownByteOffset;
        } else if (legacyLastLineNumber != null) {
            // 과도기 경로: LAST_BYTE_OFFSET 컬럼 도입 이전에 기록된 이력이라 바이트 위치를 모른다.
            // 파일 처음부터 다시 읽되, 이미 처리된 구간(legacyLastLineNumber까지)은 저장하지 않는다.
            // 이 실행이 끝나면 LAST_BYTE_OFFSET이 기록되므로, 이후 실행부터는 정상 경로(seek)로 전환된다.
            startLineNumber = 1;
            startByteOffset = 0;
            suppressBelowLineNumber = legacyLastLineNumber;
        } else {
            // 첫 수집이거나 이력이 없는 경우: 처음부터 읽는다.
            startLineNumber = 1;
            startByteOffset = 0;
        }

        // ── Step 5-1. 주기 수집 — 신규 데이터 없으면 네트워크 접속 자체를 생략 ────
        // 파일 크기(Step 3에서 이미 조회함)가 시작 오프셋과 같거나 작으면 늘어난 데이터가 없다는
        // 뜻이므로, SFTP 접속·READ 요청을 아예 하지 않고 바로 성공 처리한다.
        if ("주기".equals(scheduleType) && knownByteOffset != null && startByteOffset >= fileSizeBytes) {
            updateHistorySuccess(historyId, 0L, startLineNumber - 1, startByteOffset, fileSizeBytes);
            log.info("신규 데이터 없어 수집 생략 - 서버: {}, 파일: {}, 오프셋: {}", serverId, sourceFilePath, startByteOffset);
            return 0;
        }

        // ── Step 6. 원격 파일 라인 읽기 및 정규화 로그 파싱 ──────────────────────
        List<CollectLog> parsedLogs = new ArrayList<>();
        List<String> parseFailures = new ArrayList<>();

        // LineReadState : 람다 내부에서 읽기 상태를 추적하기 위한 가변 컨테이너.
        // Java 람다는 effectively final 변수만 캡처할 수 있어서
        // 상태를 외부로 전달하기 위해 이 내부 클래스를 사용한다.
        LineReadState lineReadState = new LineReadState(startLineNumber - 1, startByteOffset);
        long finalSuppressBelowLineNumber = suppressBelowLineNumber;

        fileReadService.readLines(
                serverIp, port, username, password, sourceFilePath,
                startLineNumber,
                startByteOffset,
                fileCharset,
                (lineNumber, byteOffsetAfterLine, lineText) -> {

                    // 이번 라인만큼 실제로 증가한 바이트 수(과도기 구간 제외 후 증분 크기 계산용).
                    long lineByteLength = byteOffsetAfterLine - lineReadState.lastByteOffset;

                    // 현재까지 읽은 마지막 라인번호와 바이트오프셋을 갱신한다.
                    // 파싱 가능 여부와 무관하게, 그리고 과도기 구간 여부와도 무관하게 항상 기록한다
                    // (다음 실행의 정확한 재개 지점이 되어야 하므로).
                    lineReadState.lastReadLineNumber = lineNumber;
                    lineReadState.lastByteOffset = byteOffsetAfterLine;

                    if (lineNumber <= finalSuppressBelowLineNumber) {
                        // 과도기 구간: 예전 방식으로 이미 수집된 라인 — 저장하지 않고,
                        // 증분 크기 한도 계산에도 포함하지 않는다(레거시 파일 전체 재전송량이
                        // 실제 신규 증분으로 오인되어 잘못 영구제외되는 것을 막기 위함).
                        return;
                    }

                    lineReadState.totalReadBytes += lineByteLength;

                    // 주기 수집 증분 크기 한도 초과 시 파싱을 중단한다.
                    // return은 이 람다(accept 호출)만 종료하므로 파일 읽기 루프는 계속된다.
                    // → 파일 끝까지 읽어 lastReadLineNumber를 정확히 추적하기 위한 의도적 설계이다.
                    if ("주기".equals(scheduleType)
                            && lineReadState.totalReadBytes >= CollectConstants.PART_COLLECT_SIZE_LIMIT_BYTES) {
                        lineReadState.exceededPartSizeLimit = true;
                        return;
                    }

                    // 라인에서 정규화 로그(@@@...@@@)를 파싱한다.
                    // 라인을 파싱 시도한다. @@@가 없는 일반 라인은 null 반환(실패 아님).
                    // @@@가 있으나 포맷 불일치 등 실제 오류는 parseFailures에 이유가 추가된다.
                    lineReadState.totalLineCount++;
                    CollectLog parsed = logNormalizeParser.parseNormalizedLogFromLine(lineText, lineNumber, parseFailures);
                    if (parsed != null) {
                        parsedLogs.add(parsed);
                    }
                }
        );

        // ── Step 7. 주기 수집 — 증분 크기 한도 초과 검사 ────────────────────────
        // 파일 읽기가 완료된 후 초과 여부를 판단한다.
        // 초과한 경우 이 파일은 이후 수집에서도 계속 증가할 가능성이 높으므로 영구 제외한다.
        if ("주기".equals(scheduleType) && lineReadState.exceededPartSizeLimit) {
            registerExclude(historyId, schedule, sourceFilePath, CollectConstants.EXCLUDE_REASON_PART_SIZE, fileSizeBytes,
                    "주기 수집 증분 크기 초과");
            updateHistorySkip(historyId, "PART_SIZE_EXCEEDED");
            return 0;
        }

        // ── Step 8. 파싱된 로그를 TB_COLLECT_LOG에 저장 ─────────────────────────
        // 수집 시각은 루프 전에 한 번만 조회하여 일관성을 유지한다. (collectDate는 Step 5에서 조회함)
        LocalDateTime now = LocalDateTime.now();

        for (CollectLog logRow : parsedLogs) {
            // 각 로그 레코드마다 SEQUENCE로 PK를 채번한다.
            Long collectLogId = sequenceHelper.nextval("SEQ_COLLECT_LOG");

            // 파서가 채운 logType, logId, logTimestamp 등 외에
            // 이 수집 실행 맥락에서만 알 수 있는 정보(서버, 파일, 수집일시 등)를 보완한다.
            logRow.setCollectLogId(collectLogId);
            logRow.setServerId(serverId);
            logRow.setServerIp(serverIp);
            logRow.setSourceFilePath(sourceFilePath);
            logRow.setCollectDate(collectDate);
            logRow.setCollectDatetime(now);
            logRow.setScheduleType(scheduleType);
            logRow.setCreatedAt(now);

            collectLogMapper.insert(logRow);
        }

        // ── Step 9. 수집 이력 성공 갱신 ──────────────────────────────────────────
        // lastProcessedLineNumber/lastProcessedByteOffset은 다음 주기 수집의 시작점이 된다.
        // 읽은 라인이 하나도 없는 경우(파일 변경 없음) startLineNumber - 1 / startByteOffset을 유지한다.
        long lastProcessedLineNumber = lineReadState.lastReadLineNumber;
        if (lastProcessedLineNumber < (startLineNumber - 1)) {
            lastProcessedLineNumber = startLineNumber - 1;
        }
        long lastProcessedByteOffset = lineReadState.lastByteOffset;

        updateHistorySuccess(historyId, (long) parsedLogs.size(), lastProcessedLineNumber, lastProcessedByteOffset, fileSizeBytes);

        log.info("수집 완료 - 서버: {}, 파일: {}, 타입: {}, 수집총라인수: {}, 정규화저장건수: {}, 정규화실패건수: {}",
                serverId, sourceFilePath, scheduleType,
                lineReadState.totalLineCount, parsedLogs.size(), parseFailures.size());
        if (!parseFailures.isEmpty()) {
            parseFailures.forEach(detail -> log.info("  └ {}", detail));
        }
        return parsedLogs.size();
    }

    /**
     * CollectException이 MAX_RETRY_COUNT를 초과하여 더 이상 재시도하지 않을 때 호출된다.
     *
     * 수집 이력을 최종 FAIL 상태로 갱신하고 실패 사유를 기록한다.
     * 메서드 시그니처(예외 타입, 파라미터 순서)는 @Retryable 메서드와 반드시 일치해야
     * Spring Retry가 올바른 @Recover 메서드를 찾을 수 있다.
     *
     * @param e 재시도를 소진시킨 최후의 예외
     */
    @Recover
    public int recover(
            CollectException e,
            Long historyId,
            CollectScheduleVo schedule,
            String scheduleType,
            int port,
            String username,
            String password
    ) {
        String causeDetail = e.getCause() != null ? e.getCause().getMessage() : null;
        String failReason = causeDetail != null ? e.getMessage() + " - 원인: " + causeDetail : e.getMessage();

        CollectHistory update = new CollectHistory();
        update.setCollectHistoryId(historyId);
        update.setCollectStatus(CollectConstants.STATUS_FAIL);
        update.setFailReason(failReason);
        update.setCollectEndAt(LocalDateTime.now());
        update.setUpdatedAt(LocalDateTime.now());
        collectHistoryMapper.updateCollectStatus(update);

        log.error("수집 재시도 모두 실패 - 서버: {}, 파일: {}, 사유: {}",
                schedule.getServerId(), schedule.getSourceFilePath(), failReason, e);
        return 0;
    }

    /**
     * 현재 재시도 횟수를 수집 이력에 갱신한다.
     *
     * 각 시도가 시작될 때 호출되어, 모니터링에서 현재 몇 번째 시도 중인지 파악할 수 있게 한다.
     */
    private void updateHistoryRetryCount(Long historyId, int retryCount) {
        CollectHistory update = new CollectHistory();
        update.setCollectHistoryId(historyId);
        update.setRetryCount((long) retryCount);
        update.setUpdatedAt(LocalDateTime.now());
        collectHistoryMapper.updateCollectStatus(update);
    }

    /**
     * 수집 이력을 SUCCESS 상태로 마무리한다.
     *
     * 정상 완료된 수집뿐 아니라, 신규 데이터가 없어 읽기 자체를 생략한 경우(Step 5-1)에도
     * 동일하게 사용한다 — 두 경우 모두 "성공적으로 최신 상태까지 처리됨"이라는 의미는 같다.
     */
    private void updateHistorySuccess(
            Long historyId,
            long collectedCount,
            long lastLineNumber,
            long lastByteOffset,
            long fileSizeBytes
    ) {
        CollectHistory update = new CollectHistory();
        update.setCollectHistoryId(historyId);
        update.setCollectStatus(CollectConstants.STATUS_SUCCESS);
        update.setCollectedCount(collectedCount);
        update.setLastLineNumber(lastLineNumber);
        update.setLastByteOffset(lastByteOffset);
        update.setFileSizeBytes(fileSizeBytes);
        update.setFailReason(null);
        update.setCollectEndAt(LocalDateTime.now());
        update.setUpdatedAt(LocalDateTime.now());
        collectHistoryMapper.updateCollectStatus(update);
    }

    /**
     * 수집 이력을 SKIP(건너뜀) 상태로 마무리한다.
     *
     * 영구 제외 대상이거나 크기 한도 초과인 경우 수집하지 않고 이 상태로 종료한다.
     * reason 값으로 건너뛴 사유를 식별한다(EXCLUDED / INIT_SIZE_EXCEEDED / PART_SIZE_EXCEEDED).
     */
    private void updateHistorySkip(Long historyId, String reason) {
        CollectHistory update = new CollectHistory();
        update.setCollectHistoryId(historyId);
        update.setCollectStatus(CollectConstants.STATUS_SKIP);
        update.setFailReason(reason);
        update.setCollectEndAt(LocalDateTime.now());
        update.setUpdatedAt(LocalDateTime.now());
        collectHistoryMapper.updateCollectStatus(update);
    }

    /**
     * 해당 서버·파일을 영구 제외 대상으로 등록하고 수집 이력에 파일 크기를 기록한다.
     *
     * TB_COLLECT_EXCLUDE에 등록되면 이후 수집 실행 시 Step 2에서 즉시 건너뛰게 된다.
     * 관리자가 수동으로 RESTORE_YN='Y'로 복원하지 않는 한 영구 제외 상태가 유지된다.
     */
    private void registerExclude(
            Long historyId,
            CollectScheduleVo schedule,
            String sourceFilePath,
            String excludeReason,
            long fileSizeBytes,
            String detail
    ) {
        // 제외 레코드 PK 채번 후 ExcludeService를 통해 TB_COLLECT_EXCLUDE에 등록한다.
        Long excludeId = sequenceHelper.nextval("SEQ_COLLECT_EXCLUDE");
        excludeService.registerExclude(
                excludeId,
                schedule.getServerId(),
                schedule.getServerIp(),
                sourceFilePath,
                excludeReason,
                fileSizeBytes,
                detail
        );

        // 수집 이력에도 해당 파일 크기를 기록하여 제외 사유를 추적할 수 있게 한다.
        CollectHistory update = new CollectHistory();
        update.setCollectHistoryId(historyId);
        update.setFileSizeBytes(fileSizeBytes);
        update.setUpdatedAt(LocalDateTime.now());
        collectHistoryMapper.updateCollectStatus(update);
    }

    /**
     * 파일 읽기 도중 상태를 추적하기 위한 내부 클래스.
     *
     * Java 람다는 effectively final 변수만 참조할 수 있어서
     * 람다 내부에서 변경한 값을 람다 외부로 전달할 수 없다.
     * 이 클래스를 이용하면 참조(객체 주소)는 고정하면서 내부 필드 값을 변경할 수 있다.
     *
     * 필드:
     *   lastReadLineNumber    - 람다가 마지막으로 처리한 라인번호. 다음 주기 수집의 시작점이 된다.
     *   lastByteOffset        - 람다가 마지막으로 도달한 누적 바이트 오프셋. 다음 주기 수집이
     *                           SFTP seek 시작점으로 그대로 사용한다.
     *   totalReadBytes        - 과도기 구간(레거시 라인)을 제외한, 이번 실행에서 새로 읽은 바이트 수.
     *                           주기 수집 증분 크기 한도 판단에 사용한다.
     *   totalLineCount        - 실제로 파싱을 시도한(과도기 구간 제외) 총 라인 수.
     *   exceededPartSizeLimit - 증분 크기 한도를 초과했는지 여부.
     */
    private static class LineReadState {
        private long lastReadLineNumber;
        private long lastByteOffset;
        private long totalReadBytes;
        private long totalLineCount;
        private boolean exceededPartSizeLimit;

        private LineReadState(long lastReadLineNumber, long lastByteOffset) {
            this.lastReadLineNumber = lastReadLineNumber;
            this.lastByteOffset = lastByteOffset;
        }
    }
}
