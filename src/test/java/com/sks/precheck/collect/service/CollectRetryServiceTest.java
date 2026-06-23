package com.sks.precheck.collect.service;

import com.sks.precheck.collect.common.util.DateUtil;
import com.sks.precheck.collect.common.util.SequenceHelper;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
        when(collectHistoryMapper.findLastLineNumber(anyString(), anyString(), any())).thenReturn(50L);

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

        verify(collectHistoryMapper).findLastLineNumber("srv01", "/logs/test." + today, today);
    }

    @Test
    void plus_접미사는_경로에서_제거되고_날짜리셋이_비활성화된다() {
        collectRetryService.collectWithRetry(1L, schedule("/logs/test.log+"), "주기", 22, "user", "pass");

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileReadService).getFileSizeBytes(any(), anyInt(), any(), any(), pathCaptor.capture());
        assertThat(pathCaptor.getValue()).isEqualTo("/logs/test.log");

        // collectDate=null 로 조회 → 날짜가 바뀌어도 라인번호 리셋되지 않음
        verify(collectHistoryMapper).findLastLineNumber("srv01", "/logs/test.log", null);
    }

    @Test
    void plus_접미사가_없으면_오늘날짜로_findLastLineNumber를_조회한다() {
        String today = DateUtil.todayCollectDate();

        collectRetryService.collectWithRetry(1L, schedule("/logs/test.log"), "주기", 22, "user", "pass");

        verify(collectHistoryMapper).findLastLineNumber("srv01", "/logs/test.log", today);
    }

    @Test
    void mmdd_플레이스홀더는_오늘월일로_치환된다() {
        String today = DateUtil.todayCollectDate();
        String monthDay = today.substring(4);

        collectRetryService.collectWithRetry(1L, schedule("/logs/test.mmdd"), "주기", 22, "user", "pass");

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileReadService).getFileSizeBytes(any(), anyInt(), any(), any(), pathCaptor.capture());
        assertThat(pathCaptor.getValue()).isEqualTo("/logs/test." + monthDay);

        verify(collectHistoryMapper).findLastLineNumber("srv01", "/logs/test." + monthDay, today);
    }

    @Test
    void dollar_플레이스홀더는_오늘요일숫자로_치환된다() {
        String today = DateUtil.todayCollectDate();
        int dayOfWeekDigit = LocalDate.now().getDayOfWeek().getValue() % 7;

        collectRetryService.collectWithRetry(1L, schedule("/logs/sys0$.log"), "주기", 22, "user", "pass");

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileReadService).getFileSizeBytes(any(), anyInt(), any(), any(), pathCaptor.capture());
        assertThat(pathCaptor.getValue()).isEqualTo("/logs/sys0" + dayOfWeekDigit + ".log");

        verify(collectHistoryMapper).findLastLineNumber("srv01", "/logs/sys0" + dayOfWeekDigit + ".log", today);
    }

    @Test
    void yyyymmdd와_plus를_함께_사용할_수_있다() {
        String today = DateUtil.todayCollectDate();

        collectRetryService.collectWithRetry(1L, schedule("/logs/test.yyyymmdd+"), "주기", 22, "user", "pass");

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileReadService).getFileSizeBytes(any(), anyInt(), any(), any(), pathCaptor.capture());
        assertThat(pathCaptor.getValue()).isEqualTo("/logs/test." + today);

        verify(collectHistoryMapper).findLastLineNumber("srv01", "/logs/test." + today, null);
    }
}
