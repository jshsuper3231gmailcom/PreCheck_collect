package com.sks.precheck.collect.service;

import com.sks.precheck.collect.common.exception.CollectException;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 로컬 파일시스템에서 직접 파일을 읽는 FileReadService 구현체.
 *
 * precheck.collect.mode=local 일 때만 활성화된다.
 * serverIp / port / username / password 파라미터는 무시하고,
 * filePath를 로컬 절대 경로로 사용한다.
 *
 * OpenSSH를 설치할 수 없는 로컬 테스트 환경에서 SFTP 없이 수집을 검증할 때 사용한다.
 */
@Service
@ConditionalOnProperty(name = "precheck.collect.mode", havingValue = "local")
public class LocalFileService implements FileReadService {

    @Override
    public long getFileSizeBytes(String serverIp, int port, String username, String password, String filePath) {
        try {
            return Files.size(Path.of(filePath));
        } catch (IOException e) {
            throw new CollectException("로컬 파일 크기 조회 실패: " + filePath, e);
        }
    }

    @Override
    public void readLines(
            String serverIp,
            int port,
            String username,
            String password,
            String filePath,
            long startLineNumber,
            long startByteOffset,
            Charset charset,
            LineConsumer lineConsumer
    ) {
        Objects.requireNonNull(lineConsumer, "lineConsumer must not be null");
        if (startLineNumber < 1) {
            throw new CollectException("startLineNumber는 1 이상이어야 한다: " + startLineNumber);
        }
        if (startByteOffset < 0) {
            throw new CollectException("startByteOffset은 0 이상이어야 한다: " + startByteOffset);
        }

        Charset effectiveCharset = charset != null ? charset : StandardCharsets.UTF_8;

        // FileInputStream의 채널 position을 옮겨 시작 바이트 위치부터 바로 읽는다.
        // 로컬 파일이라 seek 비용이 사실상 없으므로 SFTP처럼 read 오프셋 API를 쓸 필요 없이
        // 채널 position 이동만으로 충분하다.
        try (FileInputStream in = new FileInputStream(filePath)) {
            if (startByteOffset > 0) {
                in.getChannel().position(startByteOffset);
            }
            FileReadService.readLinesFromStream(in, startByteOffset, startLineNumber, effectiveCharset, lineConsumer);
        } catch (IOException e) {
            throw new CollectException("로컬 파일 라인 읽기 실패: " + filePath, e);
        }
    }
}
