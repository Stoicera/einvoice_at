FROM eclipse-temurin:25-jdk@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
# app-exec.jar, not app.jar: the Spring Boot plugin writes the executable jar under the `exec`
# classifier so that the plain app.jar can stay the module's Maven artifact — without which the e2e
# module (and any other consumer of `app`) resolves a fat jar whose classes are hidden under
# BOOT-INF/classes. See the plugin configuration in app/pom.xml.
COPY --from=build /workspace/app/target/app-exec.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
