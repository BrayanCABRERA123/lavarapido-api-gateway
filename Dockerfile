# syntax=docker/dockerfile:1

# ---- Build ---------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Primero las dependencias: esta capa se reutiliza hasta que cambie pom.xml.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Las pruebas corren en CI / local (./mvnw verify), no en cada build de la imagen.
RUN ./mvnw -B -q package -DskipTests && cp target/api-gateway-*.jar app.jar

# ---- Runtime -------------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
