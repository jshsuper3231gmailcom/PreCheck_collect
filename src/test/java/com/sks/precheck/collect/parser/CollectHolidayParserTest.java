package com.sks.precheck.collect.parser;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectHolidayParserTest {

    private final CollectHolidayParser parser = new CollectHolidayParser();

    @TempDir
    Path tempDir;

    @Test
    void parse_plainDates() throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyHoliday_List.conf");
        Files.writeString(file, "20260101\n20260919\n", StandardCharsets.UTF_8);

        Set<LocalDate> holidays = parser.parseHolidayFile(file.toString());
        assertEquals(Set.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 19)), holidays);
    }

    @Test
    void parse_skipLine_isIgnored() throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyHoliday_List.conf");
        Files.writeString(file, "#20260101\n20260919\n", StandardCharsets.UTF_8);

        Set<LocalDate> holidays = parser.parseHolidayFile(file.toString());
        assertEquals(Set.of(LocalDate.of(2026, 9, 19)), holidays);
    }

    @Test
    void parse_blankLine_isIgnored() throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyHoliday_List.conf");
        Files.writeString(file, "\n20260919\n\n", StandardCharsets.UTF_8);

        Set<LocalDate> holidays = parser.parseHolidayFile(file.toString());
        assertEquals(Set.of(LocalDate.of(2026, 9, 19)), holidays);
    }

    @Test
    void parse_invalidFormat_lineIsSkipped_restStillParsed() throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyHoliday_List.conf");
        Files.writeString(file, "2026-01-01\nabcdefgh\n20260919\n", StandardCharsets.UTF_8);

        Set<LocalDate> holidays = parser.parseHolidayFile(file.toString());
        assertEquals(Set.of(LocalDate.of(2026, 9, 19)), holidays);
    }

    @Test
    void parse_duplicateDates_areMerged() throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyHoliday_List.conf");
        Files.writeString(file, "20260919\n20260919\n", StandardCharsets.UTF_8);

        Set<LocalDate> holidays = parser.parseHolidayFile(file.toString());
        assertEquals(Set.of(LocalDate.of(2026, 9, 19)), holidays);
    }

    @Test
    void parse_missingFile_returnsEmptySet() {
        Path file = tempDir.resolve("does-not-exist.conf");

        Set<LocalDate> holidays = parser.parseHolidayFile(file.toString());
        assertTrue(holidays.isEmpty());
    }

    @Test
    void parse_emptyFile_returnsEmptySet() throws Exception {
        Path file = tempDir.resolve("PreCheck_NotifyHoliday_List.conf");
        Files.writeString(file, "", StandardCharsets.UTF_8);

        Set<LocalDate> holidays = parser.parseHolidayFile(file.toString());
        assertTrue(holidays.isEmpty());
    }
}
