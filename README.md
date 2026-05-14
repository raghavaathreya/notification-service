# Notification Service

A production-grade, event-driven notification microservice built with Java 17 and Spring Boot 3. Sends **Email**, **SMS**, and **Push** notifications through independent async consumers, with an AI-powered RAG pipeline that personalizes content using OpenAI and Pinecone before delivery.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         REST API (JWT Protected)                    │
│                                                                     │
│   POST /auth/register    POST /auth/login                           │
│   POST /notifications    GET  /notifications/user/{userId}          │
│   POST /user-context     POST /user-context/batch                   │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                    ┌──────────▼──────────┐
                    │  NotificationService │
                    │  - Dedup check       │
                    │  - Persist to DB     │
                    │  - Publish to MQ     │
                    └──────────┬──────────┘
                               │
              ┌────────────────▼────────────────┐
              │       RabbitMQ Exchange          │
              │    (notification.exchange)        │
              └──┬─────────────┬──────────────┬──┘
                 │             │              │
     ┌───────────▼──┐  ┌───────▼───┐  ┌──────▼──────┐
     │ email.queue  │  │ sms.queue │  │ push.queue  │
     └───────────┬──┘  └───────┬───┘  └──────┬──────┘
                 │             │              │
     ┌───────────▼──┐  ┌───────▼───┐  ┌──────▼──────┐
     │EmailConsumer │  │SmsConsumer│  │PushConsumer │
     │ RAG Pipeline │  │RAG Pipeline  │RAG Pipeline │
     │ JavaMail     │  │  Twilio   │  │   FCM       │
     └──────────────┘  └───────────┘  └─────────────┘
                               │
                    ┌──────────▼──────────┐
                    │     PostgreSQL       │
                    │  (status updates)    │
                    └─────────────────────┘

RAG Pipeline (per consumer, when personalize=true):
  Subject → OpenAI Embeddings → Pinecone Query
  → Context (score ≥ 0.70) → GPT-3.5-turbo → Personalized Content
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 17, Spring Boot 3.2 |
| Messaging | RabbitMQ (AMQP, manual ACK, DLQ) |
| Persistence | PostgreSQL 15 + Spring Data JPA |
| Cache / Dedup | Redis 7 |
| Auth | Spring Security + JWT (JJWT, HS256) |
| Email | JavaMail (SMTP) |
| SMS | Twilio SDK |
| Push | Firebase Admin SDK (FCM) |
| AI Embeddings | OpenAI `text-embedding-ada-002` |
| Vector Store | Pinecone |
| LLM | OpenAI `gpt-3.5-turbo` |
| Containers | Docker + Docker Compose |
| Testing | JUnit 5, Mockito, Testcontainers, MockWebServer, Awaitility |

---

## Key Design Decisions

### 1. Independent consumers per channel
Each channel has its own RabbitMQ queue and consumer bean. A Twilio outage does not affect email delivery — the queues are completely isolated. Each consumer uses manual ACK so messages are only acknowledged after the send succeeds.

### 2. Two-layer deduplication
- **Pre-queue**: a hash of `userId + type + subject` is stored in Redis with 24h TTL. Duplicate API calls return `DUPLICATE` status immediately without entering the queue.
- **Per-consumer**: the notification UUID is stored in Redis after processing. If RabbitMQ redelivers the same message after a consumer crash, the second attempt is detected and skipped with an ACK.

### 3. Graceful RAG fallback
The RAG pipeline is wrapped in try/catch in `RagPipelineService`. If Pinecone is unavailable or OpenAI rate-limits, the original notification content is used unchanged. The send still succeeds — personalization degrades gracefully rather than blocking delivery.

### 4. Invalid device token handling
FCM returns `UNREGISTERED` when a device token is stale (user uninstalled the app). `PushConsumer` catches `InvalidDeviceTokenException` and ACKs the message without retrying — retrying a permanently invalid token would just keep failing. A production extension would fire an async event to clean up the stale token from the user profile.

### 5. Stateless JWT auth
Spring Security uses `SessionCreationPolicy.STATELESS` — no server-side session is created. Every request carries a signed JWT. The JWT embeds the user's role as a claim so authorization decisions require no DB lookup per request.

### 6. User enumeration prevention
The login endpoint returns `"Invalid email or password"` for both wrong password AND non-existent email, preventing attackers from probing which emails are registered.

---

## Project Structure

```
src/main/java/com/raghav/notificationservice/
├── auth/          # JWT auth: User, JwtService, JwtAuthFilter, AuthService, AuthController
├── config/        # RabbitMQConfig, RedisConfig, SecurityConfig, FirebaseConfig
├── consumer/      # EmailConsumer, SmsConsumer, PushConsumer
├── controller/    # NotificationController, UserContextController
├── dto/           # Request/response DTOs
├── model/         # Notification entity, enums
├── producer/      # NotificationProducer (publishes to RabbitMQ)
├── rag/           # OpenAiService, PineconeService, RagPipelineService, UserContextService
├── repository/    # NotificationRepository, UserRepository
├── sender/        # EmailSender (JavaMail), SmsSender (Twilio), PushSender (FCM)
└── service/       # NotificationService, DeduplicationService

src/test/java/com/raghav/notificationservice/
├── auth/          # JwtServiceTest
├── integration/   # BaseIntegrationTest + 4 integration test classes (Testcontainers)
└── rag/           # OpenAiServiceTest, PineconeServiceTest, RagPipelineServiceTest,
                   #   UserContextServiceTest
```

---

## Getting Started

### Prerequisites
- Docker Desktop (running)
- Java 17+
- Maven 3.8+

### 1. Clone and configure

```bash
git clone https://github.com/raghav/notification-service.git
cd notification-service
cp .env.example .env
```

Edit `.env` and fill in your credentials:

```bash
# OpenAI
OPENAI_API_KEY=sk-...

# Pinecone (create a free index at pinecone.io — dimension=1536, metric=cosine)
PINECONE_API_KEY=...
PINECONE_URL=https://your-index.svc.pinecone.io

# Email (Gmail: generate App Password at myaccount.google.com/apppasswords)
SMTP_USERNAME=you@gmail.com
SMTP_PASSWORD=xxxx-xxxx-xxxx-xxxx
EMAIL_FROM=you@gmail.com

# Twilio (free trial at console.twilio.com)
TWILIO_ACCOUNT_SID=ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
TWILIO_AUTH_TOKEN=...
TWILIO_FROM_NUMBER=+1234567890

# Firebase (Firebase Console > Project Settings > Service Accounts > Generate key)
# Place the downloaded JSON as: src/main/resources/firebase-service-account.json
FIREBASE_PROJECT_ID=your-project-id

# JWT (generate: openssl rand -base64 64)
JWT_SECRET=your-base64-secret
```

### 2. Start all services

```bash
docker-compose up --build
```

PostgreSQL, Redis, RabbitMQ, and the app all start together.
RabbitMQ management UI: `http://localhost:15672` (guest/guest)

### 3. Register and get a token

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "you@example.com", "password": "yourpassword"}'

curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "you@example.com", "password": "yourpassword"}'

# Set for reuse
TOKEN="eyJhbGciOiJIUzI1NiJ9..."
```

### 4. Index user context (feeds the RAG pipeline)

```bash
curl -X POST http://localhost:8080/api/v1/user-context \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user-123",
    "text": "User purchased Sony WH-1000XM5 headphones in the premium electronics category",
    "metadata": {
      "category": "electronics",
      "product": "Sony WH-1000XM5",
      "action": "purchase"
    }
  }'
```

### 5. Send a personalized notification

```bash
curl -X POST http://localhost:8080/api/v1/notifications \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user-123",
    "type": "EMAIL",
    "recipient": "user@example.com",
    "subject": "New headphones just dropped",
    "content": "Check out our latest audio products.",
    "personalize": true
  }'
```

With `personalize: true`, the RAG pipeline retrieves purchase history from Pinecone and generates:
> *"Since you recently picked up the Sony WH-1000XM5, you might love our new Sony LinkBuds — same premium audio, true wireless."*

### 6. Check notification status

```bash
curl http://localhost:8080/api/v1/notifications/user/user-123 \
  -H "Authorization: Bearer $TOKEN"
```

---

## API Reference

### Auth

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/v1/auth/register` | None | Create account |
| POST | `/api/v1/auth/login` | None | Get JWT token |

### Notifications

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/v1/notifications` | Bearer | Send notification |
| GET | `/api/v1/notifications/{id}` | Bearer | Get by ID |
| GET | `/api/v1/notifications/user/{userId}` | Bearer | Get all for user |

**Send Notification payload:**
```json
{
  "userId": "user-123",
  "type": "EMAIL",
  "recipient": "user@example.com",
  "subject": "Your order shipped",
  "content": "Your order #456 has been dispatched.",
  "personalize": true
}
```

`type`: `EMAIL` | `SMS` | `PUSH`
For SMS, `recipient` is a phone number (E.164 format).
For PUSH, `recipient` is an FCM device registration token.

### User Context

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/v1/user-context` | Bearer | Index a user event |
| POST | `/api/v1/user-context/batch` | Bearer | Index multiple events |
| DELETE | `/api/v1/user-context/{userId}` | Bearer | Delete all context (GDPR) |

---

## Running Tests

```bash
# All tests (Docker must be running for integration tests)
mvn test

# Unit tests only — no Docker needed, fast
mvn test -Dtest="OpenAiServiceTest,PineconeServiceTest,RagPipelineServiceTest,UserContextServiceTest,JwtServiceTest"

# Integration tests only
mvn test -Dtest="*IntegrationTest"
```

**Test coverage:**

| Suite | Tests | What it covers |
|---|---|---|
| `OpenAiServiceTest` | 7 | HTTP payload shape, embedding parsing, error handling |
| `PineconeServiceTest` | 9 | Vector query, score threshold (≥0.70), upsert, GDPR delete |
| `RagPipelineServiceTest` | 8 | Orchestration, prompt contents, graceful fallback |
| `UserContextServiceTest` | 13 | Upsert pipeline, deterministic IDs, batch partial failures |
| `JwtServiceTest` | 14 | Token generation, claim extraction, expiry, tampered signatures |
| `AuthIntegrationTest` | 12 | Register, login, duplicate email, protected endpoint access |
| `NotificationFlowIntegrationTest` | 8 | Full async send flow, deduplication, personalization |
| `DeduplicationIntegrationTest` | 10 | Redis key TTLs, case sensitivity, UUID idempotency |
| `UserContextIntegrationTest` | 7 | Upsert API, batch, GDPR delete, auth enforcement |
| **Total** | **88** | |

---

## Environment Variables

| Variable | Required | Description |
|---|---|---|
| `OPENAI_API_KEY` | Yes | OpenAI API key |
| `PINECONE_API_KEY` | Yes | Pinecone API key |
| `PINECONE_URL` | Yes | Pinecone index endpoint |
| `SMTP_USERNAME` | Yes | SMTP email address |
| `SMTP_PASSWORD` | Yes | SMTP password or App Password |
| `EMAIL_FROM` | Yes | Sender email address |
| `TWILIO_ACCOUNT_SID` | Yes | Twilio account SID |
| `TWILIO_AUTH_TOKEN` | Yes | Twilio auth token |
| `TWILIO_FROM_NUMBER` | Yes | Twilio phone number (E.164) |
| `FIREBASE_PROJECT_ID` | Yes | Firebase project ID |
| `FIREBASE_CREDENTIALS_PATH` | No | Path to service account JSON |
| `JWT_SECRET` | Yes | Base64-encoded HS256 key (min 32 bytes) |
| `JWT_EXPIRATION_MS` | No | Token TTL in ms (default: 86400000) |

---

## What I'd Add With More Time

- **Token refresh** — short-lived access tokens (15 min) + longer-lived refresh tokens (7 days) so users don't re-login every 24h.
- **Rate limiting** — per-user notification throttling via Redis counters to prevent accidental or malicious notification floods.
- **Distributed tracing** — Micrometer + OpenTelemetry to trace a request from REST call through RabbitMQ to consumer to sender in a single trace.
- **Metrics** — RabbitMQ queue depths, per-channel send rates, and RAG pipeline latency via Prometheus + Grafana.
- **DLQ processing** — a scheduled job to replay failed messages from the dead-letter queue with exponential backoff, moving to permanent failure store after N attempts.
- **Template engine** — replace the hardcoded HTML wrapper in `EmailSender` with Thymeleaf templates so layouts can be edited without touching Java.
- **Webhook channel** — a fourth consumer for `WEBHOOK` type so internal services receive notifications as HTTP callbacks.
