package com.daesung.sales.common.sequence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SequenceRepository extends JpaRepository<SeqRegistry, String> {

    /** 순번 +1 (행잠금). 반환=영향 행수. */
    @Modifying
    @Query(value = "UPDATE seq_registry SET seq_val = seq_val + 1 WHERE seq_name = :name", nativeQuery = true)
    int bump(@Param("name") String name);

    /** 현재 순번. bump 직후 같은 트랜잭션에서 호출. */
    @Query(value = "SELECT seq_val FROM seq_registry WHERE seq_name = :name", nativeQuery = true)
    Long current(@Param("name") String name);

    /**
     * 순번 +1, 행이 없으면 1로 새로 만든다(10/27 피드백 §6-1 — 날짜별로 리셋되는 순번용).
     * {@link #bump}는 미리 심어둔 고정 시퀀스(seed)만 갱신할 수 있는데, 날짜별 순번은
     * 그날 처음 호출될 때 비로소 그 키의 행이 생겨야 하므로 UPSERT가 필요하다.
     */
    @Modifying
    @Query(value = "INSERT INTO seq_registry (seq_name, seq_val) VALUES (:name, 1) "
            + "ON DUPLICATE KEY UPDATE seq_val = seq_val + 1", nativeQuery = true)
    int upsertBump(@Param("name") String name);
}
