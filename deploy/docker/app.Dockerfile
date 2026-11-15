# skawau アプリ（scripts/dist.sh の成果物を詰めるだけ）
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S skawau && adduser -S -G skawau skawau
COPY dist/lib /app/lib
COPY dist/static /app/static
ENV STATIC_DIR=/app/static HTTP_PORT=8080
USER skawau
EXPOSE 8080
CMD ["java", "-XX:MaxRAMPercentage=70", "-cp", "/app/lib/*", "skawau.app.Main"]
