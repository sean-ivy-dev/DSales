package com.daesung.sales.logistics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 박스별 송장 <b>통째 교체</b> — 10/27 명세 §8-3, 프론트 E-2.
 *
 * <p>명세: "주문은 한 줄이지만 <b>송장번호는 박스 수만큼 여러 개</b> 등록할 수 있게 한다."
 *
 * <p>★줄 단위로 고치지 않고 <b>보낸 목록으로 교체</b>한다. 박스 수가 줄면 남는 줄을 지워야 하고,
 * 번호가 바뀌면 어느 줄이 어느 박스인지 짝짓기가 애매해진다. 화면도 "표를 통째로 저장"이다.
 * <b>빈 배열을 보내면 송장을 모두 지운다</b>(잘못 넣은 것을 되돌리는 길).
 */
public record TrackingsUpdateRequest(

        @Schema(description = """
                박스별 송장. **보낸 목록이 그대로 저장된다**(기존 줄은 사라진다).
                박스 번호는 1부터, 한 발송 안에서 겹치면 안 된다.""")
        @Valid List<Line> trackings
) {

    @Schema(name = "TrackingLine", description = "박스 한 줄")
    public record Line(

            @Schema(description = "박스 번호(1부터)", example = "1",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @Positive(message = "박스 번호는 1부터입니다") int boxNo,

            @Schema(description = "택배사", example = "CJ대한통운")
            String courierName,

            @Schema(description = "송장번호", example = "123456789012",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "송장번호는 비울 수 없습니다 — 지우려면 그 줄을 빼세요")
            String trackingNo
    ) {
    }
}
