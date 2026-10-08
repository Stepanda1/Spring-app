FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY rabbitmq-amqp-tutorials/pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY rabbitmq-amqp-tutorials/src src
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /build/target/*.jar app.jar
USER app
EXPOSE 8088
ENTRYPOINT ["java", "-jar", "app.jar"]
