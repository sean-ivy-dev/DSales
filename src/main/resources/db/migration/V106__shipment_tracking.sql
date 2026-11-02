-- 송장번호 여러 개 — 10/27 명세 §8-3, 프론트 E-2.
--
-- 명세: "주문은 한 줄이지만 **송장번호는 박스 수만큼 여러 개** 등록할 수 있게 한다."
-- PPT 18: "한 주문 건은 하나로 합쳐질 수 있도록. **송장은 박스에 따라 여러 개가 등록 가능하도록.**"
--
-- ‼️레거시에는 송장 개념이 **아예 없다**(`sendData` 에 BoxCount 만 있고 송장 컬럼이 없다).
--   V44 에서 우리가 신설한 축이라 따를 선례가 없고, 위 두 문장이 유일한 기준이다.
--
-- ★기존 `shipment.courier_name/tracking_no` 를 **지우지 않는다.**
--   화면·엑셀 양식·업로드가 아직 그 단일 필드를 쓴다. 바꾸면 이번 주에 쓰던 흐름이 끊긴다.
--   대신 **자식 테이블이 정본**이고, 단일 필드는 **1번 박스를 비추는 거울**로 둔다
--   (갱신은 한 메서드만 하도록 묶어 둘이 갈릴 수 없게 했다 — Shipment.replaceTrackings).
CREATE TABLE shipment_tracking (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    shipment_id   BIGINT       NOT NULL,
    box_no        INT          NOT NULL COMMENT '박스 번호(1부터). 박스 수만큼 줄이 생긴다',
    courier_name  VARCHAR(50)  NULL     COMMENT '택배사',
    tracking_no   VARCHAR(50)  NULL     COMMENT '송장번호',
    created_at    DATETIME     NOT NULL,
    created_by    VARCHAR(50)  NULL,
    CONSTRAINT fk_shipment_tracking FOREIGN KEY (shipment_id) REFERENCES shipment (id)
) COMMENT '발송 건의 송장 — 박스마다 한 줄(명세 §8-3)';

-- 한 발송 안에서 박스 번호는 유일하다. 같은 번호가 둘이면 어느 쪽이 그 박스인지 알 수 없다.
CREATE UNIQUE INDEX ux_shipment_tracking_box ON shipment_tracking (shipment_id, box_no);

-- 송장번호로 되짚는 조회(택배사 문의 대응).
CREATE INDEX ix_shipment_tracking_no ON shipment_tracking (tracking_no);

-- ★이미 입력된 송장을 **1번 박스로 옮겨 둔다.** 안 옮기면 자식 테이블이 정본이 된 뒤
--   기존 건의 송장이 목록에서 사라진 것처럼 보인다.
INSERT INTO shipment_tracking (shipment_id, box_no, courier_name, tracking_no, created_at, created_by)
SELECT id, 1, courier_name, tracking_no, COALESCE(updated_at, created_at, NOW()), 'V106'
  FROM shipment
 WHERE tracking_no IS NOT NULL AND tracking_no <> '';
