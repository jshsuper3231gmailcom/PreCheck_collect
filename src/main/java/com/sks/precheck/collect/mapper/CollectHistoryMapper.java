package com.sks.precheck.collect.mapper;

import com.sks.precheck.collect.domain.CollectHistory;
import com.sks.precheck.collect.domain.CollectResumePoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * TB_COLLECT_HISTORY 테이블 접근 Mapper.
 */
@Mapper
public interface CollectHistoryMapper {

    /**
     * 수집 이력 1건을 INSERT 한다.
     *
     * SEQUENCE nextval은 서비스에서 미리 조회하여 파라미터로 전달한다.
     *
     * @param collectHistory 수집 이력 DTO
     * @return 처리 건수
     */
    int insert(CollectHistory collectHistory);

    /**
     * 수집 상태를 UPDATE 한다.
     *
     * 수집 성공/실패/제외 확정 시점에 상태, 라인번호, 재시도 횟수, 실패 사유 등을 갱신한다.
     *
     * @param collectHistory 수집 이력 DTO
     * @return 처리 건수
     */
    int updateCollectStatus(CollectHistory collectHistory);

    /**
     * 주기 수집에서 마지막 성공 수집의 증분 시작점(라인번호 + 바이트 오프셋)을 조회한다.
     *
     * 라인번호와 바이트 오프셋을 한 쿼리로 함께 조회한다 — 같은 조건으로 두 번 나눠 조회하면
     * 그 사이 다른 수집이 이력을 갱신했을 때 서로 다른 행을 가리킬 위험이 있기 때문이다.
     *
     * collectDate가 전달되면, 마지막 성공 수집의 COLLECT_DATE가 그 값과 다르면 결과를 반환하지 않는다
     * (날짜가 바뀌면 파일 처음부터 다시 읽음).
     * collectDate가 null이면(파일 경로에 '+' 접미사가 붙어 날짜 리셋이 비활성화된 경우)
     * 날짜와 무관하게 마지막 성공 이력을 그대로 반환한다.
     * 결과가 없으면 null 이며, 서비스에서 라인 1번/오프셋 0으로 처리한다(파일 처음부터 읽기).
     *
     * @param serverId 서버 구분
     * @param sourceFilePath 대상 파일 경로
     * @param collectDate 오늘 수집 날짜 (yyyyMMdd), 날짜 리셋 비활성화 시 null
     * @return 마지막 증분 시작점 (없으면 null)
     */
    CollectResumePoint findResumePoint(
            @Param("serverId") String serverId,
            @Param("sourceFilePath") String sourceFilePath,
            @Param("collectDate") String collectDate
    );
}

