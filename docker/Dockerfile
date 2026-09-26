# 로컬 compose(app profile)용 이미지. 호스트 JDK 없이 컨테이너 안에서 빌드한다.
# 컨텍스트는 저장소 루트다(docker/docker-compose.yaml의 build.context).
FROM amazoncorretto:25 AS build
# gradlew가 xargs를 쓰는데 Amazon Linux 2023 최소 이미지에는 없다.
RUN dnf install -y findutils && dnf clean all
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon

# Corretto 25에는 JRE 전용 이미지가 없어 GUI 라이브러리를 뺀 headless JDK로 실행한다.
FROM amazoncorretto:25-headless
COPY --from=build /src/build/libs/*.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
