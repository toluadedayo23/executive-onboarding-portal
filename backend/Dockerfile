FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q package -Dmaven.test.skip=true
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache su-exec
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
COPY docker-entrypoint.sh /entrypoint.sh
RUN sed -i 's/\r$//' /entrypoint.sh && chmod 755 /entrypoint.sh && mkdir -p /var/lib/exec-platform/uploads && chown -R app:app /var/lib/exec-platform
EXPOSE 8080
ENTRYPOINT ["/entrypoint.sh"]
