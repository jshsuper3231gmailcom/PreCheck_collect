package com.sks.precheck.collect.domain;

/**
 * 주기 수집의 증분 시작점(직전 성공 수집이 마지막으로 읽은 위치)을 담는 DTO.
 *
 * lastLineNumber와 lastByteOffset은 항상 TB_COLLECT_HISTORY의 같은 행에서 함께 조회되므로
 * (같은 WHERE 조건의 SELECT를 두 번 나눠 실행하면 결과가 서로 다른 시점의 행을 가리킬 위험이 있다)
 * 하나의 쿼리 결과로 묶어서 반환한다.
 */
public class CollectResumePoint {

    private final Long lastLineNumber;
    private final Long lastByteOffset;

    public CollectResumePoint(Long lastLineNumber, Long lastByteOffset) {
        this.lastLineNumber = lastLineNumber;
        this.lastByteOffset = lastByteOffset;
    }

    public Long getLastLineNumber() {
        return lastLineNumber;
    }

    public Long getLastByteOffset() {
        return lastByteOffset;
    }
}
