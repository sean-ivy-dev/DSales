package com.daesung.sales.document.entity;

import com.daesung.sales.common.entity.BaseEntity;
import com.daesung.sales.partner.entity.Partner;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 특약점 배포용 PDF(외상매출장). 근거: 10/26 특약점 명세 §7 + 10/27 피드백 §7-8.
 *
 * <p><b>파일명이 곧 메타데이터다</b> —
 * {@code SALES_10003_강남 하람도서_20260101-20260831.pdf} 에서 종류·거래처코드·기간을 읽는다.
 * 레거시(DSRE {@code FM_BASE_CustMng.cs})가 쓰던 규칙 그대로라, 기존에 올려 둔 파일과
 * 같은 이름 체계를 유지한다.
 *
 * <p>★<b>기준월 = 기간 종료일의 월</b>(명세 §7). 같은 거래처·종류·기준월을 다시 올리면
 * 새 행을 만들지 않고 <b>교체</b>한다 — 수정본을 올리는 것이 정상 동작이기 때문이다.
 */
@Entity
@Table(name = "partner_document")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartnerDocument extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_type", nullable = false, length = 10)
    private String docType;

    /**
     * 우리 거래처. ‼️<b>null 일 수 있다</b> — 파일명의 DSRE 코드로 거래처를 못 찾은 경우다.
     * 그래도 파일은 받는다(코드는 남는다). 업로드를 막으면 담당자가 그 달 전체를 못 올린다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_id")
    private Partner partner;

    /** 파일명에서 읽은 <b>DSRE 거래처코드</b>(10003 꼴). 우리 매출코드(A10A195)가 아니다. */
    @Column(name = "partner_code", nullable = false, length = 20)
    private String partnerCode;

    /** 파일명에서 읽은 거래처명 — 참고용이다. 식별은 코드로 한다. */
    @Column(name = "partner_name", length = 100)
    private String partnerName;

    /** 기준월 {@code yyyy-MM} = 기간 종료일의 월. */
    @Column(name = "base_month", nullable = false, length = 7)
    private String baseMonth;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private int fileSize;

    /**
     * PDF 원본. 스토리지(S3 등)가 생기면 <b>이 칸만 경로로 바꾸면 된다</b> —
     * 바깥에서는 서비스의 조회·다운로드 메서드만 쓴다.
     */
    // ‼️`@Lob` 만 붙이면 Hibernate 6 가 MySQL 에서 **tinyblob(64KB)** 으로 매핑한다 —
    //   스키마 검증이 LONGBLOB 과 안 맞다고 기동을 막고, 설령 통과해도 64KB 넘는 PDF 가
    //   잘린다(외상매출장은 보통 150~400KB다). 타입을 명시해 LONGBLOB 에 맞춘다.
    @Lob
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARBINARY)
    @Column(name = "content", nullable = false)
    private byte[] content;

    public static PartnerDocument of(String docType, Partner partner, String partnerCode,
                                     String partnerName, String baseMonth,
                                     LocalDate periodFrom, LocalDate periodTo,
                                     String fileName, byte[] content) {
        PartnerDocument d = new PartnerDocument();
        d.docType = docType;
        d.partner = partner;
        d.partnerCode = partnerCode;
        d.partnerName = partnerName;
        d.baseMonth = baseMonth;
        d.periodFrom = periodFrom;
        d.periodTo = periodTo;
        d.fileName = fileName;
        d.content = content;
        d.fileSize = (content == null) ? 0 : content.length;
        return d;
    }

    /** 같은 거래처·종류·기준월을 다시 올렸다 — 내용을 갈아 끼운다(명세 §7 '중복: 교체'). */
    public void replaceWith(Partner partner, String partnerName,
                            LocalDate periodFrom, LocalDate periodTo,
                            String fileName, byte[] content) {
        if (partner != null) {
            this.partner = partner;   // 전엔 못 찾았다가 매핑이 채워졌을 수 있다
        }
        this.partnerName = partnerName;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.fileName = fileName;
        this.content = content;
        this.fileSize = (content == null) ? 0 : content.length;
    }
}
