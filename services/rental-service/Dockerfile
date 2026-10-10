FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S itms && adduser -S -G itms itms
WORKDIR /app
COPY target/rental-service.jar app.jar
USER itms
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
