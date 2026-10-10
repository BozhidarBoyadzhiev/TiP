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
# The JVM sizes its heap from the container's memory limit; its default of 25 percent wastes most of a
# 512 MB instance. JAVA_TOOL_OPTIONS is read by the JVM itself, so no shell is needed to pass it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
# The app listens on PORT when the platform sets it (Render does), otherwise on 8080.
EXPOSE 8080
# Exec form: java is PID 1 and receives SIGTERM, so the graceful shutdown closes WebSocket sessions with 1001.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
