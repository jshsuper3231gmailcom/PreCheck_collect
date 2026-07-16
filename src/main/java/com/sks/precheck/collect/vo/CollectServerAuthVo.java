package com.sks.precheck.collect.vo;

/**
 * 서버별 SFTP 접속정보(포트/계정) override 설정.
 *
 * PreCheck_CollectServer_Auth.conf 파일의 한 줄을 파싱한 결과이며,
 * 값이 없는 필드는 전역 기본값(application.yml의 precheck.sftp.*)으로 대체된다.
 */
public class CollectServerAuthVo {

    private String serverId;
    private Integer port;
    private String username;
    private String password;

    public CollectServerAuthVo() {
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
