package com.sks.precheck.collect.scheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sks.precheck.collect.service.CollectService;
import com.sks.precheck.collect.vo.CollectScheduleVo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectSchedulerTest {

    @TempDir
    Path tempDir;

    private CollectScheduleVo schedule(boolean holidaySkip) {
        CollectScheduleVo vo = new CollectScheduleVo();
        vo.setServerId("srv01");
        vo.setServerIp("127.0.0.1");
        vo.setSourceFilePath("/tmp/a.log");
        vo.setScheduleExpression("주기|*|080000|1|235959");
        vo.setHolidaySkip(holidaySkip);
        return vo;
    }

    private CollectScheduler scheduler(String holidayFilePath) {
        return new CollectScheduler(
                mock(CollectService.class),
                "",
                "",
                "local",
                22,
                "u",
                "p",
                60000L,
                holidayFilePath
        );
    }

    private Path holidayFileWithDate(String yyyyMmDd) throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyHoliday_List.conf");
        Files.writeString(file, yyyyMmDd + "\n", StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void shouldRun_holidaySkipFalse_holidayToday_stillRuns() throws Exception {
        Path holidayFile = holidayFileWithDate("20260818");
        CollectScheduler scheduler = scheduler(holidayFile.toString());
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 8, 0, 0);

        assertTrue(scheduler.shouldRun(schedule(false), now));
    }

    @Test
    void shouldRun_holidaySkipTrue_holidayToday_returnsFalse() throws Exception {
        Path holidayFile = holidayFileWithDate("20260818");
        CollectScheduler scheduler = scheduler(holidayFile.toString());
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 8, 0, 0);

        assertFalse(scheduler.shouldRun(schedule(true), now));
    }

    @Test
    void shouldRun_holidaySkipTrue_notHoliday_runsNormally() throws Exception {
        Path holidayFile = holidayFileWithDate("20260101");
        CollectScheduler scheduler = scheduler(holidayFile.toString());
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 8, 0, 0);

        assertTrue(scheduler.shouldRun(schedule(true), now));
    }

    @Test
    void shouldRun_holidaySkipTrue_missingHolidayFile_treatedAsNoHolidays_runsNormally() {
        Path missingFile = tempDir.resolve("does-not-exist.conf");
        CollectScheduler scheduler = scheduler(missingFile.toString());
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 8, 0, 0);

        assertTrue(scheduler.shouldRun(schedule(true), now));
    }

    @Test
    void getHolidays_parsesConfiguredFile() throws Exception {
        Path holidayFile = holidayFileWithDate("20260101");
        CollectScheduler scheduler = scheduler(holidayFile.toString());

        assertTrue(scheduler.getHolidays().contains(java.time.LocalDate.of(2026, 1, 1)));
    }

    @Test
    void shouldRun_holidaySkipTrue_holidayToday_recordsSkipHistory() throws Exception {
        Path holidayFile = holidayFileWithDate("20260818");
        CollectService collectService = mock(CollectService.class);
        CollectScheduler scheduler = new CollectScheduler(
                collectService, "", "", "local", 22, "u", "p", 60000L, holidayFile.toString());
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 8, 0, 0);
        CollectScheduleVo schedule = schedule(true);

        scheduler.shouldRun(schedule, now);

        verify(collectService).recordHolidaySkip(eq(schedule), eq("주기"));
    }

    @Test
    void shouldRun_holidaySkipTrue_holidayToday_recordsOnlyOncePerDay() throws Exception {
        Path holidayFile = holidayFileWithDate("20260818");
        CollectService collectService = mock(CollectService.class);
        CollectScheduler scheduler = new CollectScheduler(
                collectService, "", "", "local", 22, "u", "p", 60000L, holidayFile.toString());
        CollectScheduleVo schedule = schedule(true);

        scheduler.shouldRun(schedule, LocalDateTime.of(2026, 8, 18, 8, 0, 0));
        scheduler.shouldRun(schedule, LocalDateTime.of(2026, 8, 18, 8, 0, 1));
        scheduler.shouldRun(schedule, LocalDateTime.of(2026, 8, 18, 12, 30, 0));

        verify(collectService, times(1)).recordHolidaySkip(any(), any());
    }

    @Test
    void shouldRun_holidaySkipFalse_holidayToday_neverRecordsSkip() throws Exception {
        Path holidayFile = holidayFileWithDate("20260818");
        CollectService collectService = mock(CollectService.class);
        CollectScheduler scheduler = new CollectScheduler(
                collectService, "", "", "local", 22, "u", "p", 60000L, holidayFile.toString());
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 8, 0, 0);

        scheduler.shouldRun(schedule(false), now);

        verify(collectService, never()).recordHolidaySkip(any(), any());
    }
}
