#!/bin/bash
# PreCheck_CollectServer_Auth.conf의 password 필드에 넣을 ENC(...) 문자열을 생성한다.
#
# 사용법:
#   ./encrypt_password.sh "평문비밀번호"     ->  ENC(...) 출력, conf 파일에 그대로 붙여넣기
#   ./encrypt_password.sh --gen-key          ->  신규 마스터키 생성 (최초 1회만, 결과값을
#                                                COLLECT_AUTH_SECRET_KEY 환경변수로 등록)
#
# 주의:
#   - collect 앱이 실제로 구동되는 서버(COLLECT_AUTH_SECRET_KEY가 설정된 서버)에서 실행해야 한다.
#     다른 키로 암호화하면 collect 앱이 복호화하지 못한다.
#   - 신규 서버 추가 시: 마스터키는 그대로 두고, 이 스크립트로 새 비밀번호만 암호화해서
#     conf 파일에 새 줄로 추가하면 된다.

set -euo pipefail

# COLLECT_AUTH_SECRET_KEY가 현재 쉘에 없으면, collect 앱 기동 스크립트가 참조하는
# secret 파일에서 자동으로 읽어온다(수동 export 없이도 동작하도록). 이미 쉘에 값이
# 있으면 그 값을 그대로 쓴다(우선순위: 현재 쉘 env > secret 파일).
DEFAULT_SECRET_FILE="/home/precheck/cfg/collect_secret.env"
if [ -z "${COLLECT_AUTH_SECRET_KEY:-}" ] && [ -f "$DEFAULT_SECRET_FILE" ]; then
    # shellcheck disable=SC1090
    source "$DEFAULT_SECRET_FILE"
    # secret 파일이 'export' 없이 KEY=VALUE 형태(systemd EnvironmentFile 포맷과 겸용)일 수도
    # 있으므로, source만으로는 이 뒤의 java 자식 프로세스에 전달되지 않는다. 명시적으로 export.
    export COLLECT_AUTH_SECRET_KEY
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LIBS_DIR="$SCRIPT_DIR/../build/libs"

# Spring Boot 리패키징 jar(BOOT-INF 구조)는 -cp로 직접 클래스 로딩이 안 되므로
# 반드시 순수 클래스 jar(-plain.jar)를 사용해야 한다.
JAR_PATH="$(ls "$LIBS_DIR"/collect-*-plain.jar 2>/dev/null | head -n 1)"

if [ -z "$JAR_PATH" ]; then
    echo "collect-*-plain.jar를 찾을 수 없다. 먼저 gradlew.bat build로 빌드해야 한다." >&2
    exit 1
fi

if [ $# -ne 1 ]; then
    echo "사용법: $0 <평문비밀번호> | --gen-key" >&2
    exit 1
fi

if [ "$1" != "--gen-key" ] && [ -z "${COLLECT_AUTH_SECRET_KEY:-}" ]; then
    echo "COLLECT_AUTH_SECRET_KEY 환경변수가 설정되어 있지 않다." >&2
    exit 1
fi

java -cp "$JAR_PATH" com.sks.precheck.collect.tool.CollectAuthEncryptTool "$1"
