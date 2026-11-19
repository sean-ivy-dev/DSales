# ★jar 는 이미지 밖(CI test 잡)에서 빌드해 넣는다 — 이미지 안에서 다시 컴파일하지 않는다.
#   예전 멀티스테이지(이미지 안 gradlew bootJar)는 캐시가 없어 배포마다 의존성 다운로드+컴파일을
#   처음부터 반복했다. ‼️로컬에서 이미지를 만들 땐 먼저:
#     ./gradlew bootJar && cp build/libs/*-SNAPSHOT.jar build/libs/app.jar
FROM eclipse-temurin:21-jre

# ★타임존을 한국으로 고정한다.
#   컨테이너 기본이 UTC라 LocalDate.now() 가 오전 9시 이전에는 **전날**을 돌려준다.
#   이 값이 매출취소 역분개 날짜·마감(period_lock) 판정·대시보드 기본기간에 그대로 쓰여,
#   아침에 취소 한 건 넣으면 전날 장부가 움직이고 그 달이 마감돼 있으면 거부된다.
#   로그 시각이 CloudWatch에서 9시간 어긋나 보이던 것도 같은 원인이다.
ENV TZ=Asia/Seoul
WORKDIR /app

# ★메일 서버 TLS 를 위한 중간 CA 주입(2026-09-28).
#   mail.dshw.co.kr 이 자기 인증서 하나만 보내고 중간 CA 를 안 보내서 Java 가 체인을 못 만든다
#   (루트는 기본 신뢰저장소에 있다 — 빠진 건 그 사이 한 장뿐이다).
#   Windows(.NET)는 이 조각을 알아서 받아와 레거시는 문제가 없었고 Java 만 막혔다.
#
#   ‼️AIA 옵션(-Dcom.sun.security.enableAIAcaIssuers=true)으로 해결하려 했으나
#     실제로 돌려 보니 켜도 그대로 실패했다. 확인된 방법으로 간다. 자세한 경위는 config/certs/README.md.
#   ‼️인증서 검증을 끄는 방법(mail.smtp.ssl.trust)은 쓰지 않는다 — 레거시보다 약해진다.
COPY config/certs/sectigo-public-server-auth-ca-ov-r36.pem /tmp/mail-ca.pem
RUN keytool -importcert -noprompt -alias sectigo-public-server-auth-ca-ov-r36 \
        -file /tmp/mail-ca.pem -cacerts -storepass changeit \
    && rm /tmp/mail-ca.pem

COPY build/libs/app.jar app.jar

# ★★root 로 돌지 않는다(보안 점검 2026-11-19, semgrep `missing-user-entrypoint`).
#   기본값은 root 라, 앱이 뚫렸을 때 컨테이너 안에서 바로 root 권한을 쥔다.
#   ‼️USER 는 **COPY 뒤·ENTRYPOINT 앞**에 둔다 — 앞에 두면 위의 keytool(인증서 주입)이
#     `/opt/java/.../cacerts` 를 못 고쳐 기동 자체가 깨진다.
#   8080 은 1024 보다 커서 비특권 사용자도 그대로 연다(포트 변경 불필요).
RUN useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin app \
    && chown -R app:app /app
USER app

EXPOSE 8080
# ‼️TZ 환경변수만으로는 OS의 tz 파일 유무에 기댄다. JVM 옵션으로 한 번 더 못박아
#   어느 베이스 이미지에서든 같은 날짜가 나오게 한다(LocalDate.now() 가 장부 날짜가 된다).
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]
