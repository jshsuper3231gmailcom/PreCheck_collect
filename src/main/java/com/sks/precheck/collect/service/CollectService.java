package com.sks.precheck.collect.service;

import com.sks.precheck.collect.common.constants.CollectConstants;
import com.sks.precheck.collect.common.exception.CollectException;
import com.sks.precheck.collect.common.util.DateUtil;
import com.sks.precheck.collect.common.util.SequenceHelper;
import com.sks.precheck.collect.domain.CollectHistory;
import com.sks.precheck.collect.mapper.CollectHistoryMapper;
import com.sks.precheck.collect.vo.CollectScheduleVo;
import java.time.LocalDateTime;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 수집 진입점 서비스.
 *
 * 역할:
 *   1) 수집 이력(TB_COLLECT_HISTORY)을 DB에 먼저 생성하여 수집 시작을 기록한다.
 *   2) 실제 수집·재시도 처리는 CollectRetryService에 위임한다.
 *
 * 설계 이유:
 *   Spring의 @Retryable은 AOP 프록시를 통해서만 동작한다.
 *   같은 클래스 안에서 @Retryable 메서드를 직접 호출하면 프록시를 우회하여 재시도가 작동하지 않는다.
 *   이 문제를 피하기 위해 재시도 로직을 CollectRetryService라는 별도 빈으로 분리하고,
 *   이 클래스에서는 Spring 컨텍스트가 주입한 프록시 빈을 통해 호출한다.
 */
@Service
public class CollectService {

    private final SequenceHelper sequenceHelper;
    private final CollectHistoryMapper collectHistoryMapper;

    // AOP 프록시를 통해 @Retryable이 실제로 동작하도록 외부 빈으로 주입받는다.
    private final CollectRetryService collectRetryService;

    public CollectService(
            SequenceHelper sequenceHelper,
            CollectHistoryMapper collectHistoryMapper,
            CollectRetryService collectRetryService
    ) {
        this.sequenceHelper = sequenceHelper;
        this.collectHistoryMapper = collectHistoryMapper;
        this.collectRetryService = collectRetryService;
    }

    /**
     * 수집을 비동기로 실행한다.
     *
     * 처리 순서:
     *   1. 스케줄 표현식에서 수집 타입(배치/주기)을 파싱한다.
     *   2. 수집 이력 PK를 SEQUENCE로 채번한다.
     *   3. 수집 이력을 STATUS_FAIL / IN_PROGRESS 상태로 먼저 INSERT한다.
     *      → 수집이 중간에 서버가 다운되어도 이력이 남아 추적 가능하도록 한다.
     *   4. CollectRetryService.collectWithRetry()를 호출하여 실제 수집을 위임한다.
     *      → 수집 성공/실패/제외 여부에 따라 이력 상태는 collectWithRetry 내부에서 갱신된다.
     *
     * @Async("collectExecutor") 로 인해 collectExecutor 스레드풀에서 실행된다.
     * 재시도 backoff sleep이 스케줄러 스레드를 블로킹하지 않는다.
     *
     * @param schedule 스케줄 정보 (서버 IP, 파일 경로, 스케줄 표현식 포함)
     * @param port     SSH 포트(보통 22)
     * @param username SFTP 계정
     * @param password SFTP 비밀번호
     */
    @Async("collectExecutor")
    public void collect(CollectScheduleVo schedule, int port, String username, String password) {

        // ── Step 1. 스케줄 표현식에서 수집 타입 추출 ──────────────────────────────
        // 표현식 형식: "배치|..." 또는 "주기|..."
        // 표현식이 잘못된 경우 CollectException을 던져 수집 자체를 중단한다.
        String scheduleType = parseScheduleType(schedule.getScheduleExpression());

        // ── Step 2. 수집 이력 PK 채번 ─────────────────────────────────────────────
        Long historyId = sequenceHelper.nextval("SEQ_COLLECT_HISTORY");

        // ── Step 3. 수집 이력 선등록 (수집 시작 마킹) ────────────────────────────
        // 초기 상태는 FAIL + IN_PROGRESS로 등록한다.
        // 이후 수집 결과에 따라 collectWithRetry 내부에서 SUCCESS / SKIP / FAIL로 갱신된다.
        // findLastLineNumber는 실제 수집에 쓰이는(날짜 치환 완료) 경로로 조회하므로,
        // 이력에도 같은 경로를 저장해야 한다. 원본 템플릿을 그대로 저장하면 경로가
        // 일치하지 않아 직전 수집 위치를 찾지 못하고 매번 파일을 처음부터 재수집한다.
        String collectDate = DateUtil.todayCollectDate();
        String resolvedSourceFilePath = DateUtil.resolveActualFilePath(schedule.getSourceFilePath(), collectDate);

        CollectHistory history = new CollectHistory();
        history.setCollectHistoryId(historyId);
        history.setServerId(schedule.getServerId());
        history.setServerIp(schedule.getServerIp());
        history.setSourceFilePath(resolvedSourceFilePath);
        history.setScheduleType(scheduleType);
        history.setCollectStatus(CollectConstants.STATUS_FAIL);
        history.setRetryCount(0L);
        history.setCollectStartAt(LocalDateTime.now());
        history.setCollectDate(collectDate);
        history.setCreatedAt(LocalDateTime.now());
        history.setUpdatedAt(LocalDateTime.now());
        history.setFailReason("IN_PROGRESS");
        collectHistoryMapper.insert(history);

        // ── Step 4. 실제 수집 위임 (재시도 포함) ─────────────────────────────────
        // CollectRetryService의 AOP 프록시를 통해 호출해야 @Retryable이 정상 동작한다.
        collectRetryService.collectWithRetry(historyId, schedule, scheduleType, port, username, password);
    }

    /**
     * 휴장일(비영업일)로 건너뛴 스케줄 항목을 이력에 SKIP으로 기록한다.
     * 실제 수집 시도가 없으므로 IN_PROGRESS 선등록 없이 SKIP 상태로 곧바로 1건 INSERT한다.
     */
    public void recordHolidaySkip(CollectScheduleVo schedule, String scheduleType) {
        Long historyId = sequenceHelper.nextval("SEQ_COLLECT_HISTORY");
        String collectDate = DateUtil.todayCollectDate();
        String resolvedSourceFilePath = DateUtil.resolveActualFilePath(schedule.getSourceFilePath(), collectDate);

        CollectHistory history = new CollectHistory();
        history.setCollectHistoryId(historyId);
        history.setServerId(schedule.getServerId());
        history.setServerIp(schedule.getServerIp());
        history.setSourceFilePath(resolvedSourceFilePath);
        history.setScheduleType(scheduleType);
        history.setCollectStatus(CollectConstants.STATUS_SKIP);
        history.setRetryCount(0L);
        history.setCollectStartAt(LocalDateTime.now());
        history.setCollectEndAt(LocalDateTime.now());
        history.setCollectDate(collectDate);
        history.setCreatedAt(LocalDateTime.now());
        history.setUpdatedAt(LocalDateTime.now());
        history.setFailReason("HOLIDAY_SKIP");
        collectHistoryMapper.insert(history);
    }

    /**
     * 스케줄 표현식에서 수집 타입(배치/주기)을 파싱한다.
     *
     * 표현식 형식: "배치|크론표현식" 또는 "주기|인터벌"
     * '|'로 분리한 첫 번째 토큰이 수집 타입이며 "배치" 또는 "주기"만 허용한다.
     * 그 외 값이 오면 잘못된 스케줄 설정으로 보고 CollectException을 던진다.
     */
    private String parseScheduleType(String scheduleExpression) {
        if (scheduleExpression == null || scheduleExpression.isEmpty()) {
            throw new CollectException("수집주기기술이 비어있다");
        }

        // '|'를 구분자로 분리. -1 limit으로 빈 토큰도 보존한다.
        String[] parts = scheduleExpression.split("\\|", -1);
        if (parts.length == 0) {
            throw new CollectException("수집주기기술 포맷 오류: " + scheduleExpression);
        }

        String type = parts[0].trim();
        if ("배치".equals(type) || "주기".equals(type)) {
            return type;
        }
        throw new CollectException("수집주기기술 타입 오류: " + scheduleExpression);
    }

}
