# 로컬 compose(app profile)용 이미지. 호스트 JDK 없이 컨테이너 안에서 빌드한다.
# 컨텍스트는 저장소 루트다(docker/docker-compose.yaml의 build.context).
FROM amazoncorretto:25-alpine AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon

# Corretto 25에는 JRE 전용 이미지가 없어 alpine JDK로 실행한다.
FROM amazoncorretto:25-alpine
COPY --from=build /src/build/libs/*.jar /app.jar
EXPOSE 8080
# 힙은 컨테이너 메모리 한도의 75%다(기본 25%는 작다). 나머지는 메타스페이스·스레드 스택·버퍼 몫이다.
# 1GB·2 CPU 이하에서는 JVM이 SerialGC를 고르므로 G1을 명시한다. ZGC는 같은 부하에서 RSS가 한도(1GB)에
# 닿고 처리량이 낮아 쓰지 않았다(측정은 docs/spec/2026-09-26-local-docker-compose.md 결과 절).
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app.jar"]
