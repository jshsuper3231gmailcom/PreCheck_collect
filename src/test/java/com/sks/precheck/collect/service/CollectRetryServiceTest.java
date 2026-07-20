package com.sks.precheck.collect.service;

import com.sks.precheck.collect.common.util.DateUtil;
import com.sks.precheck.collect.common.util.SequenceHelper;
import com.sks.precheck.collect.domain.CollectHistory;
import com.sks.precheck.collect.domain.CollectResumePoint;
import com.sks.precheck.collect.mapper.CollectHistoryMapper;
import com.sks.precheck.collect.mapper.CollectLogMapper;
import com.sks.precheck.collect.vo.CollectScheduleVo;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 수집 대상 파일 경로 전처리(yyyymmdd 치환, '+' 접미사 처리)와
 * 날짜 기준 라인번호 리셋 로직을 검증한다.
 */
class CollectRetryServiceTest {

    private CollectHistoryMapper collectHistoryMapper;
    private FileReadService fileReadService;
    private CollectRetryService collectRetryService;

    @BeforeEach
    void setUp() {
        SequenceHelper sequenceHelper = mock(SequenceHelper.class);
        CollectLogMapper collectLogMapper = mock(CollectLogMapper.class);
        collectHistoryMapper = mock(CollectHistoryMapper.class);
        ExcludeService excludeService = mock(ExcludeService.class);
        fileReadService = mock(FileReadService.class);

        when(excludeService.isExcluded(anyString(), anyString())).thenReturn(false);
        when(fileReadService.getFileSizeBytes(any(), anyInt(), any(), any(), anyString())).thenReturn(100L);
        // byteOffset(10L)을 fileSizeBytes(100L)보다 작게 둬서 Step 5-1 "신규 데이터 없음" 생략 경로를
        // 타지 않고 기존 테스트들이 검증하는 정상 readLines 경로를 그대로 통과하게 한다.
        when(collectHistoryMapper.findResumePoint(anyString(), anyString(), any()))
                .thenReturn(new CollectResumePoint(50L, 10L));

        collectRetryService = new CollectRetryService(
                sequenceHelper, collectLogMapper, collectHistoryMapper, excludeService, fileReadService);
    }

    private CollectScheduleVo schedule(String sourceFilePath) {
        CollectScheduleVo vo = new CollectScheduleVo();
        vo.setServerId("srv01");
        vo.setServerIp("192.168.0.1");
        vo.setSourceFilePath(sourceFilePath);
        vo.setScheduleExpression("주기|1-7|000000|10|235959");
        return vo;
    }

    @Test
    void yyyymmdd_플레이스홀더는_오늘날짜로_치환된다() {
        String today = DateUtil.todayCollectDate();

        collectRetryService.collectWithRetry(1L, schedule("/logs/test.yyyymmdd"), "주기", 22, "user", "pass");

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileReadService).getFileSizeBytes(any(), anyInt(), any(), any(), pathCaptor.capture());
        assertThat(pathCaptor.getValue()).isEqualTo("/logs/test." + today);

        verify(collectHistoryMapper).findResumePoint("srv01", "/logs/test." + today, today);
    }

    @Test
    void plus_접미사는_경로에서_제거되고_날짜리셋이_비활성화된다() {
        collectRetryService.collectWithRetry(1L, schedule("/logs/test.log+"), "주기", 22, "user", "pass");

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileReadService).getFileSizeBytes(any(), anyInt(), any(), any(), pathCaptor.capture());
        assertThat(pathCaptor.getValue()).isEqualTo("/logs/test.log");

        // collectDate=null 로 조회 → 날짜가 바뀌어도 라인번호 리셋되지 않음
        verify(collectHistoryMapper).findResumePoint("srv01", "/logs/test.log", null);
    }

    @Test
    void plus_접미사가_없으면_오늘날짜로_findResumePoint를_조회한다() {
        String today = DateUtil.todayCollectDate();

        collectRetryService.collectWithRetry(1L, schedule("/logs/test.log"), "주기", 22, "user", "pass");

        verify(collectHistoryMapper).findResumePoint("srv01", "/logs/test.log", today);
    }

    @Test
    void mmdd_플레이스홀더는_오늘월일로_치환된다() {
        String today = DateUtil.todayCollectDate();
        String monthDay = today.substring(4);

        collectRetryService.collectWithRetry(1L, schedule("/logs/test.mmdd"), "주기", 22, "user", "pass");

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileReadService).getFileSizeBytes(any(), anyInt(), any(), any(), pathCaptor.capture());
        assertThat(pathCaptor.getValue()).isEqualTo("/logs/test." + monthDay);

        verify(collectHistoryMapper).findResumePoint("srv01", "/logs/test." + monthDay, today);
    }

    @Test
    void dollar_플레이스홀더는_오늘요일숫자로_치환된다() {
        String today = DateUtil.todayCollectDate();
        int dayOfWeekDigit = LocalDate.now().getDayOfWeek().getValue() % 7;

        collectRetryService.collectWithRetry(1L, schedule("/logs/sys0$.log"), "주기", 22, "user", "pass");

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileReadService).getFileSizeBytes(any(), anyInt(), any(), any(), pathCaptor.capture());
        assertThat(pathCaptor.getValue()).isEqualTo("/logs/sys0" + dayOfWeekDigit + ".log");

        verify(collectHistoryMapper).findResumePoint("srv01", "/logs/sys0" + dayOfWeekDigit + ".log", today);
    }

    @Test
    void yyyymmdd와_plus를_함께_사용할_수_있다() {
        String today = DateUtil.todayCollectDate();

        collectRetryService.collectWithRetry(1L, schedule("/logs/test.yyyymmdd+"), "주기", 22, "user", "pass");

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileReadService).getFileSizeBytes(any(), anyInt(), any(), any(), pathCaptor.capture());
        assertThat(pathCaptor.getValue()).isEqualTo("/logs/test." + today);

        verify(collectHistoryMapper).findResumePoint("srv01", "/logs/test." + today, null);
    }

    @Test
    void 마지막오프셋이_파일크기_이상이면_SFTP_읽기를_생략한다() {
        // fileSizeBytes(100L, setUp)와 같은 오프셋 → 신규 데이터 없음 → readLines 자체를 호출하지 않아야 한다.
        when(collectHistoryMapper.findResumePoint(anyString(), anyString(), any()))
                .thenReturn(new CollectResumePoint(50L, 100L));

        int result = collectRetryService.collectWithRetry(1L, schedule("/logs/test.log+"), "주기", 22, "user", "pass");

        assertThat(result).isEqualTo(0);
        verify(fileReadService, never()).readLines(
                any(), anyInt(), any(), any(), anyString(), anyLong(), anyLong(), any(), any());

        ArgumentCaptor<CollectHistory> historyCaptor = ArgumentCaptor.forClass(CollectHistory.class);
        verify(collectHistoryMapper, org.mockito.Mockito.atLeastOnce()).updateCollectStatus(historyCaptor.capture());
        CollectHistory successUpdate = historyCaptor.getAllValues().stream()
                .filter(h -> "SUCCESS".equals(h.getCollectStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(successUpdate.getCollectedCount()).isEqualTo(0L);
        assertThat(successUpdate.getLastLineNumber()).isEqualTo(50L);
        assertThat(successUpdate.getLastByteOffset()).isEqualTo(100L);
    }

    @Test
    void resumePoint의_바이트오프셋이_readLines_시작위치로_그대로_전달된다() {
        when(collectHistoryMapper.findResumePoint(anyString(), anyString(), any()))
                .thenReturn(new CollectResumePoint(50L, 30L));

        collectRetryService.collectWithRetry(1L, schedule("/logs/test.log+"), "주기", 22, "user", "pass");

        ArgumentCaptor<Long> lineNumberCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> byteOffsetCaptor = ArgumentCaptor.forClass(Long.class);
        verify(fileReadService).readLines(
                any(), anyInt(), any(), any(), anyString(),
                lineNumberCaptor.capture(), byteOffsetCaptor.capture(), any(), any());

        assertThat(lineNumberCaptor.getValue()).isEqualTo(51L);
        assertThat(byteOffsetCaptor.getValue()).isEqualTo(30L);
    }
}
