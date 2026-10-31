package com.daesung.sales.document;

import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 특약점 PDF 보관함이 쓸 수 있는 <b>실제 파일 크기 상한</b>을 기동 때 확인한다.
 *
 * <p>‼️왜 필요한가 — 상한이 <b>두 층</b>에 있는데 한쪽만 올리면 조용히 깨진다.
 * <ol>
 *   <li>스프링 multipart({@code spring.servlet.multipart.max-file-size}) — 넘으면 413</li>
 *   <li><b>MySQL {@code max_allowed_packet}</b> — 파일을 BLOB 으로 넣으므로 여기도 걸린다</li>
 * </ol>
 *
 * <p>운영에서 실제로 2.4MB PDF 가 <b>500</b>으로 떨어졌다(프론트 B-6):
 * {@code Packet for query is too large (5,000,423 > 1,048,576)}. 1,048,576 = 1MB 다.
 * 애플리케이션 상한만 20MB 로 올리면 <b>DB 에서 다시 막히고</b>, 그때는 413 이 아니라
 * 500 이 나가 담당자가 원인을 알 수 없다.
 *
 * <p>그래서 <b>막지 않고 크게 경고한다</b>. 기동을 막으면 이 기능 하나 때문에 전체가 안 뜨고,
 * 조용히 두면 월마감 때 수백 개를 올리다 터진다. 같은 이유로 JWT 더미 시크릿도 이렇게 알린다.
 *
 * <p>고치는 법(운영 DB):
 * <pre>
 * SET GLOBAL max_allowed_packet = 67108864;   -- 즉시(새 연결부터)
 * [mysqld] max_allowed_packet = 64M           -- 영구(my.cnf / 파라미터그룹)
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentStorageCheck {

    /** 파일 20MB 를 받으려면 패킷에 여유가 필요하다. 32MB 미만이면 경고한다. */
    private static final long NEEDED = 32L * 1024 * 1024;

    private final DataSource dataSource;

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        Long packet;
        try {
            packet = new JdbcTemplate(dataSource)
                    .queryForObject("SELECT @@max_allowed_packet", Long.class);
        } catch (RuntimeException e) {
            // MySQL 이 아니거나 변수를 못 읽는 환경 — 확인 자체가 목적이라 조용히 넘어간다.
            log.debug("max_allowed_packet 확인 생략", e);
            return;
        }
        if (packet == null || packet >= NEEDED) {
            return;
        }
        log.error("★★★ DB max_allowed_packet 이 {}바이트({}MB)라 특약점 PDF 업로드가 그 크기에서"
                        + " 500 으로 떨어진다. 운영 DB 에 `SET GLOBAL max_allowed_packet = 67108864;`"
                        + " 를 적용하고 설정 파일(my.cnf / 파라미터그룹)에도 `max_allowed_packet = 64M`"
                        + " 를 남길 것. 앱 쪽 상한만 올려도 여기서 다시 막힌다. ★★★",
                packet, packet / (1024 * 1024));
    }
}
