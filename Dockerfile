# The image a host runs. It packages the distribution Gradle has already built (./gradlew installDist), so neither a
# build tool nor the GitHub Packages token ever enters an image layer.
FROM eclipse-temurin:21-jre

RUN useradd --system --uid 10001 --no-create-home server
COPY build/install/server /opt/server

USER 10001
ENV PORT=8080
# The JVM would take a quarter of the container's memory for its heap; a host can override this.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080
ENTRYPOINT ["/opt/server/bin/server"]
