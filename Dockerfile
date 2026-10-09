FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test \
    && cp build/libs/tip-*-SNAPSHOT.jar app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system tip && useradd --system --gid tip --no-create-home tip
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER tip
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75 -Dserver.port=${PORT} -jar /app/app.jar"]
