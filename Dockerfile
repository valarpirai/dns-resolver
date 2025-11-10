# Multi-stage build for DNS Resolver
FROM maven:3.9-amazoncorretto-21-alpine AS builder

# Set working directory
WORKDIR /app

# Copy pom.xml and download dependencies (layer caching)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests

# Runtime stage
FROM azul/zulu-openjdk-alpine:21-jre

# Set working directory
WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /app/target/dns-resolver.jar ./dns-resolver.jar

# Expose DNS port (UDP 53)
EXPOSE 53/udp

# Run as non-root user for security
RUN addgroup -S dnsgroup && adduser -S dnsuser -G dnsgroup
USER dnsuser

# Set entrypoint
ENTRYPOINT ["java", "-jar", "dns-resolver.jar"]

# Default command (can be overridden)
CMD ["53"]
