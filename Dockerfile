FROM eclipse-temurin:25-jdk@sha256:2d5d5e9b533342a565907c84f47a2ec298c40cde7b13a3ca85d4950fa456a641 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre-alpine@sha256:3c0a9084927a221ccd1d007fcaf614465672c0af37aaa834c5184483afe56d61
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
