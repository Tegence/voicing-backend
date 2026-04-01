# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests -q

# Runtime stage - use Debian for glibc compatibility with ONNX Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy the shaded jar (not the original- prefixed one)
COPY --from=build /app/target/VoicingBackend-1.0-SNAPSHOT.jar app.jar

# Copy only non-model resources (models loaded from GCS volume)
COPY --from=build /app/src/main/resources/*.properties ./resources/
COPY --from=build /app/src/main/resources/*.json ./resources/
COPY --from=build /app/src/main/resources/*.txt ./resources/
COPY --from=build /app/src/main/resources/cmudict* ./resources/

# Environment variables
ENV PORT=9090
ENV JAVA_OPTS="-Xmx512m -Xms256m"

EXPOSE 9090

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD timeout 3 bash -c '</dev/tcp/localhost/9090' || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
