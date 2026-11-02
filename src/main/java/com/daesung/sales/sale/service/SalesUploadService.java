package com.daesung.sales.sale.service;

import com.daesung.sales.closing.service.PeriodLockService;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.common.money.Amounts;
import com.daesung.sales.common.sequence.SequenceService;
import com.daesung.sales.inventory.entity.TxnType;
import com.daesung.sales.inventory.service.InventoryService;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.repository.PartnerRepository;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.product.repository.ProductRepository;
import com.daesung.sales.sale.dto.SalesUploadResponse;
import com.daesung.sales.sale.entity.Sale;
import com.daesung.sales.sale.repository.SaleRepository;
import com.daesung.sales.salestype.entity.SalesCategory;
import com.daesung.sales.salestype.entity.ShipmentType;
import com.daesung.sales.warehouse.entity.Warehouse;
import com.daesung.sales.warehouse.entity.WarehouseType;
import com.daesung.sales.warehouse.repository.WarehouseRepository;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 매출 엑셀 업로드(발주처 요청 추가1). 근거: 레거시 DsSales 매출가져오기.vb(salesData 일괄 INSERT, 재고 미반영).
 * 표준양식(매출 업로드양식_샘플.xlsx, 12컬럼) 파싱 → 매출 원장 일괄 등록. 재고는 건드리지 않음.
 *
 * 컬럼(0-base): 0 거래일자 · 1 거래처코드 · 2 학교코드 · 3 분류코드 · 4 도서코드 · 5 회차
 *              · 6 정가(선택) · 7 공급률(%) · 8 수량 · 9 금액(참고) · 10 구분(출고유형) · 11 메모
 * 규칙: 상품=분류코드+도서코드 조합 / 정가·공급률 미입력 시 도서마스터·거래처단가 자동조회 /
 *      공급률 0.75 소수는 ×100(=75) 정규화 / 위탁출고·취소·반품은 거부(전용 메뉴) / 금액은 Amounts로 재계산.
 */
@Service
@RequiredArgsConstructor
public class SalesUploadService {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final SaleRepository saleRepository;
    private final PartnerRepository partnerRepository;
    private final WarehouseRepository warehouseRepository;
    private final InventoryService inventoryService;
    private final ProductRepository productRepository;
    private final com.daesung.sales.product.service.PartnerSupplyRateService partnerSupplyRateService;
    private final SequenceService sequenceService;
    private final EntryNumberService entryNumberService;
    private final PeriodLockService periodLockService;

    private record Kind(ShipmentType shipmentType, SalesCategory category) {}

    /** 구분(한글) → 출고유형/회계. 위탁출고·취소·반품은 null(업로드 거부 — 레거시 매출가져오기도 출고/매출/무상만). */
    private static Kind mapKind(String gubun) {
        if (gubun == null) {
            return null;
        }
        return switch (gubun.trim()) {
            case "정상출고", "출고", "매출" -> new Kind(ShipmentType.NORMAL_SHIP, SalesCategory.SALE);
            case "증정용", "증정", "무상" -> new Kind(ShipmentType.GIFT, SalesCategory.FREE);
            case "교사용" -> new Kind(ShipmentType.TEACHER_USE, SalesCategory.FREE);
            default -> null;   // 위탁출고/취소/반품 등
        };
    }

    @Transactional
    public SalesUploadResponse upload(MultipartFile file) {
        List<SalesUploadResponse.Line> lines = new ArrayList<>();
        int imported = 0;
        int failed = 0;
        int warned = 0;
        // ★묶음번호는 **올린 날짜**로 뽑는다(줄마다 다를 수 있는 거래일자가 아니다).
        //   한 파일이 한 번의 등록이고, 파일 안에 여러 거래일자가 섞여 들어올 수 있다 —
        //   거래일자로 뽑으면 한 파일이 여러 묶음으로 쪼개져 "몇 번째 줄"을 못 센다.
        String entryNo = entryNumberService.next(LocalDate.now());
        int entrySeq = 0;

        com.daesung.sales.common.excel.ExcelSheetReader.assertReadable(file);
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {   // 0행=헤더
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                LocalDate date = parseDate(row.getCell(0));
                if (date == null) {
                    continue;   // 거래일자가 날짜가 아니면 설명행/빈행 → 스킵
                }
                int rowNo = r + 1;
                String custCode = str(row, 1);
                String catCode = str(row, 3);
                String bookCode = str(row, 4);
                int qty = intVal(row, 8);
                try {
                    Kind kind = mapKind(str(row, 10));
                    if (kind == null) {
                        throw new BusinessException(ErrorCode.INVALID_INPUT,
                                "업로드 불가 구분: " + str(row, 10) + " (정상출고/증정용/교사용만 허용 — 위탁·취소·반품은 전용 메뉴)");
                    }
                    if (qty <= 0) {
                        throw new BusinessException(ErrorCode.INVALID_INPUT, "수량이 0 이하");
                    }
                    periodLockService.assertNotLocked(date);

                    Partner partner = partnerRepository.findByCode(custCode)
                            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "거래처 없음: " + custCode));
                    Product product = findProduct(catCode, bookCode);

                    Integer unitPrice = intOrNull(row, 6);
                    if (unitPrice == null) {
                        unitPrice = product.getPrice();
                    }
                    Integer supplyRate = rate(row, 7);
                    if (supplyRate == null) {
                        supplyRate = partnerSupplyRateService.rateFor(product, partner.getId());
                        if (supplyRate == null) {
                            supplyRate = product.getSupplyRate();   // 도서 기본정보 공급률(정본 34p 바탕값)
                        }
                    }
                    if (unitPrice == null || supplyRate == null) {
                        throw new BusinessException(ErrorCode.INVALID_INPUT,
                                "정가·공급률이 없고 거래처별 단가 매핑도 없음");
                    }

                    // 표준 업로드 양식(12컬럼)에 세액 항목이 없다 → 0. 세액이 필요한 건은 등록 후 수정한다.
                    // 표준 양식 12컬럼에 할인액이 없다 → 거래처×대분류 매핑에서만 온다.
                    Integer discount = partnerSupplyRateService.discountFor(product, partner.getId());
                    Amounts amt = Amounts.of(unitPrice, supplyRate, qty, product.isTaxFree(), null, discount);
                    String salesNo = "I-" + date.format(YYYYMMDD) + "-" + sequenceService.next(SequenceService.SEQ_INVOICE);
                    Sale sale = Sale.createBulk(salesNo, date, partner, product,
                            kind.shipmentType(), kind.category(), unitPrice, supplyRate, qty,
                            amt.supplyAmount(), amt.tax(), amt.totalAmount(), str(row, 11), null);
                    sale.applyDiscount(discount);
                    sale.applyUploadDetail(str(row, 2), null, intOrNull(row, 5));   // 학교코드·(학교명 미구현)·회차
                    // 업로드는 한 파일이 곧 한 번의 등록이다 — 파일 전체에 같은 묶음번호,
                    // 줄마다 순번. 담당자가 "몇 번째 줄" 로 찾는 경로다(프론트 실측 2026-10-16 B-2).
                    sale.applyEntry(entryNo, ++entrySeq);

                    // ★파일에 적힌 금액이 서버 계산과 다르면 **그 값을 그대로 저장한다**
                    //   (10/27 §4-15 "수량·공급률·금액이 계산과 맞지 않아도 **예외가 있을 수 있다**",
                    //   프론트 B-8). 계산값으로 덮으면 예외를 적은 뜻이 사라진다.
                    //   매출등록 창의 직접 입력과 같은 취급 — `amountOverridden` 으로 표시된다.
                    //
                    // ‼️**반드시 save 前이다.** 아래 재고 반영(applyShipment)이 원자적 UPDATE라
                    //   영속성 컨텍스트를 비운다. 그 뒤에 부르면 detached 엔티티에 걸려
                    //   **조용히 아무 일도 안 일어난다**(응답엔 파일 값, 장부엔 계산값이 남았다).
                    //   매출 취소·삭제 쪽에 같은 주석이 있는 바로 그 함정이다.
                    Long fileAmount = fileSupplyAmount(row);
                    boolean overridden = fileAmount != null && fileAmount != amt.supplyAmount();
                    if (overridden) {
                        sale.overrideSupplyAmount(fileAmount);
                    }
                    long saved = overridden ? fileAmount : amt.supplyAmount();

                    saleRepository.save(sale);

                    // ★재고도 같이 뺀다. 업로드만 이게 빠져 있어서, 올린 만큼 매출은 서는데
                    //   재고는 그대로였다 — 제품수불부와 순매출 조회의 수량이 어긋나는 원인이었다
                    //   (2026-09-11 점검: 국어 개념 교재 수불부 103,515 / 순매출 103,518, 차이가
                    //   곧 업로드분이었다). 이중 장부가 갈라지면 어느 쪽이 맞는지 알 수 없게 된다.
                    // ‼️양식(12컬럼)에 창고 칸이 없다 — 물류창고로 보낸다. 업로드로 받는 구분은
                    //   정상출고·증정용·교사용뿐이고 위탁은 전용 화면이라, 실물은 물류창고에서 나간다.
                    if (product.isStockManaged()) {
                        inventoryService.applyShipment(product, mainWarehouse(), -qty,
                                TxnType.OUTBOUND, kind.shipmentType(), date, salesNo, "매출 엑셀 업로드");
                    }

                    imported++;
                    // 계산값과 다르다는 사실은 **등록을 막지 않고** 경고로 알린다
                    //   ("알럿만 주고 등록은 허용한다"). 저장값은 위에서 이미 파일 값으로 정했다.
                    //   ‼️두 번 고친 자리다 — ①처음엔 파일 금액을 **안 읽고 조용히 덮어썼고**,
                    //     ②경고를 붙인 뒤에도 **저장값은 계산값**이라 그 "예외"가 결국 버려졌다.
                    String warn = amountWarning(row, amt.supplyAmount());
                    if (warn == null) {
                        lines.add(new SalesUploadResponse.Line(rowNo, "IMPORTED", salesNo,
                                custCode, label(catCode, bookCode), qty, saved, null));
                    } else {
                        warned++;
                        lines.add(new SalesUploadResponse.Line(rowNo, "IMPORTED_WITH_WARNING", salesNo,
                                custCode, label(catCode, bookCode), qty, saved, warn));
                    }
                } catch (BusinessException e) {
                    failed++;
                    lines.add(new SalesUploadResponse.Line(rowNo, "ERROR", null,
                            custCode, label(catCode, bookCode), qty, 0, e.getMessage()));
                }
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "엑셀 파일을 읽을 수 없습니다: " + e.getMessage());
        }
        return new SalesUploadResponse(imported, failed, warned, lines);
    }

    /**
     * 양식의 <b>분류코드 + 도서코드</b>로 상품을 찾는다.
     *
     * <p>★두 체계를 모두 받는다.
     * <pre>
     *   ① 분류코드 + 도서코드   발주처 표준양식(도서코드가 분류 안에서만 고유: S2026A02 + 01)
     *   ② 도서코드 단독         우리 마스터의 전역 고유코드(BK-K2026-1)
     * </pre>
     * 예전에는 두 값을 <b>문자열로 이어붙여</b>({@code catCode + bookCode}) 전역코드와 대조했다.
     * 그러면 {@code K202601BK-K2026-1} 같은 값을 찾게 되어 <b>어떤 마스터로도 맞지 않는다</b> —
     * 업로드가 구조적으로 100% 실패했다(2026-09-11 발견).
     *
     * <p>★①을 먼저 본다. ②만 지원하면 표준양식이 통째로 실패하고,
     * ①만 지원하면 지금 마스터로 만든 파일이 전부 실패한다.
     */
    private Product findProduct(String catCode, String bookCode) {
        if (bookCode == null || bookCode.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "도서코드가 비어 있습니다");
        }
        if (catCode != null && !catCode.isBlank()) {
            Optional<Product> byPair = productRepository.findByCatCodeAndCode(catCode, bookCode);
            if (byPair.isPresent()) {
                return byPair.get();
            }
        }
        return productRepository.findByCode(bookCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "도서 없음: " + label(catCode, bookCode)
                                + " (분류코드+도서코드 조합, 또는 도서코드 단독으로 찾습니다)"));
    }

    /** 오류·결과 표시용 라벨. 분류코드가 있으면 함께 보여야 담당자가 어느 줄인지 안다. */
    private static String label(String catCode, String bookCode) {
        return (catCode == null || catCode.isBlank()) ? safe(bookCode) : catCode + "/" + safe(bookCode);
    }

    // ── POI 셀 헬퍼 ──
    private static String safe(String s) {
        return (s == null) ? "" : s;
    }

    private static String str(Row row, int i) {
        Cell c = row.getCell(i);
        if (c == null) {
            return null;
        }
        return switch (c.getCellType()) {
            case STRING -> {
                String v = c.getStringCellValue().trim();
                yield v.isEmpty() ? null : v;
            }
            case NUMERIC -> {
                double d = c.getNumericCellValue();
                yield (d == Math.floor(d)) ? String.valueOf((long) d) : String.valueOf(d);
            }
            default -> null;
        };
    }

    private static LocalDate parseDate(Cell c) {
        if (c == null) {
            return null;
        }
        try {
            if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
                return c.getLocalDateTimeCellValue().toLocalDate();
            }
            String s = (c.getCellType() == CellType.STRING) ? c.getStringCellValue().trim() : null;
            return (s == null || s.isEmpty()) ? null : LocalDate.parse(s);   // yyyy-MM-dd
        } catch (Exception e) {
            return null;
        }
    }

    private static Double dbl(Row row, int i) {
        Cell c = row.getCell(i);
        if (c == null) {
            return null;
        }
        try {
            return switch (c.getCellType()) {
                case NUMERIC -> c.getNumericCellValue();
                case STRING -> {
                    String v = c.getStringCellValue().trim().replace(",", "");
                    yield v.isEmpty() ? null : Double.parseDouble(v);
                }
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 파일에 적힌 금액(9번 칸)과 서버 계산 금액이 다르면 경고 문구, 같거나 비어 있으면 null.
     *
     * <p>근거: 10/27 피드백 §4-15 — "수량·공급률·금액이 계산과 맞지 않아도 예외가 있을 수 있다.
     * <b>알럿만 주고 등록은 허용</b>한다". 그래서 여기서 <b>막지 않는다</b>. 다만 예전처럼
     * 파일 값을 조용히 버리지도 않는다 — 담당자가 적은 금액이 바뀌었다는 사실은 알려야 한다.
     *
     * <p>★<b>저장되는 값은 파일 값이다</b>(프론트 B-8). 명세가 말한 "예외"가 바로 그 금액이라,
     * 계산값으로 덮으면 예외를 적은 뜻이 사라진다. 대신 {@code amountOverridden} 으로
     * <b>손으로 맞춘 금액</b>임을 남긴다 — 매출등록 창의 직접 입력과 같은 취급이다.
     * 계산값과 다르다는 사실은 이 경고로 알린다.
     */
    /**
     * 양식 9번 칸(금액). 비어 있으면 {@code null} — 선택 항목이다.
     * 경고 판정과 저장값이 <b>같은 값을 봐야</b> 하므로 한 곳에서 읽는다.
     */
    private static Long fileSupplyAmount(Row row) {
        Double given = dbl(row, 9);
        return (given == null) ? null : Math.round(given);
    }

    private static String amountWarning(Row row, long computed) {
        Long given = fileSupplyAmount(row);
        if (given == null) {
            return null;   // 금액 칸은 선택이다(참고용) — 비어 있으면 할 말이 없다
        }
        long g = given;
        if (g == computed) {
            return null;
        }
        return "파일의 금액(" + String.format("%,d", g) + ")이 계산값("
                + String.format("%,d", computed) + ")과 다릅니다 — 계산값으로 등록했습니다";
    }

    private static Integer intOrNull(Row row, int i) {
        Double d = dbl(row, i);
        return (d == null) ? null : (int) Math.round(d);
    }

    private static int intVal(Row row, int i) {
        Integer v = intOrNull(row, i);
        return (v == null) ? 0 : v;
    }

    /** 공급률: 0.75 소수는 ×100(=75), 75 등 1 이상은 그대로. 정수 반올림. */
    private static Integer rate(Row row, int i) {
        Double d = dbl(row, i);
        if (d == null) {
            return null;
        }
        return (int) Math.round(d <= 1.0 ? d * 100 : d);
    }

    /**
     * 업로드분이 나갈 물류창고.
     *
     * <p>표준 양식에 창고 칸이 없어 서버가 정한다. 실물창고가 여럿이면 <b>가장 먼저 만든 것</b>을 쓴다
     * — 창고를 고르는 규칙이 정해지면 그때 양식에 칸을 넣어야 한다.
     * 하나도 없으면 재고를 어디서 뺄지 알 수 없으므로 올리지 않고 막는다.
     */
    private Warehouse mainWarehouse() {
        return warehouseRepository.findByType(WarehouseType.MAIN).stream()
                .min(java.util.Comparator.comparing(Warehouse::getId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "물류창고가 없습니다. 창고관리에서 물류창고를 먼저 등록하세요."));
    }
}
