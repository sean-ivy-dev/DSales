package com.daesung.sales.logistics.entity;

/**
 * 택배사(송장번호와 함께 선택). 근거: 10/31 클라이언트 회신 §2(10/26 §4 송장번호 조회 보완) —
 * "개인(선생님) 택배는 대부분 CJ대한통운이다. 대량 발송은 대신화물 택배를 쓰기도 한다."
 *
 * <pre>
 *   입력      작업요청서 [송장번호 입력]에서 송장번호와 함께 택배사를 선택. 기본값 CJ대한통운.
 *   엑셀 일괄  택배사 열 추가. 비어 있으면 CJ대한통운으로 넣는다. 목록에 없는 값은 업로드 전 오류.
 *   과거 건   courier_name 이 비어 있으면 CJ대한통운으로 간주한다.
 * </pre>
 *
 * <p>★{@link DeliveryType}(택배/화물 — "어떻게 보내나")과 <b>다른 축</b>이다. 이쪽은
 * "어느 택배사로 보내나"다 — 발송구분이 COURIER(택배)일 때만 의미가 있다.
 *
 * <p>⚠️대신택배 배송조회 URL·송장번호 파라미터 형식은 아직 실측 전이다(10/31 §2 "추측한
 * URL로 넣지 않는다, 실제 송장으로 확인"). 그래서 조회 URL을 만드는 로직은 여기 두지
 * 않는다 — "송장번호를 누르면 새 창이 열린다"는 사이트가 만들고, 우리는 택배사 값을
 * 코드로만 내려준다(10/26 §4 "API는 연동하지 않는다").
 */
public enum CourierCompany {

    /** 기본값. 개인(선생님) 소량 발송 대부분. */
    CJ("CJ대한통운"),

    /** 대량 발송. 회신 원문은 "대신화물 택배"라 두 표기를 다 받는다. */
    DAESIN("대신택배", "대신화물");

    private final String label;
    private final String[] aliases;

    CourierCompany(String label, String... aliases) {
        this.label = label;
        this.aliases = aliases;
    }

    public String label() {
        return label;
    }

    /** 과거 건·미지정 입력의 기본값(10/31 §2 "비어 있으면 CJ대한통운으로 간주"). */
    public static final CourierCompany DEFAULT = CJ;

    /**
     * 코드명·한글(라벨/별칭) 전부 받는다. 빈 값은 {@code null}(호출부가 기본값 처리를 정한다 —
     * 입력 경로마다 "비어도 되는지"가 다르다: 단건 입력은 비워 둘 수 있고, 엑셀 일괄은
     * 비면 CJ로 채운다).
     *
     * <p>‼️모르는 값은 그대로 거부한다 — 오타가 조용히 통과하면 나중에 "그 택배사가
     * 뭔지 몰라 링크를 못 연다"는 문제로 터진다.
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    public static CourierCompany from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        for (CourierCompany c : values()) {
            if (c.name().equals(v) || c.label.equals(v)) {
                return c;
            }
            for (String alias : c.aliases) {
                if (alias.equals(v)) {
                    return c;
                }
            }
        }
        throw new IllegalArgumentException("알 수 없는 택배사입니다: " + raw + " (CJ대한통운/대신택배)");
    }

    /**
     * 비어 있으면 기본값(CJ대한통운)으로 채우고, 모르는 값은 <b>거부</b>한다 —
     * 입력 경로(엑셀 일괄 업로드)에서만 쓴다. 오타를 조용히 통과시키지 않는다.
     *
     * @throws IllegalArgumentException 모르는 값
     */
    public static String labelOrDefault(String raw) {
        CourierCompany c = (raw == null || raw.isBlank()) ? null : from(raw);
        return (c != null) ? c.label : DEFAULT.label;
    }

    /**
     * 비어 있으면 기본값(CJ대한통운), 모르는 값이면 <b>그 값을 그대로 돌려준다</b> —
     * 조회·노출 경로에서만 쓴다. 이 enum이 생기기 전 자유입력으로 저장된 과거 값
     * (오타·다른 표기)이 있을 수 있어, 모른다고 던지면 그 발송 건을 보여줄 수조차 없게 된다
     * (10/31 §2 "과거 건은 courier_name이 비어 있으면 CJ대한통운으로 간주"의 확장 —
     * 비어 있지 않은 모르는 값은 가진 그대로 보여주는 쪽이 안전하다).
     */
    public static String displayLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT.label;
        }
        try {
            CourierCompany c = from(raw);
            return (c != null) ? c.label : raw;
        } catch (IllegalArgumentException e) {
            return raw;
        }
    }
}
