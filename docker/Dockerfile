# 로컬 compose(app profile)용 이미지. 호스트 JDK 없이 컨테이너 안에서 빌드한다.
# 컨텍스트는 저장소 루트다(docker/docker-compose.yaml의 build.context).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon

FROM eclipse-temurin:25-jre
COPY --from=build /src/build/libs/*.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
