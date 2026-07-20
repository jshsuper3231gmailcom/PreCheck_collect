package com.sks.precheck.collect.service;

import com.sks.precheck.collect.common.exception.CollectException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * SSHJ 라이브러리를 사용한 SFTP 원격 파일 접근 서비스.
 *
 * 역할:
 *   원격 서버에 SSH로 접속하여 SFTP 프로토콜로 파일을 다룬다.
 *   - 파일 크기 조회 (getFileSizeBytes)
 *   - 특정 라인부터 파일을 라인 단위로 읽어 콜백으로 전달 (readLines)
 *
 * 의존성:
 *   SSHJ(net.schmizz:sshj) — SSH/SFTP 구현체.
 *   PromiscuousVerifier 사용으로 호스트 키 검증을 생략한다.
 *   (내부망 전용 구성이므로 보안 정책상 허용. 외부망이라면 KnownHostsVerifier 적용 필요)
 *
 * 연결 생명주기:
 *   모든 public 메서드는 try-with-resources로 SSHClient를 관리하여
 *   메서드 종료 시 자동으로 연결을 닫는다. 연결을 재사용하지 않으므로 스레드 세이프하다.
 */
@Service
@ConditionalOnProperty(name = "precheck.collect.mode", havingValue = "sftp", matchIfMissing = true)
public class SftpService implements FileReadService {

    private static final Logger log = LogManager.getLogger(SftpService.class);

    /**
     * 원격 파일의 크기(bytes)를 조회한다.
     *
     * SFTP stat 명령을 사용하여 파일 메타데이터에서 크기를 가져온다.
     * 파일이 존재하지 않거나 접속에 실패하면 CollectException을 던진다.
     * 이 값은 수집 크기 한도 초과 여부 판단과 수집 이력 기록에 사용된다.
     *
     * @param serverIp       원격 서버 IP 주소
     * @param port           SSH 포트 (보통 22)
     * @param username       SFTP 계정
     * @param password       SFTP 비밀번호
     * @param remoteFilePath 원격 파일의 절대 경로
     * @return 파일 크기 (bytes)
     * @throws CollectException SFTP 접속 또는 stat 조회 실패 시
     */
    public long getFileSizeBytes(String serverIp, int port, String username, String password, String remoteFilePath) {
        try (SSHClient client = createClient()) {
            connectAndAuth(client, serverIp, port, username, password);
            try (SFTPClient sftpClient = client.newSFTPClient()) {
                // stat()은 원격 파일의 메타데이터(크기, 권한, 수정시각 등)를 반환한다.
                return sftpClient.stat(remoteFilePath).getSize();
            }
        } catch (IOException e) {
            throw new CollectException("원격 파일 크기 조회 실패: " + serverIp + ":" + port + " " + remoteFilePath, e);
        }
    }

    /**
     * 원격 파일을 startByteOffset 위치부터 끝까지 읽어 lineConsumer로 전달한다.
     *
     * 처리 방식:
     *   SFTP READ 프로토콜은 원래 (handle, offset, length) 방식으로 임의 위치를 바로 읽을 수 있다.
     *   remoteFile.new RemoteFileInputStream(startByteOffset)로 시작 오프셋을 지정해서 열면
     *   그 위치부터 바로 READ 요청이 나가므로, 이전에 이미 읽은 앞부분은 네트워크로 전송되지 않는다.
     *   (과거 버전은 항상 offset 0부터 읽고 클라이언트에서 앞부분을 버리는 방식이라 매 주기마다
     *    파일 앞부분을 반복 전송받는 낭비가 있었다 — 이 낭비를 없애는 것이 이 메서드의 목적이다)
     *
     * @param serverIp       원격 서버 IP 주소
     * @param port           SSH 포트
     * @param username       SFTP 계정
     * @param password       SFTP 비밀번호
     * @param remoteFilePath 원격 파일의 절대 경로
     * @param startLineNumber startByteOffset 위치가 몇 번째 라인인지 (1부터 시작)
     * @param startByteOffset 읽기 시작 바이트 위치 (0부터 시작)
     * @param charset        파일 인코딩 (null이면 UTF-8로 대체)
     * @param lineConsumer   (라인번호, 누적바이트오프셋, 라인내용)을 처리하는 콜백
     * @throws CollectException SFTP 접속 또는 파일 읽기 실패 시
     */
    @Override
    public void readLines(
            String serverIp,
            int port,
            String username,
            String password,
            String remoteFilePath,
            long startLineNumber,
            long startByteOffset,
            Charset charset,
            LineConsumer lineConsumer
    ) {
        Objects.requireNonNull(lineConsumer, "lineConsumer must not be null");

        // charset이 null이면 UTF-8로 안전하게 대체한다.
        Charset effectiveCharset = charset != null ? charset : StandardCharsets.UTF_8;

        if (startLineNumber < 1) {
            throw new CollectException("startLineNumber는 1 이상이어야 한다: " + startLineNumber);
        }
        if (startByteOffset < 0) {
            throw new CollectException("startByteOffset은 0 이상이어야 한다: " + startByteOffset);
        }

        try (SSHClient client = createClient()) {
            connectAndAuth(client, serverIp, port, username, password);

            // SFTPClient, RemoteFile, RemoteFileInputStream을 모두 try-with-resources로 관리하여
            // 예외 발생 시에도 원격 파일 스트림과 SSH 연결이 반드시 닫히도록 보장한다.
            try (SFTPClient sftpClient = client.newSFTPClient();
                 RemoteFile remoteFile = sftpClient.open(remoteFilePath);
                 RemoteFile.RemoteFileInputStream in = remoteFile.new RemoteFileInputStream(startByteOffset)) {

                FileReadService.readLinesFromStream(in, startByteOffset, startLineNumber, effectiveCharset, lineConsumer);
            }
        } catch (IOException e) {
            throw new CollectException("원격 파일 라인 읽기 실패: " + serverIp + ":" + port + " " + remoteFilePath, e);
        }
    }

    /**
     * PromiscuousVerifier가 설정된 SSHClient를 생성한다.
     *
     * PromiscuousVerifier는 서버의 호스트 키를 무조건 신뢰하므로 MITM 공격에 취약하다.
     * 내부망 전용 환경에서만 사용해야 한다.
     */
    private SSHClient createClient() {
        SSHClient client = new SSHClient();
        client.addHostKeyVerifier(new PromiscuousVerifier());
        return client;
    }

    /**
     * SSH 서버에 접속하고 비밀번호 인증을 수행한다.
     *
     * 인증 실패 시 연결을 안전하게 끊고 예외를 다시 던진다.
     * 연결은 성공하지만 인증이 실패하는 경우 열린 소켓을 명시적으로 닫아 자원 누수를 방지한다.
     */
    private void connectAndAuth(SSHClient client, String serverIp, int port, String username, String password)
            throws IOException {
        client.connect(serverIp, port);
        try {
            client.authPassword(username, password);
        } catch (IOException e) {
            // 인증 실패 시 이미 열린 연결을 닫고 예외를 상위로 전달한다.
            try {
                client.disconnect();
            } catch (IOException disconnectException) {
                log.debug("SSH disconnect 실패", disconnectException);
            }
            throw e;
        }
    }
}
