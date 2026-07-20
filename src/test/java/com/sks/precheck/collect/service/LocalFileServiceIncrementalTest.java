package com.sks.precheck.collect.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 파일시스템에 대해 LocalFileService.readLines()를 구동해, 증분 수집(바이트오프셋 seek)이
 * mock 없이 실제로 정확히 동작하는지 검증한다.
 *
 * LocalFileService와 SftpService는 둘 다 FileReadService.readLinesFromStream()(공통 바이트
 * 분리 로직)을 그대로 위임해서 쓰므로, 여기서 검증하는 라인분리/오프셋 계산 정확성은
 * SftpService 경로에도 동일하게 적용된다(차이는 오직 스트림을 여는 방식뿐).
 */
class LocalFileServiceIncrementalTest {

    private final LocalFileService service = new LocalFileService();
    private Path tempFile;

    @BeforeEach
    void setUp() throws IOException {
        tempFile = Files.createTempFile("collect-incremental-test-", ".log");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(tempFile);
    }

    private record Captured(long lineNumber, long byteOffset, String text) {
    }

    private List<Captured> readAll(long startLineNumber, long startByteOffset) {
        List<Captured> result = new ArrayList<>();
        service.readLines(
                null, 0, null, null, tempFile.toString(),
                startLineNumber, startByteOffset, StandardCharsets.UTF_8,
                (lineNumber, byteOffset, text) -> result.add(new Captured(lineNumber, byteOffset, text)));
        return result;
    }

    @Test
    void 첫_수집은_offset0부터_전체를_읽는다() throws IOException {
        Files.writeString(tempFile, "line1\nline2\nline3\n", StandardCharsets.UTF_8);

        List<Captured> lines = readAll(1, 0);

        assertThat(lines).extracting(Captured::text).containsExactly("line1", "line2", "line3");
        assertThat(lines).extracting(Captured::lineNumber).containsExactly(1L, 2L, 3L);
        long fileSize = Files.size(tempFile);
        assertThat(lines.get(2).byteOffset()).isEqualTo(fileSize);
    }

    @Test
    void 두번째_수집은_저장된_오프셋부터만_읽고_이전_내용은_다시_전달하지_않는다() throws IOException {
        Files.writeString(tempFile, "line1\nline2\nline3\n", StandardCharsets.UTF_8);

        // 1차 수집 — 실제 CollectRetryService가 하는 것과 동일하게 마지막 라인번호/오프셋을 기록한다고 가정.
        List<Captured> firstRun = readAll(1, 0);
        Captured lastOfFirstRun = firstRun.get(firstRun.size() - 1);
        assertThat(lastOfFirstRun.lineNumber()).isEqualTo(3L);
        long resumeOffset = lastOfFirstRun.byteOffset();
        long resumeLineNumber = lastOfFirstRun.lineNumber() + 1;

        // 대상 서버가 로그를 계속 append 한다고 가정 — 파일 뒤에 새 라인만 추가.
        Files.writeString(tempFile, "line4\nline5\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        // 2차 수집 — 1차가 기록한 오프셋부터 seek해서 읽는다.
        List<Captured> secondRun = readAll(resumeLineNumber, resumeOffset);

        // 새로 추가된 2줄만 와야 하고, line1~line3는 다시 전달되면 안 된다(=재전송/중복 수집 없음).
        assertThat(secondRun).extracting(Captured::text).containsExactly("line4", "line5");
        assertThat(secondRun).extracting(Captured::lineNumber).containsExactly(4L, 5L);
    }

    @Test
    void 세번째_수집에서_신규데이터_없으면_아무_라인도_전달되지_않는다() throws IOException {
        Files.writeString(tempFile, "line1\nline2\n", StandardCharsets.UTF_8);
        List<Captured> firstRun = readAll(1, 0);
        Captured last = firstRun.get(firstRun.size() - 1);

        // 파일이 안 늘어난 상태에서 같은 오프셋으로 다시 읽으면 아무 것도 안 와야 한다.
        List<Captured> secondRun = readAll(last.lineNumber() + 1, last.byteOffset());

        assertThat(secondRun).isEmpty();
    }

    @Test
    void 개행없이_끝나는_마지막_라인도_전달된다() throws IOException {
        // 대상 서버가 로그를 쓰는 도중(마지막 줄에 아직 개행이 안 붙은 상태)을 흉내낸다.
        Files.writeString(tempFile, "line1\nline2", StandardCharsets.UTF_8);

        List<Captured> lines = readAll(1, 0);

        assertThat(lines).extracting(Captured::text).containsExactly("line1", "line2");
    }

    @Test
    void CRLF_라인도_CR이_내용에서_제거된채_정상_분리된다() throws IOException {
        Files.write(tempFile, "line1\r\nline2\r\n".getBytes(StandardCharsets.UTF_8));

        List<Captured> lines = readAll(1, 0);

        assertThat(lines).extracting(Captured::text).containsExactly("line1", "line2");
    }

    @Test
    void 한글_멀티바이트_라인도_오프셋_경계에서_깨지지_않는다() throws IOException {
        String korean1 = "@@@[2026/07/16 10:00:00.000][문구][TEST]|한글 내용 확인|@@@";
        String korean2 = "@@@[2026/07/16 10:00:01.000][문구][TEST2]|두번째 한글 라인|@@@";
        Files.writeString(tempFile, korean1 + "\n" + korean2 + "\n", StandardCharsets.UTF_8);

        List<Captured> firstRun = readAll(1, 0);
        assertThat(firstRun).extracting(Captured::text).containsExactly(korean1, korean2);

        long resumeOffset = firstRun.get(1).byteOffset();
        Files.writeString(tempFile, "세번째\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        List<Captured> secondRun = readAll(3, resumeOffset);
        assertThat(secondRun).extracting(Captured::text).containsExactly("세번째");
    }
}
