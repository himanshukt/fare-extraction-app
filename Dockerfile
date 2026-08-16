# Build stage
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom.xml and download dependencies to leverage Docker cache
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy the rest of the source code and build the application
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy the built JAR from the build stage
COPY --from=build /app/target/*.jar app.jar

# Expose the default Spring Boot port
EXPOSE 8080

# Set standard JVM options for memory management (especially useful for PDFBox)
# -Xmx2g: Sets the maximum heap size to 2GB to prevent OutOfMemoryErrors
# -Xms512m: Sets the initial heap size to 512MB
ENV JAVA_OPTS="-Xmx2g -Xms512m -XX:+UseG1GC"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
