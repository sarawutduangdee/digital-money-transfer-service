# ==========================================
# Stage 1: Build Phase (ใช้ Maven)
# ==========================================
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app

# Copy ไฟล์ pom.xml และ source code ทั้งหมดเข้าไปใน Container
COPY pom.xml .
COPY src ./src

# สั่ง Build โค้ดให้กลายเป็นไฟล์ .jar
# (ใส่ -DskipTests เพื่อข้ามการรัน Test ไปก่อน เพราะตอน Build จะยังไม่มี DB/Redis ให้เชื่อมต่อ)
RUN mvn clean package -DskipTests

# ==========================================
# Stage 2: Run Phase (ใช้แค่ JRE เบาๆ)
# ==========================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy เฉพาะไฟล์ .jar ที่ Build เสร็จแล้วจาก Stage 1 มาใช้งาน
COPY --from=builder /app/target/*.jar app.jar

# เปิด Port 8080 ให้ภายนอกเข้ามาคุยได้
EXPOSE 8080

# คำสั่ง Start Spring Boot Application
ENTRYPOINT ["java", "-jar", "app.jar"]