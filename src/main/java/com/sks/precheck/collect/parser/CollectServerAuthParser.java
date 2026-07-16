package com.sks.precheck.collect.parser;

import com.sks.precheck.collect.common.exception.CollectException;
import com.sks.precheck.collect.common.util.PasswordCryptoUtil;
import com.sks.precheck.collect.vo.CollectServerAuthVo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 서버별 SFTP 접속정보(포트/계정) override 설정 파일 파서.
 *
 * ~/cfg/PreCheck_CollectServer_Auth.conf 파일을 읽어 [serverId][port][username][password] 포맷을
 * serverId를 키로 하는 Map으로 변환한다. override가 필요한 서버만 등록하면 되며,
 * 값이 비어있는 필드(port/username/password)는 전역 기본값으로 대체하도록 null을 남겨둔다.
 *
 * password 필드는 ENC(...)로 감싸진 경우 PasswordCryptoUtil로 복호화하여 사용한다
 * (COLLECT_AUTH_SECRET_KEY 환경변수 필요). ENC(...)가 아니면 평문으로 간주하되 WARN 로그를 남긴다.
 *
 * '#'으로 시작하는 라인과 포맷이 맞지 않는 라인은 무시하고 WARN 로그를 남긴다.
 */
public class CollectServerAuthParser {

    private static final Logger log = LogManager.getLogger(CollectServerAuthParser.class);

    /**
     * 서버별 접속정보 override 파일을 파싱하여 serverId -> CollectServerAuthVo 맵을 반환한다.
     *
     * 파일이 존재하지 않으면(운영 시 override가 하나도 없는 경우) 빈 맵을 반환한다.
     *
     * @param filePath override 설정 파일 경로
     * @return serverId를 키로 하는 override 맵 (없으면 빈 맵)
     */
    public Map<String, CollectServerAuthVo> parseAuthFile(String filePath) {
        Path path = Path.of(filePath);
        Map<String, CollectServerAuthVo> result = new HashMap<>();

        if (!Files.exists(path)) {
            log.debug("서버별 접속정보 override 파일 없음(전역 기본값만 사용) - filePath: {}", filePath);
            return result;
        }

        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                CollectServerAuthVo vo = parseLine(lines.get(i), i + 1);
                if (vo != null) {
                    result.put(vo.getServerId(), vo);
                }
            }
            log.info("서버별 접속정보 override 파일 파싱 완료 - filePath: {}, 등록 건수: {}", filePath, result.size());
        } catch (IOException e) {
            log.error("서버별 접속정보 override 파일 읽기 실패 - filePath: {}, error: {}", filePath, e.getMessage());
            throw new CollectException("서버별 접속정보 override 파일 읽기 실패: " + filePath, e);
        }

        return result;
    }

    private CollectServerAuthVo parseLine(String line, int lineNumber) {
        if (line == null) {
            return null;
        }

        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }

        List<String> tokens = extractBracketTokens(trimmed);
        if (tokens.size() != 4) {
            log.warn("서버 접속정보 라인 포맷 오류로 무시 - lineNumber: {}, line: {}", lineNumber, trimmed);
            return null;
        }

        String serverId = tokens.get(0).trim();
        String portText = tokens.get(1).trim();
        String username = tokens.get(2).trim();
        String password = tokens.get(3).trim();

        if (serverId.isEmpty()) {
            log.warn("서버 접속정보 라인 serverId 누락으로 무시 - lineNumber: {}, line: {}", lineNumber, trimmed);
            return null;
        }

        Integer port = null;
        if (!portText.isEmpty()) {
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                log.warn("서버 접속정보 포트 파싱 실패, 전역 기본값으로 대체 - lineNumber: {}, line: {}", lineNumber, trimmed);
            }
        }

        String resolvedPassword = null;
        if (!password.isEmpty()) {
            if (PasswordCryptoUtil.isEncrypted(password)) {
                try {
                    resolvedPassword = PasswordCryptoUtil.decrypt(PasswordCryptoUtil.unwrapEnc(password));
                } catch (Exception e) {
                    log.error("비밀번호 복호화 실패로 라인 무시 - lineNumber: {}, serverId: {}, 사유: {}", lineNumber, serverId, e.getMessage());
                    return null;
                }
            } else {
                log.warn("평문 비밀번호 사용 중(운영 환경에서는 ENC(...) 암호화 권장) - lineNumber: {}, serverId: {}", lineNumber, serverId);
                resolvedPassword = password;
            }
        }

        CollectServerAuthVo vo = new CollectServerAuthVo();
        vo.setServerId(serverId);
        vo.setPort(port);
        vo.setUsername(username.isEmpty() ? null : username);
        vo.setPassword(resolvedPassword);
        return vo;
    }

    private List<String> extractBracketTokens(String text) {
        List<String> tokens = new ArrayList<>(4);
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf('[', i);
            if (start < 0) {
                break;
            }
            int end = text.indexOf(']', start + 1);
            if (end < 0) {
                break;
            }
            tokens.add(text.substring(start + 1, end));
            i = end + 1;
        }
        return tokens;
    }
}
