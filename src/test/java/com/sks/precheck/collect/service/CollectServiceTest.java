package com.sks.precheck.collect.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sks.precheck.collect.common.util.SequenceHelper;
import com.sks.precheck.collect.domain.CollectHistory;
import com.sks.precheck.collect.mapper.CollectHistoryMapper;
import com.sks.precheck.collect.vo.CollectScheduleVo;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CollectServiceTest {

    private CollectScheduleVo schedule() {
        CollectScheduleVo vo = new CollectScheduleVo();
        vo.setServerId("srv01");
        vo.setServerIp("127.0.0.1");
        vo.setSourceFilePath("/tmp/a.log");
        vo.setScheduleExpression("주기|*|080000|1|235959");
        vo.setHolidaySkip(true);
        return vo;
    }

    @Test
    void recordHolidaySkip_insertsSkipRowWithHolidayReason() {
        SequenceHelper sequenceHelper = mock(SequenceHelper.class);
        CollectHistoryMapper collectHistoryMapper = mock(CollectHistoryMapper.class);
        CollectRetryService collectRetryService = mock(CollectRetryService.class);
        org.mockito.Mockito.when(sequenceHelper.nextval("SEQ_COLLECT_HISTORY")).thenReturn(1234L);
        CollectService collectService = new CollectService(sequenceHelper, collectHistoryMapper, collectRetryService);

        collectService.recordHolidaySkip(schedule(), "주기");

        ArgumentCaptor<CollectHistory> captor = ArgumentCaptor.forClass(CollectHistory.class);
        verify(collectHistoryMapper).insert(captor.capture());
        CollectHistory inserted = captor.getValue();

        assertEquals(1234L, inserted.getCollectHistoryId());
        assertEquals("srv01", inserted.getServerId());
        assertEquals("127.0.0.1", inserted.getServerIp());
        assertEquals("주기", inserted.getScheduleType());
        assertEquals("SKIP", inserted.getCollectStatus());
        assertEquals("HOLIDAY_SKIP", inserted.getFailReason());
        assertEquals(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")), inserted.getCollectDate());
        assertNotNull(inserted.getCollectStartAt());
        assertNotNull(inserted.getCollectEndAt());
    }

    @Test
    void recordHolidaySkip_doesNotCallRetryService() {
        SequenceHelper sequenceHelper = mock(SequenceHelper.class);
        CollectHistoryMapper collectHistoryMapper = mock(CollectHistoryMapper.class);
        CollectRetryService collectRetryService = mock(CollectRetryService.class);
        CollectService collectService = new CollectService(sequenceHelper, collectHistoryMapper, collectRetryService);

        collectService.recordHolidaySkip(schedule(), "주기");

        verifyNoInteractions(collectRetryService);
    }
}
