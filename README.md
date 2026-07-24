# digital-money-transfer-service

ระบบบริการโอนเงินดิจิทัล (Digital Money Transfer Service) พัฒนาด้วย **Spring Boot 3.2 + Java 21** รองรับ High Concurrency, Distributed Locking (Redis/Redisson), Idempotency และ Outbox Pattern สำหรับส่ง event ไปยัง IBM MQ

**Base URL:** `http://localhost:8080/api/v1`  
**Swagger UI:** `http://localhost:8080/api/v1/swagger-ui/index.html`

---

## 1. วิธีรันตั้งแต่ศูนย์ (Quick Start)

### ความต้องการของระบบ

- Docker Desktop / Docker Engine + Docker Compose v2
- (ถ้ารัน test หรือ dev แบบ local) Java 21, Maven 3.9+

### รันทุก service ด้วยคำสั่งเดียว

```bash
docker compose up --build
```

คำสั่งนี้จะสตาร์ทครบ 4 service และเชื่อมต่อกันอัตโนมัติ:

| Service    | Container     | Port  | หน้าที่                          |
|------------|---------------|-------|----------------------------------|
| App        | `bank-app`    | 8080  | Spring Boot API                  |
| SQL Server | `bank-sqlserver` | 1433 | ฐานข้อมูลหลัก + Liquibase migration |
| Redis      | `bank-redis`  | 6379  | Cache, Distributed Lock, Idempotency, Rate Limit |
| IBM MQ     | `bank-ibmmq`  | 1414  | Message Queue (Outbox publisher) |

รอจน app ขึ้นแล้วทดสอบด้วย:

```bash
curl -s http://localhost:8080/api/v1/accounts/1 | jq
```

> **หมายเหตุ:** ครั้งแรกที่ build อาจใช้เวลา 5–10 นาที (ดาวน์โหลด image + Maven build ใน Dockerfile)  
> Seed data เริ่มต้นมีบัญชี `0000001001` (ยอด 5,000 THB) และ `0000002002` (ยอด 1,000 THB)

### รันแบบ local (ไม่ใช้ Docker สำหรับ app)

```bash
# 1. สตาร์ทเฉพาะ infrastructure
docker compose up -d sqlserver redis ibmmq

# 2. รันแอป
mvn spring-boot:run
```

---

## 2. วิธีรันเทสและดู Coverage

### รัน unit test (ไม่ต้องมี DB/Redis/MQ)

```bash
mvn test -Dtest=AccountControllerTest,TransferControllerTest,TransferServiceTest,DigitalMoneyTransferServiceApplicationTests
```

### รัน test ทั้งหมด (รวม integration test)

ต้องมี SQL Server, Redis และ IBM MQ รันอยู่ก่อน:

```bash
docker compose up -d sqlserver redis ibmmq
mvn clean test
```

### รันเฉพาะ integration test (concurrency)

```bash
mvn test -Dtest=TransferConcurrencyIntegrationTest
```

### Code Coverage

> **ยังไม่ได้ตั้งค่า JaCoCo** ใน `pom.xml` — โปรเจกต์ยังไม่มีรายงาน coverage อัตโนมัติ

ถ้าต้องการเพิ่ม coverage report ในอนาคต สามารถเพิ่ม `jacoco-maven-plugin` แล้วรัน:

```bash
mvn clean verify
# เปิดรายงาน: target/site/jacoco/index.html
```

---

## 3. ตัวอย่างเรียก API (curl)

Flow หลัก: **เปิดบัญชี → ฝาก → โอน → ดู statement**

### 3.1 เปิดบัญชี

```bash
curl -s -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{
    "ownerName": "สมชาย ใจดี",
    "currency": "THB",
    "initialBalance": 1000.00
  }' | jq
```

เก็บ `id` และ `accountNumber` จาก response ไว้ใช้ขั้นตอนถัดไป

### 3.2 ฝากเงิน

```bash
# แทน {accountId} ด้วย id จากขั้นตอน 3.1
curl -s -X POST http://localhost:8080/api/v1/accounts/{accountId}/deposit \
  -H "Content-Type: application/json" \
  -d '{"amount": 500.00}' | jq
```

### 3.3 โอนเงิน

```bash
# Idempotency-Key ต้อง unique ต่อ 1 คำขอโอน
curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: transfer-demo-001" \
  -d '{
    "fromAccountNumber": "0000001001",
    "toAccountNumber": "0000002002",
    "amount": 200.00,
    "currency": "THB"
  }' | jq
```

### 3.4 ดู Statement (ประวัติธุรกรรม)

```bash
curl -s "http://localhost:8080/api/v1/accounts/{accountId}/transactions?page=0&size=20" | jq
```

### 3.5 API เสริม

```bash
# ดูยอดคงเหลือ
curl -s http://localhost:8080/api/v1/accounts/{accountId}/balance | jq

# ดูรายละเอียดการโอน
curl -s http://localhost:8080/api/v1/transfers/{transferId} | jq
```

### ไฟล์ .http สำหรับ VS Code REST Client / IntelliJ

ดูตัวอย่างครบ flow ได้ที่ [`docs/api-examples.http`](docs/api-examples.http)

---

## 4. ตารางสรุปสถานะงาน

| หัวข้อ | สถานะ | รายละเอียด |
|--------|--------|------------|
| เปิดบัญชี (Create Account) | ✅ เสร็จ | POST `/accounts`, รองรับ initial balance + ledger entry |
| ฝากเงิน (Deposit) | ✅ เสร็จ | POST `/accounts/{id}/deposit` + distributed lock |
| ถอนเงิน (Withdraw) | ✅ เสร็จ | POST `/accounts/{id}/withdraw`, ตรวจยอดไม่พอ |
| โอนเงิน (Transfer) | ✅ เสร็จ | POST `/transfers` + Idempotency-Key + 2-account lock |
| ดู Statement | ✅ เสร็จ | GET `/accounts/{id}/transactions` (pagination) |
| Distributed Lock (Redis/Redisson) | ✅ เสร็จ | Lock ตาม account id, ordered lock ป้องกัน deadlock |
| Idempotency (Transfer) | ✅ เสร็จ | เก็บใน Redis TTL 24 ชม. |
| Outbox Pattern → IBM MQ | 🟡 บางส่วน | Scheduler ส่ง queue `TRANSFER.COMPLETED` ทุก 5 วิ — retry อัตโนมัติถ้า MQ ล่ม แต่ยังไม่มี DLQ / monitoring |
| Rate Limiting | 🟡 บางส่วน | จำกัด 10 req/min ต่อบัญชีต้นทาง เฉพาะ `/transfers` |
| Account Cache (Redis) | ✅ เสร็จ | TTL 60 วินาที, evict เมื่อมีธุรกรรม |
| Docker Compose (app + SQL + Redis + MQ) | ✅ เสร็จ | `docker compose up --build` รันได้ครบ |
| Unit Tests | ✅ เสร็จ | Controller + Service tests (MockMvc / Mockito) |
| Integration Test (Concurrency) | 🟡 บางส่วน | มี `TransferConcurrencyIntegrationTest` แต่ต้องพึ่ง infra จริง ยังไม่มี Testcontainers |
| Code Coverage (JaCoCo) | ❌ ยังไม่ทำ | ยังไม่ได้ configure ใน pom.xml |
| Authentication / Authorization | ❌ ยังไม่ทำ | API เปิด public ทั้งหมด |
| Observability (Metrics / Tracing) | ❌ ยังไม่ทำ | มี logging อย่างเดียว |

### ถ้ามีเวลาเพิ่ม จะทำต่อ

1. เพิ่ม **JaCoCo** + coverage gate ใน CI
2. ใช้ **Testcontainers** ให้ integration test รันได้โดยไม่ต้อง manual start infra
3. เพิ่ม **Spring Security** (API Key หรือ JWT)
4. Outbox **Dead Letter Queue** + alert เมื่อ publish ล้มเหลวซ้ำ
5. **Spring Actuator** + Prometheus metrics
6. สร้าง IBM MQ queue `TRANSFER.COMPLETED` อัตโนมัติผ่าน MQ script/config

---

## 5. ข้อจำกัดและสมมติฐาน

| หัวข้อ | รายละเอียด |
|--------|------------|
| สกุลเงิน | สร้างบัญชีได้เฉพาะ **THB** (`currency` ต้องเป็น `"THB"`) |
| Authentication | ไม่มี — ออกแบบสำหรับ demo / internal network |
| จำนวนเงิน | ใช้ `DECIMAL(19,4)` — ไม่รองรับ fractional satang เกิน 4 ตำแหน่ง |
| Idempotency | ใช้กับ **Transfer** เท่านั้น (header `Idempotency-Key`) — Deposit/Withdraw ไม่มี |
| Rate Limit | 10 คำขอ/นาที ต่อ `fromAccountNumber` บน endpoint `/transfers` |
| IBM MQ | Queue manager `QM1`, channel `DEV.APP.SVRCONN`, queue `TRANSFER.COMPLETED` |
| Database | SQL Server 2022, schema จัดการผ่าน **Liquibase** (`ddl-auto: validate`) |
| Concurrency | ใช้ Redisson distributed lock — ถ้า Redis ล่ม API ที่ต้อง lock จะได้ 503 |
| Seed Data | มีบัญชีทดสอบ 2 บัญชีตั้งแต่ migrate ครั้งแรก (`0000001001`, `0000002002`) |
| Platform | IBM MQ และ SQL Server image ใช้ `linux/amd64` — บน Apple Silicon อาจช้ากว่าปกติ (Rosetta emulation) |

---

## โครงสร้าง API สรุป

| Method | Path | คำอธิบาย |
|--------|------|----------|
| POST | `/accounts` | เปิดบัญชี |
| GET | `/accounts/{id}` | ดูข้อมูลบัญชี |
| GET | `/accounts/{id}/balance` | ดูยอดคงเหลือ |
| GET | `/accounts/{id}/transactions` | ดู statement |
| POST | `/accounts/{id}/deposit` | ฝากเงิน |
| POST | `/accounts/{id}/withdraw` | ถอนเงิน |
| PATCH | `/accounts/{id}/status` | เปลี่ยนสถานะ (ACTIVE/FROZEN/CLOSED) |
| POST | `/transfers` | โอนเงิน (ต้องมี `Idempotency-Key`) |
| GET | `/transfers/{id}` | ดูรายละเอียดการโอน |
