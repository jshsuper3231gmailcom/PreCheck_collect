package com.sks.precheck.collect.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * 파일 접근 추상화 인터페이스.
 *
 * 기본 구현은 SftpService(SFTP 원격 파일), 로컬 테스트용으로 LocalFileService(로컬 파일 직접 읽기)가 있다.
 * precheck.collect.mode 프로퍼티로 구현체를 선택한다.
 *   - sftp (기본값): SftpService
 *   - local: LocalFileService
 */
public interface FileReadService {

    /**
     * 파일 크기(bytes)를 반환한다.
     */
    long getFileSizeBytes(String serverIp, int port, String username, String password, String filePath);

    /**
     * filePath를 startByteOffset 바이트 위치부터 끝까지 읽어 lineConsumer로 전달한다.
     *
     * startLineNumber는 이 시작 지점의 라인번호(1-based)이며, TB_COLLECT_LOG.LINE_NUMBER 등
     * 파일 전체 기준 절대 라인번호를 유지하기 위해 필요하다(오프셋만으로는 몇 번째 라인인지 알 수 없다).
     * startByteOffset는 네트워크 전송량을 줄이기 위한 실제 읽기 시작 위치이다.
     *
     * @param startLineNumber startByteOffset 위치가 몇 번째 라인인지 (1-based)
     * @param startByteOffset 읽기 시작 바이트 위치 (0-based, 이전 수집이 여기까지 읽었음을 의미)
     */
    void readLines(
            String serverIp,
            int port,
            String username,
            String password,
            String filePath,
            long startLineNumber,
            long startByteOffset,
            Charset charset,
            LineConsumer lineConsumer
    );

    /**
     * 읽은 라인 1건을 전달하는 콜백.
     *
     * @param lineNumber        이 라인의 절대 라인번호 (1-based)
     * @param byteOffsetAfterLine 이 라인(개행 포함)까지 읽은 후의 누적 바이트 오프셋.
     *                            다음 수집의 시작 오프셋으로 그대로 저장하면 된다.
     * @param lineText          라인 내용 (개행 문자 제외)
     */
    @FunctionalInterface
    interface LineConsumer {
        void accept(long lineNumber, long byteOffsetAfterLine, String lineText);
    }

    /**
     * 이미 startByteOffset 위치로 이동(seek)된 InputStream을 라인 단위로 읽으며
     * 절대 라인번호와 누적 바이트 오프셋을 함께 계산해 lineConsumer로 전달한다.
     *
     * SftpService/LocalFileService가 공통으로 사용하는 바이트 레벨 라인 분리 로직이다.
     * 개행(LF, 0x0A)을 기준으로 라인을 자르며, 직전 바이트가 CR(0x0D)이면 함께 제거해
     * CRLF/LF 모두 BufferedReader.readLine()과 동일하게 처리한다.
     *
     * 바이트 단위로 LF를 찾는 것이 UTF-8/EUC-KR 등에서 안전한 이유: 두 인코딩 모두
     * ASCII 호환 멀티바이트 구조라 멀티바이트 문자의 후속 바이트 범위에 0x0A가 절대
     * 나타나지 않는다 (UTF-8 continuation byte는 0x80~0xBF, EUC-KR 2바이트는 0xA1~0xFE).
     * 즉 원본 바이트 그대로 LF를 찾아 잘라도 문자 중간을 끊지 않는다.
     */
    static void readLinesFromStream(
            InputStream in,
            long startByteOffset,
            long startLineNumber,
            Charset charset,
            LineConsumer lineConsumer
    ) throws IOException {
        ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);
        byte[] chunk = new byte[8192];
        long offset = startByteOffset;
        long lineNumber = startLineNumber - 1;
        int read;

        while ((read = in.read(chunk)) != -1) {
            for (int i = 0; i < read; i++) {
                byte b = chunk[i];
                offset++;
                if (b == '\n') {
                    lineNumber++;
                    lineConsumer.accept(lineNumber, offset, decodeLine(lineBuffer, charset));
                    lineBuffer.reset();
                } else {
                    lineBuffer.write(b);
                }
            }
        }

        // 파일이 개행 없이 끝나는 마지막 라인(수집 시점에 대상 서버가 쓰는 중인 라인)도
        // BufferedReader.readLine()과 동일하게 마지막 조각을 그대로 전달한다.
        if (lineBuffer.size() > 0) {
            lineNumber++;
            lineConsumer.accept(lineNumber, offset, decodeLine(lineBuffer, charset));
        }
    }

    private static String decodeLine(ByteArrayOutputStream lineBuffer, Charset charset) {
        byte[] bytes = lineBuffer.toByteArray();
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        return new String(bytes, 0, length, charset);
    }
}
