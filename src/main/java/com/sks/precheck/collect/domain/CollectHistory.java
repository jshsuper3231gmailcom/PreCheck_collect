package com.sks.precheck.collect.domain;

import java.time.LocalDateTime;

/**
 * TB_COLLECT_HISTORY 테이블과 1:1로 매핑되는 수집 이력 DTO.
 */
public class CollectHistory {

    private Long collectHistoryId;
    private String serverId;
    private String serverIp;
    private String sourceFilePath;
    private String scheduleType;
    private String collectStatus;
    private Long collectedCount;
    private Long lastLineNumber;
    private Long lastByteOffset;
    private Long fileSizeBytes;
    private Long retryCount;
    private String failReason;
    private LocalDateTime collectStartAt;
    private LocalDateTime collectEndAt;
    private String collectDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public CollectHistory() {
    }

    public Long getCollectHistoryId() {
        return collectHistoryId;
    }

    public void setCollectHistoryId(Long collectHistoryId) {
        this.collectHistoryId = collectHistoryId;
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

    public String getScheduleType() {
        return scheduleType;
    }

    public void setScheduleType(String scheduleType) {
        this.scheduleType = scheduleType;
    }

    public String getCollectStatus() {
        return collectStatus;
    }

    public void setCollectStatus(String collectStatus) {
        this.collectStatus = collectStatus;
    }

    public Long getCollectedCount() {
        return collectedCount;
    }

    public void setCollectedCount(Long collectedCount) {
        this.collectedCount = collectedCount;
    }

    public Long getLastLineNumber() {
        return lastLineNumber;
    }

    public void setLastLineNumber(Long lastLineNumber) {
        this.lastLineNumber = lastLineNumber;
    }

    public Long getLastByteOffset() {
        return lastByteOffset;
    }

    public void setLastByteOffset(Long lastByteOffset) {
        this.lastByteOffset = lastByteOffset;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(Long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public Long getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Long retryCount) {
        this.retryCount = retryCount;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public LocalDateTime getCollectStartAt() {
        return collectStartAt;
    }

    public void setCollectStartAt(LocalDateTime collectStartAt) {
        this.collectStartAt = collectStartAt;
    }

    public LocalDateTime getCollectEndAt() {
        return collectEndAt;
    }

    public void setCollectEndAt(LocalDateTime collectEndAt) {
        this.collectEndAt = collectEndAt;
    }

    public String getCollectDate() {
        return collectDate;
    }

    public void setCollectDate(String collectDate) {
        this.collectDate = collectDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
