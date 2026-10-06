# Runtime image for appointment-service. Build the jar first (tools/dev/up.sh does this).
# Production builds must use the Authority's mirrored, scanned base image (SEC-023, ADR-009).
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S itms && adduser -S -G itms itms
WORKDIR /app
COPY target/appointment-service.jar app.jar
USER itms
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
