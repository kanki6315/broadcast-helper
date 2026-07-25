# Single-container image for hosting (Phase 4a): the Spring Boot backend serves
# the built React bundle as static assets, with the Python entry-list parser
# sidecar bundled in. Build from the repo root: `docker build -t pit-pass .`

# 1) Build the React bundle.
FROM node:20-slim AS frontend
WORKDIR /fe
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# 2) Build the Spring Boot jar, with the frontend bundle folded in as static
#    resources (HashRouter → no server-side SPA fallback needed; deep links are
#    #/... and all resolve to index.html at /).
FROM eclipse-temurin:21-jdk-jammy AS backend
WORKDIR /app
COPY backend/ ./
COPY --from=frontend /fe/dist/ src/main/resources/static/
RUN ./gradlew bootJar --no-daemon -x test

# 3) Runtime: JRE + Python (venv with pdfplumber) + the parser scripts + the jar.
#    Pin the Ubuntu 22.04 LTS base (Python 3.10) — the default/rolling tag ships a
#    bleeding-edge Python (3.14) whose missing native wheels crash pdfplumber (SIGILL).
FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-venv \
    && python3 -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir pdfplumber \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY parser/ ./parser/
COPY --from=backend /app/build/libs/backend-*.jar app.jar
# Point the sidecar config at the in-image Python venv and parser script.
ENV PARSER_PYTHON=/opt/venv/bin/python3 \
    PARSER_SCRIPT=/app/parser/parse_entry_list.py \
    TEAM_SHEET_PARSER_SCRIPT=/app/parser/extract_team_sheet_pages.py \
    POINTS_PARSER_SCRIPT=/app/parser/parse_points.py
# Documentation only, and only meaningful when $PORT is unset — Railway injects
# it. Kept in step with the application.yml fallback so the two never disagree.
EXPOSE 8731
# Size the heap to the container's memory limit (PaaS instances are small).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
