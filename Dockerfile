# ============================================================================
# Uni Market API — imagen de producción (multi-stage)
# Etapa 1: build con Maven (dependencias en capa cacheada).
# Etapa 2: runtime con JRE — imagen final sin Maven ni código fuente.
# ============================================================================

FROM docker.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# Cachea el árbol de dependencias: solo se re-descarga si cambia el pom.
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM docker.io/library/eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
