package com.daesung.sales.product.entity;

import com.daesung.sales.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 상품. 근거: bookData + 🆕 content_type/is_set. */
@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseEntity {

    /**
     * 낙관적 잠금 버전. 수정될 때마다 JPA 가 올린다.
     * ‼️이 칸만으로는 못 막는다 — 요청마다 새로 읽으면 버전이 늘 일치한다.
     * **클라이언트가 읽은 버전을 되돌려 보내야** 충돌이 드러난다(서비스에서 비교).
     */
    @jakarta.persistence.Version
    @jakarta.persistence.Column(nullable = false)
    private Long version;


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 10)
    private ContentType contentType;

    @Column(name = "is_set", nullable = false)
    private boolean set;

    private Integer price;

    @Column(name = "tax_free", nullable = false)
    private boolean taxFree;

    @Column(length = 5)
    private String grade;

    /** 분류코드(계층, 첫 글자=대분류). 매출액명세서 rollup 축. 근거: 레거시 catCode. */
    @Column(name = "cat_code", length = 20)
    private String catCode;

    /** 분류명. 근거: 레거시 catName. */
    @Column(name = "cat_name", length = 100)
    private String catName;

    @Column(name = "use_yn", nullable = false)
    private boolean useYn = true;

    /** 세부구분(구 '매출구분' — 발주처 2026-08-31 명칭 통일). 매출액정리·순매출조회 집계기준. 근거: 32p. */
    @Column(name = "sales_division", length = 30)
    private String salesDivision;

    /** 상품년도(예: 2026). 근거: 32p 도서관리(레거시 bookData 부재→신규). */
    @Column(name = "product_year")
    private Integer productYear;

    /** 상품구분(레거시 bookData.type 원시값). contentType(콘텐츠구분 SELF/EXTERNAL)과 별개 분류. 근거: 32p 도서관리. */
    @Column(name = "product_type", length = 30)
    private String productType;

    /**
     * 기본 공급률(%). 근거: 정본 구분값정리 [6.도서관리-1.기본정보] "정가 / 공급률 = 판매가 계산 기준".
     *
     * <p>거래처별 매핑({@code partner_supply_rate} — 거래처×대분류)이 없을 때 쓰는 바탕값이다.
     * 매출등록 자동적용 우선순위는 <b>입력값 &gt; 거래처별 매핑 &gt; 이 값</b>.
     * (옛 도서×거래처 표 {@code product_partner_price} 는 V42 가 지웠다 — 축이 둘이면
     * "무엇을 먼저 보나"라는 규칙이 하나 더 생긴다.)
     */
    @Column(name = "supply_rate")
    private Integer supplyRate;

    /** 수불부노출 여부(제품수불부 집계 포함). 기본 true. */
    /**
     * 단가노출 — 거래처별 단가(34p) 화면에 이 도서를 띄울지.
     * ★수불부노출과 <b>다른 축</b>이다(발주처 2026-08-21 E-7 지적으로 분리).
     * "수불부엔 안 나오지만 단가는 매긴다"가 가능하다.
     */
    @Column(name = "price_visible", nullable = false)
    private boolean priceVisible = true;

    /** 수불부노출 — 제품수불부(11p) 집계에 포함할지. DSRE가 수불을 관리하는 상품은 빼야 한다. */
    @Column(name = "ledger_visible", nullable = false)
    private boolean ledgerVisible = true;

    /** Web/신청사이트 게시 여부. 기본 false. */
    @Column(name = "web_visible", nullable = false)
    private boolean webVisible = false;

    /**
     * <b>지정 거래처 전용</b>(화이트리스트 모드). 근거: 11/04 클라이언트 회신 §1(10/31 §1-1 대체).
     *
     * <p>true면 <b>열기 행이 있는 거래처에만</b> 노출하고 거래처×대분류 매핑은 보지 않는다.
     * 10/31 판은 이 자리를 '닫기' 예외로 대신했는데, 그러면 나머지 거래처마다 닫기 행을
     * 깔아야 하고 신규 거래처는 기본 노출이라 의도와 반대로 열렸다.
     *
     * <p>판정은 {@link com.daesung.sales.product.repository.ProductRepository#findDealerVisible}
     * <b>한 곳</b>에만 있다.
     */
    @Column(name = "restricted_visible", nullable = false)
    private boolean restrictedVisible = false;

    /**
     * 재고관리 여부. 기본 true. false면 매출 시 재고 차감·재고이벤트 없음(수불부·재고 대상 아님).
     * 근거: 레거시 모의고사는 invenData(재고장부) 미기록·제품수불부 없음 = 인원 기반 종량제 매출.
     */
    @Column(name = "stock_managed", nullable = false)
    private boolean stockManaged = true;

    public static Product create(String code, String name, ContentType contentType, boolean set,
                                 Integer price, boolean taxFree, String grade,
                                 String catCode, String catName, boolean useYn,
                                 String salesDivision, boolean ledgerVisible, boolean webVisible,
                                 boolean stockManaged) {
        Product p = new Product();
        p.code = code;
        p.name = name;
        p.contentType = contentType;
        p.set = set;
        p.price = price;
        p.taxFree = taxFree;
        p.grade = grade;
        p.catCode = catCode;
        p.catName = catName;
        p.useYn = useYn;
        p.salesDivision = salesDivision;
        p.ledgerVisible = ledgerVisible;
        p.webVisible = webVisible;
        p.stockManaged = stockManaged;
        return p;
    }

    /** 32p 확장 필드(상품년도·상품구분·기본공급률) 설정 — 등록·수정 시 사용. */
    public void applyExtra(Integer productYear, String productType, Integer supplyRate) {
        // update()와 같은 규칙 — null은 "안 보냈다"이지 "지워라"가 아니다.
        this.productYear = keep(productYear, this.productYear);
        this.productType = keep(productType, this.productType);
        this.supplyRate = keep(supplyRate, this.supplyRate);
    }

    /** 수정(코드는 불변). */
    /**
     * 수정(코드는 불변).
     *
     * <p>★Y/N 플래그는 <b>null이면 기존 값을 유지</b>한다. 예전엔 primitive라 요청에서 빠지면
     * false가 되어, 일부 필드만 보낸 PUT이 사용여부·수불부노출·Web게시·재고관리를 조용히 껐다.
     * 그중 재고관리가 꺼지면 매출을 넣어도 재고가 차감되지 않는데 오류조차 나지 않는다.
     */
    public void update(String name, ContentType contentType, Boolean set, Integer price,
                       Boolean taxFree, String grade, String catCode, String catName, Boolean useYn,
                       String salesDivision, Boolean ledgerVisible, Boolean webVisible,
                       Boolean stockManaged) {
        // ★null = "안 보냈다" = 기존 값 유지. 예전에는 Boolean만 그랬고 나머지는 그대로 대입해,
        //   화면이 정가만 고치려고 일부 필드만 보내면 분류코드·학년이 조용히 지워졌다.
        //   29개 필드를 하나도 빠뜨리지 않고 되돌려 보내야만 안전한 API였다.
        //   지우고 싶으면 빈 문자열("")을 보낸다 — 그건 "비운다"는 의사표시라 그대로 저장된다.
        this.name = keep(name, this.name);
        this.contentType = keep(contentType, this.contentType);
        this.set = keep(set, this.set);
        this.price = keep(price, this.price);
        this.taxFree = keep(taxFree, this.taxFree);
        this.grade = keep(grade, this.grade);
        this.catCode = keep(catCode, this.catCode);
        this.catName = keep(catName, this.catName);
        this.useYn = keep(useYn, this.useYn);
        this.salesDivision = keep(salesDivision, this.salesDivision);
        this.ledgerVisible = keep(ledgerVisible, this.ledgerVisible);
        this.webVisible = keep(webVisible, this.webVisible);
        this.stockManaged = keep(stockManaged, this.stockManaged);
    }

    /** 논리삭제(비활성화). */
    public void deactivate() {
        this.useYn = false;
    }

    /** BOM 완제품(세트)으로 표시. */
    public void markAsSet() {
        this.set = true;
    }

    /** 변경이력용 스냅샷(필드명|한글라벨 → 값). 근거: 발주처 확정 3-1(라). */
    public java.util.Map<String, String> auditSnapshot() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("name|도서명", name);
        m.put("contentType|콘텐츠구분", (contentType == null) ? null : contentType.name());
        m.put("set|세트여부", String.valueOf(set));
        m.put("price|정가", str(price));
        m.put("supplyRate|기본공급률", str(supplyRate));
        m.put("taxFree|면세여부", String.valueOf(taxFree));
        m.put("grade|학년", grade);
        m.put("catCode|분류코드", catCode);
        m.put("catName|분류명", catName);
        m.put("useYn|사용여부", String.valueOf(useYn));
        m.put("salesDivision|세부구분", salesDivision);   // 구 '매출구분' — 발주처 2026-08-31 명칭 통일
        m.put("productYear|상품년도", str(productYear));
        m.put("productType|상품구분", productType);
        m.put("ledgerVisible|수불부노출", String.valueOf(ledgerVisible));
        m.put("priceVisible|단가노출", String.valueOf(priceVisible));
        m.put("webVisible|Web게시", String.valueOf(webVisible));
        m.put("restrictedVisible|지정거래처전용", String.valueOf(restrictedVisible));
        m.put("stockManaged|재고관리", String.valueOf(stockManaged));
        return m;
    }

    /**
     * Y/N 항목만 골라서 설정(일괄 변경용). null인 항목은 건드리지 않는다.
     * 근거: 발주처 요청(1-3) "각 열을 일괄로 처리(전체선택 등)".
     */
    public void updateFlags(Boolean webVisible, Boolean taxFree, Boolean ledgerVisible,
                            Boolean useYn, Boolean stockManaged, Boolean priceVisible,
                            Boolean restrictedVisible) {
        this.webVisible = keep(webVisible, this.webVisible);
        this.taxFree = keep(taxFree, this.taxFree);
        this.ledgerVisible = keep(ledgerVisible, this.ledgerVisible);
        this.useYn = keep(useYn, this.useYn);
        this.stockManaged = keep(stockManaged, this.stockManaged);
        this.priceVisible = keep(priceVisible, this.priceVisible);
        this.restrictedVisible = keep(restrictedVisible, this.restrictedVisible);
    }

    /** 단가노출 설정(등록·수정 경로 공용). */
    public void applyPriceVisible(Boolean priceVisible) {
        this.priceVisible = keep(priceVisible, this.priceVisible);
    }

    /** 지정 거래처 전용 설정(등록·수정 경로 공용). */
    public void applyRestrictedVisible(Boolean restrictedVisible) {
        this.restrictedVisible = keep(restrictedVisible, this.restrictedVisible);
    }

    /** 요청에 값이 없으면(null) 기존 값을 그대로 둔다. */
    private static boolean keep(Boolean incoming, boolean current) {
        return (incoming == null) ? current : incoming;
    }

    /**
     * 위와 같은 규칙의 일반형. <b>null은 "안 보냈다"</b>이지 "지워라"가 아니다.
     *
     * <p>지우려는 의사는 빈 문자열로 표현한다 — 그건 값이 실려 온 것이라 그대로 저장된다.
     * 이 구분이 없으면 화면이 필드 하나만 빠뜨려도 조용히 데이터가 사라진다.
     */
    private static <T> T keep(T incoming, T current) {
        return (incoming == null) ? current : incoming;
    }

    private static String str(Object v) {
        return (v == null) ? null : String.valueOf(v);
    }

    /**
     * <b>상품 연도 — 분류코드에서 뽑는다.</b> 트리·필터의 연도 축이 이것이다.
     *
     * <p>★근거는 레거시다. {@code 조회.vb:367}·{@code UC_TabPages_IC.vb:2701} 등 여러 화면이
     * {@code order by substring(catCode,2,4) desc} 로 <b>분류코드 2번째부터 4자리</b>를
     * 연도로 쓴다. 분류코드 형식({@link com.daesung.sales.product.dto.CatCode})도
     * "2~5번째 = 연도"로 못 박혀 있다.
     *
     * <p>‼️{@link #productYear} 필드를 쓰지 않는 이유: 운영 14,811개 중 <b>3개만 채워져 있다</b>
     * (2026-11-09 실측). 그 필드로 트리를 만들면 전 상품이 '미지정' 한 묶음이 된다.
     * 반면 분류코드에서는 <b>100% 뽑힌다</b>(2007~2027, 21개 연도).
     * 담당자가 {@code productYear} 를 채우기 시작하면 그 값을 우선하도록 바꿀 수 있다.
     *
     * @return 연도(예: 2026). 분류코드가 형식에 안 맞으면 {@code null}
     */
    public Integer catCodeYear() {
        return yearOfCatCode(catCode);
    }

    /** {@link #catCodeYear()}의 문자열 버전 — 상품 객체 없이 분류코드만 있을 때. */
    public static Integer yearOfCatCode(String catCode) {
        if (catCode == null || catCode.length() < 5) {
            return null;
        }
        String y = catCode.substring(1, 5);
        for (int i = 0; i < 4; i++) {
            if (!Character.isDigit(y.charAt(i))) {
                return null;
            }
        }
        return Integer.valueOf(y);
    }
    /**
     * 법인 축(V125). 신규 등록 상품은 **연구소**다 — 발주처 11/09 1부 1 "신규 등록은 연구소로 저장".
     *
     * <p>★조회 조건에는 아직 쓰이지 않는다 — 다음 단계(법인별 조회)에서 붙는다.
     *   값만 등록 시점에 채워 둔다(안 채우면 다음 단계에서 또 백필해야 한다).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "corp", length = 10)
    private com.daesung.sales.common.corp.Corp corp;

    /** 법인 축 자동 채움. 이관분은 V126 이 코드 접두어로 채웠고, 신규 등록은 연구소로 고정한다. */
    @PrePersist
    void fillCorp() {
        if (corp == null) {
            corp = com.daesung.sales.common.corp.Corp.DSLAB;
        }
        fillProductYear();
    }

    /**
     * 상품년도 자동 채움 — <b>분류코드의 연도</b>({@code substring(cat_code,2,4)}).
     *
     * <p>근거: 레거시 상품년도가 그것이다({@code CommonDB.GetCatYearList}).
     * 분류코드 형식이 {@code [영문1][연도4][영문·숫자1~3]} 이고 실측 14,808권 전부 그 형식이다.
     *
     * <p>‼️<b>V127 백필만으로는 부족하다.</b> 그 뒤 등록되는 상품은 또 비고, 응시현황
     * 상품년도 조건이 그 상품을 못 찾는다(프론트 실측 2026-11-10 §4 — 값이 3권만 있어
     * 어느 연도로 걸어도 0건이었다). 그래서 등록 시점에도 채운다.
     *
     * <p>‼️담당자가 넣은 값은 덮지 않고, 형식에 맞지 않는 분류코드는 비워 둔다 —
     * 근거 없이 연도를 만들면 그 상품이 엉뚱한 해로 묶인다.
     */
    private void fillProductYear() {
        if (productYear != null || catCode == null || catCode.length() < 5) {
            return;
        }
        String y = catCode.substring(1, 5);
        if (!Character.isLetter(catCode.charAt(0)) || !y.chars().allMatch(Character::isDigit)) {
            return;
        }
        int v = Integer.parseInt(y);
        if (v >= 1990 && v <= 2100) {
            productYear = v;
        }
    }

}
