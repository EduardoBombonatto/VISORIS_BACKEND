# ==========================================
# Estágio 1: Build (Pesado - JDK + SBT)
# ==========================================
FROM sbtscala/scala-sbt:eclipse-temurin-alpine-17.0.10_7_1.9.9_3.3.3 AS builder

WORKDIR /app

# Otimização de cache
COPY build.sbt ./
COPY project ./project

# Baixar dependências
RUN sbt update

# Copiar código fonte e compilar
COPY src ./src
RUN sbt assembly

# ==========================================
# Estágio 2: Runtime (Ultra Leve - Apenas JRE)
# ==========================================
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Criar usuário sem privilégios
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copiar o JAR com o nome fixo que definimos no build.sbt
# e dar a propriedade do arquivo para o appuser
COPY --from=builder --chown=appuser:appgroup /app/target/scala-*/app.jar ./app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]