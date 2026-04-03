FROM maven:3.9-amazoncorretto-17 AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests -q

FROM amazoncorretto:17
WORKDIR /app
COPY --from=builder /app/target/finance-sentiment-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
