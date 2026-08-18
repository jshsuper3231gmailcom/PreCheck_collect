package com.sks.precheck.collect.vo;

/**
 * 수집 스케줄 conf 파일의 한 줄을 파싱한 결과 DTO.
 */
public class CollectScheduleVo {

    private String serverId;
    private String serverIp;
    private String sourceFilePath;
    private String scheduleExpression;
    private boolean holidaySkip;

    public CollectScheduleVo() {
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getServerIp() {
        return serverIp;
    }

    public void setServerIp(String serverIp) {
        this.serverIp = serverIp;
    }

    public String getSourceFilePath() {
        return sourceFilePath;
    }

    public void setSourceFilePath(String sourceFilePath) {
        this.sourceFilePath = sourceFilePath;
    }

    public String getScheduleExpression() {
        return scheduleExpression;
    }

    public void setScheduleExpression(String scheduleExpression) {
        this.scheduleExpression = scheduleExpression;
    }

    public boolean isHolidaySkip() {
        return holidaySkip;
    }

    public void setHolidaySkip(boolean holidaySkip) {
        this.holidaySkip = holidaySkip;
    }
}

