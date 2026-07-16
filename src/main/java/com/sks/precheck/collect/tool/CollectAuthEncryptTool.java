package com.sks.precheck.collect.tool;

import com.sks.precheck.collect.common.util.PasswordCryptoUtil;

/**
 * PreCheck_CollectServer_Auth.conf에 넣을 암호화된 비밀번호(ENC(...))를 생성하는 커맨드라인 유틸.
 * Spring 컨텍스트를 띄우지 않는 순수 main() 진입점이며, collect 애플리케이션과 별개로 실행한다.
 *
 * 사용법 (collect 서버에서, COLLECT_AUTH_SECRET_KEY 환경변수가 설정된 상태로):
 *   java -cp collect.jar com.sks.precheck.collect.tool.CollectAuthEncryptTool <평문비밀번호>
 *     -> ENC(...) 문자열 출력, 이 값을 conf의 password 필드에 그대로 붙여넣는다.
 *
 *   java -cp collect.jar com.sks.precheck.collect.tool.CollectAuthEncryptTool --gen-key
 *     -> 신규 마스터 키 생성(최초 설정 시 1회만 사용, 결과값을 COLLECT_AUTH_SECRET_KEY로 등록).
 */
public final class CollectAuthEncryptTool {

    private CollectAuthEncryptTool() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("사용법: CollectAuthEncryptTool <평문비밀번호> | --gen-key");
            System.exit(1);
            return;
        }

        try {
            if ("--gen-key".equals(args[0])) {
                System.out.println(PasswordCryptoUtil.generateKey());
                return;
            }

            String cipherText = PasswordCryptoUtil.encrypt(args[0]);
            System.out.println(PasswordCryptoUtil.wrapEnc(cipherText));
        } catch (Exception e) {
            System.err.println("암호화 실패: " + e.getMessage());
            System.exit(1);
        }
    }
}
