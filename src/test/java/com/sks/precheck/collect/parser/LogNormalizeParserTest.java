package com.sks.precheck.collect.parser;

import com.sks.precheck.collect.common.constants.CollectConstants;
import com.sks.precheck.collect.domain.CollectLog;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogNormalizeParserTest {

    private final LogNormalizeParser parser = new LogNormalizeParser();

    @Test
    void parse_numeric_valueAtEnd() {
        String line = "@@@[2026/05/01 09:00:01.123][수치][DISK_HOME]|홈디스크|$80$@@@";

        CollectLog result = parser.parseNormalizedLogFromLine(line, 1);

        assertNotNull(result);
        assertEquals(CollectConstants.LOG_TYPE_NUMERIC, result.getLogType());
        assertEquals("DISK_HOME", result.getLogId());
        assertEquals("홈디스크", result.getLogContent());
        assertEquals(new BigDecimal("80"), result.getLogValue());
    }

    @Test
    void parse_numeric_valueInMiddle() {
        String line = "@@@[2026/05/28 13:01:09.555][수치][PROC_AO_TR]|ao_tr_pro_nm PROCESS 갯수 $4$ 처리중|@@@";

        CollectLog result = parser.parseNormalizedLogFromLine(line, 1);

        assertNotNull(result);
        assertEquals(CollectConstants.LOG_TYPE_NUMERIC, result.getLogType());
        assertEquals("PROC_AO_TR", result.getLogId());
        assertEquals("ao_tr_pro_nm PROCESS 갯수 처리중", result.getLogContent());
        assertEquals(new BigDecimal("4"), result.getLogValue());
    }

    @Test
    void parse_numeric_rejectsTwoTokens() {
        String line = "@@@[2026/05/28 13:01:09.555][수치][PROC_AO_TR]|처리 $4$ 중 $5$|@@@";

        CollectLog result = parser.parseNormalizedLogFromLine(line, 1);

        assertNull(result);
    }

    @Test
    void parse_compare_acceptsTwoNumericTokens() {
        String line = "@@@[2026/05/01 09:00:01.123][비교][JUCHE_DIFF_01]|실시간정산 수신$123$ 처리$120$|@@@";

        CollectLog result = parser.parseNormalizedLogFromLine(line, 1);

        assertNotNull(result);
        assertEquals(CollectConstants.LOG_TYPE_COMPARE, result.getLogType());
        assertEquals("JUCHE_DIFF_01", result.getLogId());
        assertEquals("실시간정산 수신$123$ 처리$120$", result.getLogContent());
        assertNull(result.getLogValue());
    }

    @Test
    void parse_time_storesMinutes() {
        String line = "@@@[2026/05/01 09:00:01.123][시간][DATE_BTIME]|처리시간 $07:35$|@@@";

        CollectLog result = parser.parseNormalizedLogFromLine(line, 1);

        assertNotNull(result);
        assertEquals(CollectConstants.LOG_TYPE_TIME, result.getLogType());
        assertEquals("DATE_BTIME", result.getLogId());
        assertEquals("처리시간 $07:35$", result.getLogContent());
        assertEquals(new BigDecimal("455"), result.getLogValue());
    }

    @Test
    void parse_time_rejectsNumericToken() {
        String line = "@@@[2026/05/01 09:00:01.123][시간][DATE_BTIME]|처리시간 $735$|@@@";

        CollectLog result = parser.parseNormalizedLogFromLine(line, 1);

        assertNull(result);
    }

    @Test
    void parse_date_acceptsSlashToken() {
        String line = "@@@[2026/05/01 09:00:01.123][날짜][DATE_BDAY]|bday[$2026/04/22$] frec[12607]|@@@";

        CollectLog result = parser.parseNormalizedLogFromLine(line, 1);

        assertNotNull(result);
        assertEquals(CollectConstants.LOG_TYPE_DATE, result.getLogType());
        assertEquals("bday[$2026/04/22$] frec[12607]", result.getLogContent());
    }

    @Test
    void parse_date_acceptsDashToken() {
        String line = "@@@[2026/05/01 09:00:01.123][날짜][DATE_BDAY]|bday[$2026-04-22$] frec[12607]|@@@";

        CollectLog result = parser.parseNormalizedLogFromLine(line, 1);

        assertNotNull(result);
        assertEquals(CollectConstants.LOG_TYPE_DATE, result.getLogType());
    }

    @Test
    void parse_date_rejectsMissingToken() {
        String line = "@@@[2026/05/01 09:00:01.123][날짜][DATE_BDAY]|bday[2026/04/22] frec[12607]|@@@";

        CollectLog result = parser.parseNormalizedLogFromLine(line, 1);

        assertNull(result);
    }

    @Test
    void parse_date_rejectsInvalidCalendarDate() {
        String line = "@@@[2026/05/01 09:00:01.123][날짜][DATE_BDAY]|bday[$2026/02/30$]|@@@";

        CollectLog result = parser.parseNormalizedLogFromLine(line, 1);

        assertNull(result);
    }

    @Test
    void parse_acceptsLowercaseLogId() {
        String line = "@@@[2026/05/01 09:00:01.123][수치][disk_home]|홈디스크|$80$@@@";

        CollectLog result = parser.parseNormalizedLogFromLine(line, 1);

        assertNotNull(result);
        assertEquals("disk_home", result.getLogId());
    }

    @Test
    void parse_rejectsInvalidLogId() {
        String line = "@@@[2026/05/01 09:00:01.123][수치][disk-home]|홈디스크|$80$@@@";

        CollectLog result = parser.parseNormalizedLogFromLine(line, 1);

        assertNull(result);
    }
}
