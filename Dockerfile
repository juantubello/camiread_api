# Etapa de build (Maven con Java 21, ajustá si usás otra versión)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copiamos el descriptor y descargamos dependencias
COPY pom.xml .
COPY mvnw .
COPY .mvn .mvn
RUN ./mvnw dependency:go-offline -B

# Copiamos el código y compilamos
COPY src src
RUN ./mvnw clean package -DskipTests

# Etapa de runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copiamos el jar generado (ajustá el nombre si es distinto)
COPY --from=build /app/target/*.jar app.jar

# Variables opcionales para tunear la JVM
ENV JAVA_OPTS=""

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]