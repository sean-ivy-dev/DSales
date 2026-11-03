package com.daesung.sales.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code DELETE /orders/{reqCd}}(OrderController, DSRE 모의고사 신청)와
 * 주문조회 화면의 주문 삭제 경로가 겹쳐서 <b>둘 다 라이브에서 500(Ambiguous handler
 * methods)이 났던 회귀</b>(2026-11-03 전수 호출로 발견).
 *
 * <p>★<b>왜 실제 컨트롤러 대신 더미를 쓰나</b> — {@code OrderController}는
 * {@code @ConditionalOnProperty(daesung.dsre.enabled)} 뒤에 있고,
 * {@link com.daesung.sales.support.IntegrationTestSupport}는 <b>모든</b> 통합테스트에서
 * {@code daesung.dsre.enabled=false}로 고정한다(DSRE 복제본 없이는 켤 수도 없다) —
 * 그래서 실제 컨트롤러 둘이 같은 Spring context에 한 번도 같이 안 올라오고, 둘이 동시에
 * 있을 때만 터지는 이 버그를 통합테스트가 못 잡았다. 여기선 실제 애플리케이션 컨텍스트
 * 없이 <b>표준 MockMvc standalone 셋업</b>으로 Spring MVC의 실제 라우팅만 떼어내 검증한다 —
 * 매핑 애너테이션은 운영 코드와 패턴을 동일하게 맞춘 더미 컨트롤러를 등록해, 진짜
 * DispatcherServlet이 어느 쪽으로 보내는지(또는 등록·요청이 깨지는지) 직접 본다.
 *
 * <p>★<b>처음 시도한 수정(정규식 제약)은 틀렸었다</b> — {@code reqCd}에 {@code :[0-9]+}를
 * 걸면 될 줄 알았으나, Spring의 PathPattern 특이성 비교는 "정규식 제약이 있는 변수 1개
 * 세그먼트"와 "제약 없는 변수 1개 세그먼트"를 <b>똑같이</b> 본다 — 아래 테스트가 그걸
 * 직접 재현해 고정한다. 실제로 가른 것은 리터럴 세그먼트(`/entries`)로 패턴 자체를
 * 다르게 만든 것이다.
 */
@DisplayName("/orders 경로 충돌 회귀(2026-11-03 라이브 전수 호출 발견)")
class OrderPathCollisionTest {

    @RestController
    @RequestMapping("/orders")
    static class ReqCdController {
        // 운영 OrderController.delete() 와 같은 매핑(reqCd 숫자 제약은 유지 — 입력 모양 guard).
        @DeleteMapping("/{reqCd:[0-9]+}")
        public String delete(@PathVariable int reqCd) {
            return "reqCd:" + reqCd;
        }
    }

    @RestController
    @RequestMapping("/orders")
    static class OrderNoControllerUnconstrained {
        // ‼️고치기 전(버그 재현용) — orders/{orderNo} 그대로, reqCd 와 패턴이 같아 겹친다.
        @DeleteMapping("/{orderNo}")
        public String delete(@PathVariable String orderNo) {
            return "orderNo:" + orderNo;
        }
    }

    @RestController
    @RequestMapping("/orders")
    static class OrderNoControllerFixed {
        // 운영 SalesOrderController.delete() 와 같은 매핑(고친 뒤) — entries 리터럴로 분리.
        @DeleteMapping("/entries/{orderNo}")
        public String delete(@PathVariable String orderNo) {
            return "orderNo:" + orderNo;
        }
    }

    @Test
    @DisplayName("버그 재현 — reqCd에 숫자 제약을 걸어도 /{orderNo}와는 여전히 겹친다")
    void 정규식_제약만으로는_안_풀린다() throws Exception {
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new ReqCdController(), new OrderNoControllerUnconstrained())
                .build();

        // ‼️두 핸들러 메서드가 메시지에 나열되는 순서는 HashMap 순회라 비결정적이다
        // (실행마다 바뀐다) — 원인 타입·두 핸들러가 모두 언급되는지만 본다, 문구 전체를 고정하지 않는다.
        assertThatThrownBy(() -> mvc.perform(delete("/orders/999999999")))
                .as("라이브에서 실제로 500으로 터진 바로 그 원인 — 정규식 제약은 특이성에 안 반영된다")
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .satisfies(e -> {
                    Throwable root = e;
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(root.getMessage())
                            .contains("Ambiguous handler methods mapped for '/orders/999999999'")
                            .contains("OrderNoControllerUnconstrained.delete")
                            .contains("ReqCdController.delete");
                });
    }

    @Test
    @DisplayName("실제 수정 — entries 리터럴로 가른 뒤엔 숫자/entryNo 둘 다 제 컨트롤러로 간다")
    void entries_리터럴로_분리하면_정상라우팅() throws Exception {
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new ReqCdController(), new OrderNoControllerFixed())
                .build();

        mvc.perform(delete("/orders/999999999"))
                .andExpect(status().isOk())
                .andExpect(content().string("reqCd:999999999"));

        mvc.perform(delete("/orders/entries/E-20261101-55"))
                .andExpect(status().isOk())
                .andExpect(content().string("orderNo:E-20261101-55"));

        // ‼️MockMvc standalone은 실제 서블릿 컨테이너가 아니라 %23→# URL 디코딩을 안 한다
        // (그건 OrderDeleteIntegrationTest.옛주문은_거부가 실제 서버로 이미 검증한다) —
        // 여기선 리터럴 세그먼트(entries)가 라우팅을 가른다는 것만 본다.
        mvc.perform(delete("/orders/entries/%23123"))
                .andExpect(status().isOk())
                .andExpect(content().string("orderNo:%23123"));
    }

    @Test
    @DisplayName("회귀 고정 — 고친 두 컨트롤러를 같이 등록해도 깨지지 않는다")
    void 고친뒤_등록성공() {
        assertThat(MockMvcBuilders
                .standaloneSetup(new ReqCdController(), new OrderNoControllerFixed())
                .build())
                .isNotNull();
    }
}
