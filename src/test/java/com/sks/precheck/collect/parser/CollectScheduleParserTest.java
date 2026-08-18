package com.sks.precheck.collect.parser;

import static org.junit.jupiter.api.Assertions.*;

import com.sks.precheck.collect.common.exception.CollectException;
import com.sks.precheck.collect.vo.CollectScheduleVo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectScheduleParserTest {

    private final CollectScheduleParser parser = new CollectScheduleParser();

    @TempDir
    Path tempDir;

    private Path confFile(String content) throws Exception {
        Path file = tempDir.resolve("PreCheck_CollectLogs_Schedule.conf");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void parse_fourBracketLine_holidaySkipDefaultsFalse() throws Exception {
        Path file = confFile("[srv01][127.0.0.1][/tmp/a.log][주기|*|080000|1|235959]\n");

        List<CollectScheduleVo> result = parser.parseScheduleFile(file.toString());

        assertEquals(1, result.size());
        assertFalse(result.get(0).isHolidaySkip());
    }

    @Test
    void parse_fiveBracketLine_Y_setsHolidaySkipTrue() throws Exception {
        Path file = confFile("[srv01][127.0.0.1][/tmp/a.log][주기|*|080000|1|235959][Y]\n");

        List<CollectScheduleVo> result = parser.parseScheduleFile(file.toString());

        assertEquals(1, result.size());
        assertTrue(result.get(0).isHolidaySkip());
    }

    @Test
    void parse_fiveBracketLine_N_setsHolidaySkipFalse() throws Exception {
        Path file = confFile("[srv01][127.0.0.1][/tmp/a.log][주기|*|080000|1|235959][N]\n");

        List<CollectScheduleVo> result = parser.parseScheduleFile(file.toString());

        assertEquals(1, result.size());
        assertFalse(result.get(0).isHolidaySkip());
    }

    @Test
    void parse_fiveBracketLine_invalidFlag_lineIsIgnored() throws Exception {
        Path file = confFile(
                "[srv01][127.0.0.1][/tmp/a.log][주기|*|080000|1|235959][X]\n"
                        + "[srv02][127.0.0.1][/tmp/b.log][주기|*|080000|1|235959][y]\n"
        );

        List<CollectScheduleVo> result = parser.parseScheduleFile(file.toString());

        assertTrue(result.isEmpty());
    }

    @Test
    void parse_sixBracketLine_isIgnored() throws Exception {
        Path file = confFile("[srv01][127.0.0.1][/tmp/a.log][주기|*|080000|1|235959][Y][extra]\n");

        List<CollectScheduleVo> result = parser.parseScheduleFile(file.toString());

        assertTrue(result.isEmpty());
    }

    @Test
    void parse_commentLine_isIgnored() throws Exception {
        Path file = confFile(
                "#[srv01][127.0.0.1][/tmp/a.log][주기|*|080000|1|235959]\n"
                        + "[srv02][127.0.0.1][/tmp/b.log][배치|*|090000]\n"
        );

        List<CollectScheduleVo> result = parser.parseScheduleFile(file.toString());

        assertEquals(1, result.size());
        assertEquals("srv02", result.get(0).getServerId());
    }

    @Test
    void parse_blankLine_isIgnored() throws Exception {
        Path file = confFile("\n[srv02][127.0.0.1][/tmp/b.log][배치|*|090000]\n\n");

        List<CollectScheduleVo> result = parser.parseScheduleFile(file.toString());

        assertEquals(1, result.size());
    }

    @Test
    void parse_missingFile_throwsCollectException() {
        Path file = tempDir.resolve("does-not-exist.conf");

        assertThrows(CollectException.class, () -> parser.parseScheduleFile(file.toString()));
    }
}
