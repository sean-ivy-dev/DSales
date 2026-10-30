package com.daesung.sales.document.repository;

import com.daesung.sales.document.entity.PartnerDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 특약점 배포용 PDF 보관함. */
public interface PartnerDocumentRepository extends JpaRepository<PartnerDocument, Long> {

    /** 교체 판정 키 — 같은 거래처·종류·기준월이면 덮어쓴다(명세 §7 '중복'). */
    Optional<PartnerDocument> findByDocTypeAndPartnerCodeAndBaseMonth(
            String docType, String partnerCode, String baseMonth);

    /**
     * 목록. 조건은 전부 선택이고, <b>내용(LONGBLOB)은 빼고</b> 읽는다 —
     * 목록 한 번에 PDF 수백 개를 메모리로 끌어오면 그것만으로 앱이 넘어간다.
     *
     * <p>반환 Object[]: [id, docType, partnerId, partnerCode, partnerName,
     * baseMonth, periodFrom, periodTo, fileName, fileSize, createdAt].
     */
    @Query(value = """
            SELECT d.id, d.doc_type, d.partner_id, d.partner_code, d.partner_name,
                   d.base_month, d.period_from, d.period_to, d.file_name, d.file_size, d.created_at
            FROM partner_document d
            WHERE (:docType IS NULL OR d.doc_type = :docType)
              AND (CAST(:partnerId AS SIGNED) IS NULL OR d.partner_id = :partnerId)
              AND (:partnerCode IS NULL OR d.partner_code = :partnerCode)
              AND (:fromMonth IS NULL OR d.base_month >= :fromMonth)
              AND (:toMonth IS NULL OR d.base_month <= :toMonth)
            ORDER BY d.base_month DESC, d.partner_code, d.doc_type
            """, nativeQuery = true)
    List<Object[]> search(@Param("docType") String docType,
                          @Param("partnerId") Long partnerId,
                          @Param("partnerCode") String partnerCode,
                          @Param("fromMonth") String fromMonth,
                          @Param("toMonth") String toMonth);
}
