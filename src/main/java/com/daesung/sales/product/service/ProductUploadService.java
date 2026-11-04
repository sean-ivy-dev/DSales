package com.daesung.sales.product.service;

import com.daesung.sales.common.excel.ExcelSheetReader;
import com.daesung.sales.common.excel.ExcelSheetReader.Header;
import com.daesung.sales.common.excel.ExcelSheetReader.RowView;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.repository.PartnerRepository;
import com.daesung.sales.product.dto.BomRegisterRequest;
import com.daesung.sales.product.dto.MasterUploadResponse;
import com.daesung.sales.product.dto.PartnerSupplyRateRequest;
import com.daesung.sales.product.dto.ProductCreateRequest;
import com.daesung.sales.product.dto.ProductUpdateRequest;
import com.daesung.sales.product.entity.ContentType;
import com.daesung.sales.product.entity.MajorCategory;
import com.daesung.sales.product.entity.MaterialType;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.product.repository.ProductRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 도서관리 3개 탭 <b>엑셀 업로드</b>(33p).
 * 근거: 발주처 화면검토(2026-08-31) 화면33 — "도서관리 등록 탭(기본정보/세트구성/거래처별단가)
 * 전체에 엑셀 업로드 기능 신설 요청드렸던 부분".
 *
 * <p>★<b>양식은 우리 목록 다운로드 파일 그대로다.</b> 발주처가 정한 별도 양식이 없는 탭이라,
 * "받은 파일을 고쳐서 다시 올린다"가 가장 자연스러운 사용법이다. 그래서 헤더 이름으로 읽고
 * ({@link ExcelSheetReader}), 모르는 열은 무시한다 — 담당자가 메모 열을 붙여도 깨지지 않는다.
 *
 * <p>★<b>한 행이 틀려도 나머지는 들어간다.</b> 수백 줄짜리 마스터에서 한 줄 오타 때문에
 * 전부 되돌리면 담당자는 고칠 곳을 찾지 못한 채 처음부터 다시 해야 한다.
 * 행별로 결과와 사유를 돌려주고, 성공한 행은 남긴다.
 * (다만 <b>필수 컬럼이 없는 파일</b>은 행 단위가 아니라 파일 전체를 거부한다 — 양식이 틀린 것이다.)
 */
@Service
@RequiredArgsConstructor
public class ProductUploadService {

    private final ProductRepository productRepository;
    private final PartnerRepository partnerRepository;
    private final ProductService productService;
    private final PartnerSupplyRateService partnerSupplyRateService;

    // ── ① 기본정보 탭 ────────────────────────────────────────────────
    private static final Header H_CODE = Header.of("도서코드", "상품코드");
    private static final Header H_NAME = Header.of("도서명", "상품명");
    private static final Header H_CONTENT = Header.of("콘텐츠구분");
    private static final Header H_CAT_CODE = Header.of("분류코드");
    private static final Header H_CAT_NAME = Header.of("분류명");
    private static final Header H_PRICE = Header.of("정가");
    private static final Header H_TAXFREE = Header.of("면세", "면세여부");
    private static final Header H_DIVISION = Header.of("세부구분", "매출구분");
    private static final Header H_LEDGER = Header.of("수불부노출");
    private static final Header H_PRICE_VIS = Header.of("단가노출");
    private static final Header H_WEB = Header.of("web게시", "web게시여부");
    private static final Header H_SUPPLY = Header.of("공급률");
    private static final Header H_YEAR = Header.of("상품년도");
    private static final Header H_TYPE = Header.of("상품구분");
    private static final Header H_GRADE = Header.of("학년");
    private static final Header H_USE = Header.of("사용여부");
    private static final Header H_STOCK = Header.of("재고관리여부", "재고관리");

    /**
     * 기본정보 탭 업로드. <b>도서코드가 키</b>다 — 있으면 고치고 없으면 만든다.
     *
     * <p>★<b>빈 칸은 "지우기"가 아니라 "그대로"다.</b> 담당자는 고칠 열만 채워 올린다.
     * 빈 칸을 null로 덮으면 정가만 고치려던 사람이 분류·공급률을 통째로 날린다.
     */
    @Transactional
    public MasterUploadResponse uploadProducts(MultipartFile file) {
        // ★파일 단위 필수는 **키 열 하나**다. 도서명은 신규 등록에만 필요하므로 행 단위 오류로 다룬다 —
        //   "정가만 고치려고 도서코드·정가 두 열만 채워 올린다"가 이 기능의 주 사용법이다.
        ExcelSheetReader sheet = ExcelSheetReader.read(file, List.of(H_CODE));
        Result out = new Result();

        for (RowView r : sheet.rows()) {
            String code = null;
            try {
                code = r.required(H_CODE);
                Product existing = productRepository.findByCode(code).orElse(null);
                ContentType content = r.enumOrNull(H_CONTENT, ContentType.class, ContentType::label);

                if (existing == null) {
                    if (content == null) {
                        content = ContentType.SELF;   // 신규인데 미지정이면 자체교재(대다수)
                    }
                    productService.create(new ProductCreateRequest(
                            code, r.required(H_NAME), content, false,
                            r.intOrNull(H_PRICE), Boolean.TRUE.equals(r.boolOrNull(H_TAXFREE)),
                            r.str(H_GRADE), r.str(H_CAT_CODE), r.str(H_CAT_NAME),
                            r.boolOrNull(H_USE), r.str(H_DIVISION),
                            r.intOrNull(H_YEAR), r.str(H_TYPE), r.intOrNull(H_SUPPLY),
                            r.boolOrNull(H_LEDGER), r.boolOrNull(H_PRICE_VIS), r.boolOrNull(H_WEB),
                            // 지정 거래처 전용(11/04 §1)은 업로드 양식에 칸이 없다 — 기본(매핑대로)으로 둔다.
                            //   화이트리스트는 도서 한 권씩 따지는 설정이라 일괄 업로드로 켜지면 위험하다.
                            null,
                            r.boolOrNull(H_STOCK)));
                    out.created(r.rowNo(), code);
                } else {
                    // 빈 칸은 기존 값으로 채워 넘긴다.
                    // ※Product.update 가 이제 null을 "유지"로 다루므로(2026-09-10) 여기서 채우지 않아도
                    //   결과는 같다. 그래도 남겨 두는 이유는 **업로드가 무엇을 보내는지 이 자리에서 읽히게**
                    //   하기 위해서다 — 엔티티 규칙이 또 바뀌어도 업로드 동작은 흔들리지 않는다.
                    productService.update(existing.getId(), new ProductUpdateRequest(
                            or(r.str(H_NAME), existing.getName()),
                            (content != null) ? content : existing.getContentType(),
                            null,
                            or(r.intOrNull(H_PRICE), existing.getPrice()),
                            r.boolOrNull(H_TAXFREE),
                            or(r.str(H_GRADE), existing.getGrade()),
                            or(r.str(H_CAT_CODE), existing.getCatCode()),
                            or(r.str(H_CAT_NAME), existing.getCatName()),
                            r.boolOrNull(H_USE),
                            or(r.str(H_DIVISION), existing.getSalesDivision()),
                            or(r.intOrNull(H_YEAR), existing.getProductYear()),
                            or(r.str(H_TYPE), existing.getProductType()),
                            or(r.intOrNull(H_SUPPLY), existing.getSupplyRate()),
                            r.boolOrNull(H_LEDGER), r.boolOrNull(H_PRICE_VIS), r.boolOrNull(H_WEB),
                            null,   // 지정 거래처 전용 — 양식에 칸이 없다(null=유지)
                            r.boolOrNull(H_STOCK),
                            // ★업로드는 버전을 보내지 않는다 — 파일 한 장으로 수백 건을 고치는 경로라
                            //   한 건이 그 사이 바뀌었다고 전체를 멈추면 담당자가 손쓸 방법이 없다.
                            //   동시 수정 보호는 **화면 수정**에만 건다.
                            null));
                    out.updated(r.rowNo(), code);
                }
            } catch (BusinessException e) {
                out.error(r.rowNo(), code, e.getMessage());
            }
        }
        return out.build();
    }

    // ── ② 세트구성(BOM) 탭 ───────────────────────────────────────────
    private static final Header H_SET = Header.of("세트도서코드", "세트코드", "완제품코드");
    private static final Header H_CHILD = Header.of("구성도서코드", "구성품코드", "자재코드");
    private static final Header H_RATIO = Header.of("소요수량", "세트당소요수량", "비율");
    private static final Header H_ROUND = Header.of("구성회차", "회차");
    private static final Header H_EXAM_DATE = Header.of("시행예정일");
    private static final Header H_SEPARATE = Header.of("분리포장", "분리포장여부");
    private static final Header H_MATERIAL = Header.of("자재구분");

    /**
     * 세트구성 탭 업로드. 한 세트의 구성품이 <b>여러 행</b>으로 오므로 세트별로 모아 한 번에 등록한다.
     *
     * <p>★<b>세트 단위 전량 교체</b>다({@code registerBom}이 그렇게 동작한다).
     * 그래서 <b>한 세트의 구성품 중 하나라도 틀리면 그 세트는 통째로 건너뛴다</b> —
     * 일부만 넣으면 구성품이 빠진 반쪽 BOM이 만들어지고, 그 세트로 조립하면 자재가 안 빠진다.
     * 다른 세트는 영향받지 않는다.
     */
    @Transactional
    public MasterUploadResponse uploadBom(MultipartFile file) {
        ExcelSheetReader sheet = ExcelSheetReader.read(file, List.of(H_SET, H_CHILD, H_RATIO));
        Result out = new Result();

        // 세트코드 → (행번호, 구성품). 입력 순서를 지킨다.
        Map<String, List<PendingComponent>> bySet = new LinkedHashMap<>();
        for (RowView r : sheet.rows()) {
            String setCode = null;
            try {
                setCode = r.required(H_SET);
                bySet.computeIfAbsent(setCode, k -> new ArrayList<>())
                        .add(new PendingComponent(r.rowNo(), r.required(H_CHILD), r.intVal(H_RATIO),
                                r.intOrNull(H_ROUND), r.dateOrNull(H_EXAM_DATE),
                                r.boolOrNull(H_SEPARATE),
                                r.enumOrNull(H_MATERIAL, MaterialType.class, MaterialType::label)));
            } catch (BusinessException e) {
                out.error(r.rowNo(), setCode, e.getMessage());
            }
        }

        for (Map.Entry<String, List<PendingComponent>> e : bySet.entrySet()) {
            String setCode = e.getKey();
            List<PendingComponent> pending = e.getValue();
            try {
                Product parent = productRepository.findByCode(setCode)
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                                "세트 도서가 없습니다: " + setCode));
                List<BomRegisterRequest.Component> comps = new ArrayList<>();
                for (PendingComponent p : pending) {
                    Product child = productRepository.findByCode(p.childCode())
                            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                                    "구성 도서가 없습니다: " + p.childCode()));
                    comps.add(new BomRegisterRequest.Component(child.getId(), p.ratio(),
                            p.round(), p.examDate(), p.separatePack(), p.materialType()));
                }
                boolean hadBom = !productService.getBom(parent.getId()).components().isEmpty();
                productService.registerBom(parent.getId(), new BomRegisterRequest(comps));
                for (PendingComponent p : pending) {
                    if (hadBom) {
                        out.updated(p.rowNo(), setCode);
                    } else {
                        out.created(p.rowNo(), setCode);
                    }
                }
            } catch (BusinessException ex) {
                // ★세트 전체를 건너뛴다 — 반쪽 BOM은 조립할 때 자재가 안 빠진다.
                for (PendingComponent p : pending) {
                    out.error(p.rowNo(), setCode, ex.getMessage());
                }
            }
        }
        return out.build();
    }

    private record PendingComponent(int rowNo, String childCode, int ratio, Integer round,
                                    java.time.LocalDate examDate, Boolean separatePack,
                                    MaterialType materialType) {
    }

    // ── ③ 거래처별 단가 탭 ───────────────────────────────────────────
    private static final Header H_PARTNER = Header.of("거래처코드");
    private static final Header H_MAJOR = Header.of("대분류");
    private static final Header H_RATE = Header.of("공급률");
    private static final Header H_DISCOUNT = Header.of("할인액");
    private static final Header H_WEB_VIS = Header.of("web게시", "web게시여부");
    private static final Header H_USE_YN = Header.of("사용여부");

    /**
     * 거래처별 단가 탭 업로드. 키는 <b>거래처 × 대분류</b>다(도서 단위가 아니다 — 정본 34p
     * "같은 거래처라도 대분류별 공급률이 다르게 설정 가능").
     */
    @Transactional
    public MasterUploadResponse uploadSupplyRates(MultipartFile file) {
        ExcelSheetReader sheet = ExcelSheetReader.read(file, List.of(H_PARTNER, H_MAJOR));
        Result out = new Result();

        for (RowView r : sheet.rows()) {
            String key = null;
            try {
                String partnerCode = r.required(H_PARTNER);
                MajorCategory major = r.enumOrNull(H_MAJOR, MajorCategory.class, MajorCategory::label);
                if (major == null) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT, "대분류가 비어 있습니다");
                }
                key = partnerCode + "/" + major.label();
                Partner partner = partnerRepository.findByCode(partnerCode)
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                                "거래처가 없습니다: " + partnerCode));

                boolean existed = partnerSupplyRateService.exists(partner.getId(), major);
                partnerSupplyRateService.upsert(partner.getId(), major, new PartnerSupplyRateRequest(
                        r.intOrNull(H_RATE), r.intOrNull(H_DISCOUNT),
                        r.boolOrNull(H_WEB_VIS), r.boolOrNull(H_USE_YN)));
                if (existed) {
                    out.updated(r.rowNo(), key);
                } else {
                    out.created(r.rowNo(), key);
                }
            } catch (BusinessException e) {
                out.error(r.rowNo(), key, e.getMessage());
            }
        }
        return out.build();
    }

    /** 빈 칸(null)이면 기존 값. 업로드의 "적은 것만 고친다"를 만드는 한 줄이다. */
    private static <T> T or(T given, T existing) {
        return (given != null) ? given : existing;
    }

    /** 행별 결과 누적기. 세 업로드가 같은 모양으로 답해야 화면이 하나로 처리한다. */
    private static final class Result {
        private final List<MasterUploadResponse.Line> lines = new ArrayList<>();
        private int created;
        private int updated;
        private int failed;

        void created(int row, String key) {
            created++;
            lines.add(new MasterUploadResponse.Line(row, "CREATED", key, null));
        }

        void updated(int row, String key) {
            updated++;
            lines.add(new MasterUploadResponse.Line(row, "UPDATED", key, null));
        }

        void error(int row, String key, String message) {
            failed++;
            lines.add(new MasterUploadResponse.Line(row, "ERROR", key, message));
        }

        MasterUploadResponse build() {
            lines.sort(java.util.Comparator.comparingInt(MasterUploadResponse.Line::row));
            return new MasterUploadResponse(created, updated, failed, lines);
        }
    }
}
