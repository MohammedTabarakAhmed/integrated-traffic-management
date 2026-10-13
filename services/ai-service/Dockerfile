FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/ai-service.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65.0","-jar","/app/app.jar"]
