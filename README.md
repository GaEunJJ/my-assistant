# 📄 myassistant — 내 문서로 만드는 나만의 AI 어시스턴트

> PDF, DOCX 등 문서를 업로드하면, 그 문서를 기반으로 AI가 실시간으로 답변해주는 멀티테넌트 RAG 챗봇 서비스

![Java](https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5-brightgreen?style=flat-square&logo=springboot)
![Spring AI](https://img.shields.io/badge/Spring_AI-1.1-green?style=flat-square)
![WebFlux](https://img.shields.io/badge/WebFlux-Reactive-blue?style=flat-square)
![PostgreSQL](https://img.shields.io/badge/PgVector-PostgreSQL-336791?style=flat-square&logo=postgresql)

> 프런트엔드 저장소: [myassistant-frontend](https://github.com/GaEunJJ/myassistant-frontend) (Next.js 16 / React 19)

---

## 🗂 목차

- [프로젝트 개요](#-프로젝트-개요)
- [핵심 기능](#-핵심-기능)
- [기술 스택](#-기술-스택)
- [시스템 아키텍처](#-시스템-아키텍처)
- [주요 설계 결정 (ADR)](#-주요-설계-결정-adr)
- [데이터 모델](#-데이터-모델)
- [프로젝트 구조](#-프로젝트-구조)
- [로컬 실행 방법](#-로컬-실행-방법)
- [테스트](#-테스트)
- [API 명세](#-api-명세)
- [성능 개선 지표](#-성능-개선-지표)
- [향후 개선 계획](#-향후-개선-계획)

---

## 💡 프로젝트 개요

myassistant는 사용자가 업로드한 문서(PDF, DOCX, TXT)를 기반으로 AI와 대화할 수 있는 **멀티테넌트 RAG(Retrieval-Augmented Generation) 챗봇 서비스**입니다.

### 만들게 된 배경

기존 챗봇 서비스는 LLM이 학습한 일반 지식만 답변에 활용합니다.  
myassistant는 **사용자 본인의 문서**를 기반으로 답변을 생성하여, 다음 문제를 해결합니다.

- 내부 문서나 개인 자료에 대한 질문에 정확한 답변 불가능
- 답변 근거를 확인할 수 없어 신뢰도 부족
- LLM 응답 지연으로 인한 사용자 경험 저하

---

## ✨ 핵심 기능

| 기능 | 설명 |
|---|---|
| 회원가입 / 로그인 | JWT 기반 무상태 인증, 발급된 토큰으로 API 접근 |
| 소셜 로그인 | 카카오 · 네이버 · 구글 OAuth2 로그인 |
| 문서 업로드 | PDF, DOCX, TXT 업로드 (형식 검증, 최대 50MB) 및 자동 인덱싱 |
| 비동기 인덱싱 | 업로드 즉시 202 응답, 백그라운드에서 추출 → 청킹 → 임베딩 |
| 문서 관리 | 내 문서 목록, 인덱싱 상태(대기/인덱싱 중/완료/실패) 추적, 벡터 포함 삭제 |
| 멈춘 문서 복구 | 서버 재시작 등으로 인덱싱이 중단된 문서를 자동으로 실패 처리해 삭제·재업로드 가능 |
| AI 채팅 | 내 문서 기반으로 질문에 실시간 스트리밍(SSE) 답변 |
| 출처 표시 | 답변에 사용된 문서의 파일명 · 페이지 · 청크 순번 표시 |
| 대화 이력 | 대화별 질문·답변 저장, 이전 맥락을 이어가는 후속 질문, 새로고침 후 복원 |
| 비용·환각 방지 | 검색할 문서가 없으면 LLM을 호출하지 않고 안내 메시지 응답 |
| 멀티테넌트 | 문서 · 벡터 · 대화 모두 사용자별로 격리 (통합 테스트로 검증) |

---

## 🛠 기술 스택

### Backend
| 기술 | 버전 | 사용 이유 |
|---|---|---|
| Java | 21 | 최신 LTS (record, pattern matching 활용) |
| Spring Boot | 3.5 | 안정적인 프로덕션 환경 |
| Spring WebFlux | - | 논블로킹 I/O, SSE 스트리밍 |
| Spring AI | 1.1 | ChatClient · VectorStore 추상화 (OpenAI, PgVector) |
| Spring Security | - | JWT 기반 무상태 인증/인가, OAuth2 소셜 로그인 |
| Spring Data R2DBC | - | 리액티브 DB 접근 (사용자 · 문서 · 대화) |

### AI / Data
| 기술 | 사용 이유 |
|---|---|
| OpenAI GPT-4o | 답변 생성 LLM |
| OpenAI text-embedding-3-small | 문서 벡터화 (1536차원) |
| PostgreSQL + PgVector | 벡터 저장 및 유사도 검색 (HNSW, 코사인 거리) |
| Apache Tika / PDFBox | DOCX·TXT 텍스트 추출 / PDF 페이지 단위 추출 |

### Infra / Test
| 기술 | 사용 이유 |
|---|---|
| Docker Compose | 로컬 개발용 PostgreSQL(pgvector) 실행 |
| Testcontainers | 통합 테스트에서 실제 pgvector 컨테이너로 검증 |

---

## 🏗 시스템 아키텍처

### 문서 인덱싱 흐름 (업로드 시 1회)

```
사용자 파일 업로드
       │
       ▼
형식(PDF/DOCX/TXT)·크기(50MB) 검증 ── 실패 시 400 / 413
       │
       ▼
documents 테이블에 PENDING 저장 → [즉시 HTTP 202 반환]  ← 사용자는 기다리지 않음
       │
       ▼ (백그라운드 @Async, 상태: INDEXING)
텍스트 추출 (PDF는 PDFBox로 페이지별, 그 외는 Tika)
       │
       ▼
TokenTextSplitter 청킹 (페이지별, 500 토큰 단위)
       │
       ▼
OpenAI Embedding API (벡터 변환) ─┐  100개 청크 묶음 단위로 반복,
       │                          │  묶음마다 updated_at 갱신 (진행 신호)
       ▼                          │
PgVector 저장 (user_id · document_id · filename · page · chunk_index 메타데이터)
       │
       ▼
documents 상태 DONE(청크 수 기록) / 실패 시 FAILED(사유 기록)
```

### 멈춘 문서 복구 (1분 주기 + 서버 시작 직후)

인덱싱은 서버 메모리의 `@Async` 작업이라, 처리 중 서버가 재시작되면 문서가 `PENDING`/`INDEXING`에 영원히 남습니다.
(목록에 계속 "인덱싱 중"으로 보이고, 인덱싱 중 문서는 삭제가 막혀 사용자가 정리할 수 없음)

```
StaleDocumentRecoveryService (@Scheduled)
       │
       ▼
PENDING/INDEXING 이면서 updated_at이 10분 이상 갱신되지 않은 문서 → FAILED + "인덱싱이 중단되었습니다" 사유
```

- **진행 신호:** 정상 처리 중인 문서는 청크 묶음마다 `updated_at`이 갱신되므로, 큰 문서도 멈춘 문서로 오인되지 않음
- **시간 기준 판단:** "서버 시작 시 전부 실패 처리"가 아니라 시간 기준이라, 서버를 여러 대로 늘려도 다른 서버가 처리 중인 문서를 건드리지 않음
- **조건부 상태 변경:** 상태 변경은 모두 `UPDATE ... WHERE status = '현재 상태'`로 수행 → 실패 처리·삭제된 문서를 뒤늦게 끝난 인덱싱 작업이 DONE으로 덮어쓰지 않고, 이미 저장한 벡터는 삭제해 고아 데이터를 남기지 않음
- 설정: `app.indexing.stale-timeout`(기본 10m), `app.indexing.recovery-interval`(기본 PT1M)

### 질의응답 흐름 (질문마다)

```
사용자 질문 (+ 이어갈 conversationId)
       │
       ▼
대화 조회 또는 새 대화 생성 ──────────────▶ SSE event: conversation
       │
       ▼
같은 대화의 최근 메시지 10개 조회 (대화 맥락)
       │
       ▼
검색 질의 구성 (직전 질문 + 현재 질문 → 후속 질문도 관련 문서를 찾도록)
       │
       ▼
PgVector 유사도 검색 (user_id 필터 → 내 문서만, 상위 5개)
       │
       ├─ 0건 ─▶ LLM 호출 없이 안내 메시지 응답 (비용·환각 방지)
       │
       ▼
출처 목록 전송 ─────────────────────────▶ SSE event: sources
       │
       ▼
GPT-4o 프롬프트 = 시스템 지시 + 이전 대화 + (검색 문서 + 현재 질문)
       │
       ▼
토큰 단위 스트리밍 ──────────────────────▶ SSE event: token (N건)
       │
       ▼
질문·답변·출처를 chat_messages에 저장 (오류 시 SSE event: error)
```

---

## 📐 주요 설계 결정 (ADR)

### ADR-001. Spring MVC 대신 WebFlux 선택

**문제:** LLM 응답 생성까지 평균 3~5초 소요 → 동시 요청 증가 시 스레드 풀 고갈 위험

**결정:** Spring WebFlux(논블로킹) + SSE 스트리밍 채택

**결과:**
- 스레드 블로킹 없이 다수 동시 요청 처리 가능
- 첫 토큰 도달 시간(TTFT) 단축으로 체감 응답속도 개선
- 스트리밍 미적용 대비 체감 대기시간 약 80% 감소
- PgVector(JDBC)·임베딩 호출처럼 블로킹이 불가피한 작업은 `boundedElastic` 스케줄러 또는 `@Async` 스레드에서 실행해 이벤트 루프를 막지 않음

---

### ADR-002. Fine-tuning 대신 RAG 선택

**문제:** 사용자마다 다른 문서를 기반으로 답변해야 함

**결정:** Fine-tuning(LLM 재학습) 대신 RAG(검색 증강 생성) 채택

**이유:**
- Fine-tuning은 사용자별로 모델을 학습시켜야 해 비용/시간 비현실적
- RAG는 벡터 DB에 문서를 저장하고 질문 시 검색하는 방식 → 즉시 적용 가능
- 문서 추가/삭제가 자유롭고 출처 추적 가능

---

### ADR-003. 청킹 전략 — 고정 크기 500 토큰

**문제:** 문서를 어떤 단위로 쪼갤 것인가

**비교 검토:**

| 전략 | 장점 | 단점 |
|---|---|---|
| 고정 크기 (500 토큰) | 구현 단순, 일관된 크기 | 문단 중간에서 잘릴 수 있음 |
| 문단 기준 | 의미 단위 유지 | 문단 길이 편차 큼 |
| 시맨틱 청킹 | 의미 경계 정확 | 구현 복잡, 처리 비용 높음 |

**결정:** MVP 단계에서 고정 크기 500 토큰 채택, overlap 50 토큰으로 문맥 단절 최소화.  
PDF는 **페이지별로 따로 청킹**해 청크가 페이지 경계를 넘지 않도록 하여, 출처에 정확한 페이지 번호를 남김

---

### ADR-004. 멀티테넌트 격리 — user_id 메타데이터 필터링

**문제:** 다른 사용자의 문서가 검색 결과에 포함되면 안 됨

**결정:** 벡터 저장 시 `user_id`를 메타데이터로 포함, 검색 시 필터 조건으로 적용

```java
FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
SearchRequest.builder()
    .query(message)
    .filterExpression(filterBuilder.eq("user_id", userId).build())
    .build();
```

- 문서 삭제 시에도 `user_id` + `document_id`를 함께 걸어 다른 사용자의 벡터가 지워지지 않게 함
- `documents` · `conversations` · `chat_messages` 테이블도 모든 조회/삭제에 `user_id` 조건을 포함
- 다른 사용자의 리소스는 403이 아닌 **404**로 응답해 존재 여부도 노출하지 않음

**결과:** DB 레벨에서 사용자 데이터 완전 격리 보장 — 목록·삭제·채팅 검색·LLM 프롬프트·대화 이력·필터 우회 시도까지 통합 테스트로 검증 ([테스트](#-테스트) 참고)

---

### ADR-005. 인증 방식 — Session 대신 JWT

**문제:** 인증 상태를 어떻게 유지할 것인가

**결정:** 세션/쿠키 대신 무상태(stateless) JWT 채택

**이유:**
- WebFlux는 서버 인스턴스 확장을 전제로 하므로, 세션 동기화 부담이 없는 무상태 인증이 적합
- 로그인 시 발급한 JWT를 `Authorization: Bearer` 헤더로 전달받아 `JwtAuthenticationWebFilter`에서 매 요청마다 검증
- CSRF는 쿠키 기반 공격이므로 헤더 토큰 방식에서는 비활성화 가능 (`SecurityConfig`)
- 소셜 로그인 성공 시에도 같은 JWT를 발급해 프런트 콜백(`/auth/callback?token=`)으로 전달
- `/api/**` 미인증 요청은 로그인 페이지 리다이렉트(302) 대신 **401**을 반환해 프런트가 일관되게 처리

---

### ADR-006. 대화 이력 — 자체 테이블 + 최근 N개 메시지 주입

**문제:** "그건 왜 그래?" 같은 후속 질문을 이해하고, 새로고침 후에도 대화를 복원해야 함

**비교 검토:**

| 선택지 | 장점 | 단점 |
|---|---|---|
| Spring AI `ChatMemory` (JDBC 저장소) | 구현 간단 | 프레임워크 전용 테이블, 사용자별 대화 목록·출처 저장이 어려움, JDBC(블로킹) |
| **자체 테이블 (R2DBC)** | 대화 목록·제목·출처까지 저장, `user_id` 격리 규칙을 그대로 적용 | 직접 구현 필요 |

**결정:**
- `conversations` / `chat_messages` 테이블을 R2DBC로 직접 관리, 같은 대화의 **최근 메시지 10개(5턴)**를 프롬프트에 포함 (토큰 비용과 맥락 유지의 절충)
- 후속 질문은 단독으로 검색하면 관련 문서를 못 찾으므로 **직전 질문을 붙여 벡터 검색**  
  (LLM으로 질문을 재작성하는 방식은 질문마다 호출이 1회 늘어나 채택하지 않음 → 향후 개선 계획)
- 스트리밍 도중 연결이 끊기면 받은 데까지의 답변을 "(답변이 중단되었습니다)" 표시와 함께 저장

---

## 🗄 데이터 모델

테이블은 앱 시작 시 자동 생성됩니다. (`schema.sql` + Spring AI PgVector `initialize-schema`)

| 테이블 | 생성 | 용도 | 주요 컬럼 |
|---|---|---|---|
| `users` | schema.sql | 사용자 | `username`(격리 기준 ID), `password`(BCrypt, 소셜은 NULL), `provider`, `provider_id` |
| `documents` | schema.sql | 업로드 문서 · 인덱싱 상태 | `user_id`, `filename`, `status`, `chunk_count`, `error_message` |
| `vector_store` | Spring AI | 문서 청크 + 임베딩 | `content`, `metadata`(JSON), `embedding vector(1536)` |
| `conversations` | schema.sql | 대화방 | `user_id`, `title`(첫 질문 30자), `updated_at` |
| `chat_messages` | schema.sql | 질문 · 답변 | `conversation_id`(FK, CASCADE), `user_id`, `role`, `content`, `sources`(JSON) |

```
users.username ──(1:N)── documents.user_id
                             │
documents.id   ──(1:N)── vector_store.metadata->>'document_id'
users.username ──(1:N)── vector_store.metadata->>'user_id'
users.username ──(1:N)── conversations.user_id
conversations.id ─(1:N)─ chat_messages.conversation_id   (ON DELETE CASCADE)
```

- 원본 파일은 저장하지 않고, 추출한 텍스트 청크와 임베딩만 저장
- `vector_store` 연결 키는 JSON 메타데이터라 FK를 걸 수 없으므로, 문서 삭제 시 애플리케이션이 벡터 → 문서 순으로 삭제

---

## 📁 프로젝트 구조

```
src/main/java/com/myassistant/
├── auth/
│   ├── AuthController.java       # 회원가입/로그인 API
│   ├── AuthService.java          # 인증 비즈니스 로직, JWT 발급
│   ├── GlobalExceptionHandler.java # 전역 예외 처리 (400/404/409/413/500)
│   └── dto/                      # LoginRequest, RegisterRequest, AuthResponse
├── oauth2/
│   ├── CustomOAuth2UserService.java # 소셜 사용자 조회/가입
│   ├── OAuth2SuccessHandler.java # 로그인 성공 시 JWT 발급 후 프런트로 리다이렉트
│   ├── OAuthAttributes.java      # 카카오/네이버/구글 응답 정규화
│   └── CustomOAuth2User.java
├── security/
│   ├── JwtProvider.java          # JWT 생성/검증
│   ├── JwtAuthenticationWebFilter.java # 요청별 JWT 인증 필터
│   └── UserDetailsServiceImpl.java # ReactiveUserDetailsService 구현
├── user/
│   ├── User.java                 # 사용자 엔티티
│   └── UserRepository.java       # R2DBC 사용자 레포지토리
├── document/
│   ├── DocumentController.java   # 문서 업로드/목록/삭제 API
│   ├── DocumentService.java      # 문서 관리 (업로드 접수, 목록, 벡터 포함 삭제)
│   ├── DocumentIndexingService.java # 비동기 인덱싱 처리 + 상태 기록 (조건부 UPDATE, 진행 신호)
│   ├── StaleDocumentRecoveryService.java # 멈춘 PENDING/INDEXING 문서 주기적 실패 처리
│   ├── DocumentParser.java       # Tika/PDFBox 기반 텍스트 추출
│   ├── DocumentUploadValidator.java # 업로드 형식·크기 검증
│   ├── UploadedDocument.java     # 업로드 문서 엔티티 (인덱싱 상태 포함)
│   ├── UploadedDocumentRepository.java # R2DBC 문서 레포지토리
│   ├── DocumentStatus.java       # PENDING/INDEXING/DONE/FAILED
│   ├── *Exception.java           # 문서 없음(404) · 인덱싱 중(409) · 크기 초과(413)
│   └── dto/                      # DocumentResponse, UploadResponse
├── chat/
│   ├── ChatController.java       # SSE 스트리밍 API
│   ├── ChatService.java          # RAG 기반 답변 생성 (대화 맥락 + 출처 + 이력 저장)
│   └── dto/SourceInfo.java       # 출처 정보 (파일명/페이지/청크)
├── conversation/
│   ├── ConversationController.java # 대화 목록/메시지/삭제 API
│   ├── ConversationService.java  # 대화 생성·조회·삭제, 메시지 저장
│   ├── Conversation.java / ChatMessage.java # 대화방 · 메시지 엔티티
│   ├── ConversationRepository.java / ChatMessageRepository.java # R2DBC 레포지토리
│   ├── MessageRole.java          # USER / ASSISTANT
│   ├── ConversationNotFoundException.java # 대화 없음(404)
│   └── dto/                      # ConversationResponse, ChatMessageResponse
└── config/
    ├── SecurityConfig.java       # Spring Security + JWT 필터 체인, CORS, 401 진입점
    ├── ChatClientConfig.java     # Spring AI ChatClient (시스템 프롬프트)
    └── JdbcDataSourceConfig.java # PgVector용 JDBC DataSource (R2DBC와 병행)

src/test/java/com/myassistant/
├── support/                      # IntegrationTestSupport, FakeEmbeddingModel, RecordingChatModel
├── isolation/TenantIsolationIntegrationTest.java # 문서·검색 멀티테넌트 격리
├── conversation/ConversationIntegrationTest.java # 대화 이력 · 멀티턴 · 격리
└── document/StaleDocumentRecoveryIntegrationTest.java # 멈춘 문서 복구
```

---

## 🚀 로컬 실행 방법

### 사전 준비

- Java 21
- Docker Desktop
- OpenAI API Key

### 실행 순서

```bash
# 1. 레포지토리 클론
git clone https://github.com/GaEunJJ/my-assistant.git
cd my-assistant

# 2. 환경변수 설정 (Docker 실행 전에 먼저 해야 함)
cp .env.example .env
# .env 파일을 열어 OPENAI_API_KEY, DB_PASSWORD, JWT_SECRET, 소셜 로그인 키 값을 채워넣기
# JWT_SECRET은 다음 명령으로 생성: openssl rand -base64 32

# 3. PostgreSQL 실행 (Docker Desktop이 켜져 있어야 함)
# docker-compose.yml이 .env의 DB_PASSWORD를 읽으므로, 2번을 건너뛰면 컨테이너가 시작되지 않음
docker compose up -d

# 4. 애플리케이션 실행
# 시작 시 테이블 자동 생성: users/documents/conversations/chat_messages(schema.sql), vector_store(Spring AI PgVector)
./gradlew bootRun
```

> 이미 빈 비밀번호로 컨테이너를 띄웠다면 `docker compose down -v`로 볼륨까지 지운 뒤 다시 실행하세요. (**DB 데이터가 모두 삭제됩니다**)
> PostgreSQL은 **처음 초기화될 때만** 비밀번호를 설정하기 때문에, `.env`만 고쳐서는 반영되지 않습니다.

### 환경변수 목록

| 변수명 | 설명 | 필수 |
|---|---|---|
| `OPENAI_API_KEY` | OpenAI API 키 | ✅ |
| `DB_PASSWORD` | PostgreSQL 비밀번호 | ✅ |
| `JWT_SECRET` | JWT 서명용 Base64 인코딩 256비트 이상 키 | ✅ |
| `KAKAO_CLIENT_ID` / `KAKAO_CLIENT_SECRET` | 카카오 OAuth2 앱 키 | ✅ (기본값 없음) |
| `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` | 네이버 OAuth2 앱 키 | ✅ (기본값 없음) |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | 구글 OAuth2 앱 키 | ✅ (기본값 없음) |
| `DB_USERNAME` | DB 사용자명 (기본값: myassistant) | ❌ |
| `DB_R2DBC_URL` | R2DBC 연결 URL (기본값: r2dbc:postgresql://localhost:5432/myassistant) | ❌ |
| `DB_JDBC_URL` | JDBC 연결 URL — PgVector 내부용 (기본값: jdbc:postgresql://localhost:5432/myassistant) | ❌ |
| `CORS_ALLOWED_ORIGINS` | 프런트 도메인, 쉼표로 여러 개 (기본값: http://localhost:3000) | ❌ |
| `OAUTH2_REDIRECT_URI` | 소셜 로그인 후 JWT를 전달할 프런트 콜백 (기본값: http://localhost:3000/auth/callback) | ❌ |

> 소셜 로그인 키는 기본값이 없어 비어 있으면 앱이 시작되지 않습니다. 소셜 로그인을 쓰지 않는다면 임의의 값(예: `unused`)을 넣어도 일반 로그인은 동작합니다.

---

## 🧪 테스트

```bash
# Docker Desktop이 켜져 있어야 함 (Testcontainers가 pgvector 컨테이너를 자동으로 띄움)
./gradlew test
```

- **실제 DB로 검증:** Testcontainers로 운영과 같은 `pgvector/pgvector:pg16`을 띄워, PgVector의 metadata 필터가 실제로 동작하는지 확인
- **외부 API 호출 없음:** OpenAI 대신 단어 해시 기반 `FakeEmbeddingModel`과 프롬프트를 기록하는 `RecordingChatModel`을 사용 → API 키·비용 없이 반복 실행 가능
- **HTTP API 전 구간 검증:** 회원가입 → 업로드 → 비동기 인덱싱 완료 대기 → 목록/삭제/채팅/대화 이력을 WebTestClient로 호출
- 총 **22개** 테스트 (격리 8 · 대화 이력 10 · 멈춘 문서 복구 3 · 컨텍스트 로딩 1)

### 멀티테넌트 격리 테스트 (`TenantIsolationIntegrationTest`)

| 구분 | 검증 내용 |
|---|---|
| 문서 목록 | 다른 사용자의 문서는 목록에 보이지 않는다 |
| 문서 삭제 | 다른 사용자의 문서는 삭제할 수 없다 (404, 문서·벡터 유지) |
| 문서 삭제 | 내 문서를 삭제해도 같은 내용의 다른 사용자 벡터는 남는다 |
| 채팅 검색 | 질문과 일치하는 문서를 서로 갖고 있어도 검색 결과·출처·**LLM 프롬프트**에 본인 문서만 포함된다 |
| 채팅 검색 | 내 문서가 없으면 다른 사용자 문서가 있어도 검색되지 않고 LLM을 호출하지 않는다 |
| 필터 우회 시도 | `a" \|\| $.user_id != "`, `o'neil' OR '1'='1` 등 따옴표·연산자가 포함된 아이디로 필터 식을 조작해 다른 사용자 문서를 검색할 수 없다 |

### 대화 이력 테스트 (`ConversationIntegrationTest`)

| 구분 | 검증 내용 |
|---|---|
| 대화 생성 | 대화 ID 없이 질문하면 새 대화가 생성되고 첫 질문이 제목이 된다 |
| 멀티턴 | 같은 대화의 이전 질문과 답변이 다음 LLM 프롬프트에 포함된다 |
| 멀티턴 | 새 대화에는 다른 대화의 맥락이 섞이지 않는다 |
| 후속 질문 검색 | "그건 몇 일이야?"처럼 단독으로는 검색되지 않는 질문도 직전 질문과 함께 검색되어 관련 문서를 찾는다 |
| 저장·복원 | 질문·답변이 시간순으로 저장되고 답변의 출처도 복원된다 |
| 저장·복원 | 문서가 없을 때의 안내 답변도 대화에 저장된다 |
| 삭제 | 대화를 삭제하면 메시지까지 함께 삭제된다 |
| 검증 | 빈 질문은 400으로 거절되고 대화가 생성되지 않는다 |
| 격리 | 다른 사용자의 대화는 목록에 보이지 않고 조회·삭제할 수 없다 (404) |
| 격리 | 다른 사용자의 대화 ID로 질문을 이어갈 수 없다 (404, 메시지 추가·맥락 유출 없음) |

### 멈춘 문서 복구 테스트 (`StaleDocumentRecoveryIntegrationTest`)

| 검증 내용 |
|---|
| 오래 멈춘 PENDING·INDEXING 문서는 FAILED로 바뀌고 사유가 기록되며, 삭제할 수 있다 |
| 최근 진행 중인 문서와 이미 끝난 문서는 건드리지 않는다 |
| 실패 처리된 문서는 뒤늦게 실행된 인덱싱 작업이 DONE으로 덮어쓰지 않고 벡터도 저장되지 않는다 |

> 테스트가 실제로 결함을 잡아내는지 확인하기 위해 코드를 일부러 망가뜨려 실행해 보았습니다.  
> `ChatService`의 `user_id` 필터를 제거하면 격리 테스트 5건이, 후속 질문에 직전 질문을 붙이지 않으면 대화 이력 테스트 3건이 실패합니다.

---

## 📡 API 명세

> `/api/auth/**`, 소셜 로그인 경로를 제외한 모든 API는 `Authorization: Bearer {JWT}` 헤더가 필요합니다.  
> 토큰이 없거나 유효하지 않으면 `401 Unauthorized`를 반환합니다. 오류 응답 본문은 `{"message": "..."}` 형식입니다.

### 회원가입
```
POST /api/auth/register
Content-Type: application/json
{"username": "user1", "password": "password123"}    # 아이디 4~20자, 비밀번호 8자 이상

Response: 201 Created
{"accessToken": "eyJhbGciOiJIUzI1NiJ9..."}
```

### 로그인
```
POST /api/auth/login
Content-Type: application/json
{"username": "user1", "password": "password123"}

Response: 200 OK
{"accessToken": "eyJhbGciOiJIUzI1NiJ9..."}
```

### 소셜 로그인
```
GET /oauth2/authorization/{kakao|naver|google}    # 브라우저로 이동

→ 로그인 성공 시 302 {OAUTH2_REDIRECT_URI}?token={JWT}
```

### 문서 업로드
```
POST /api/documents/upload
Content-Type: multipart/form-data   (file 파트)

Response: 202 Accepted
{
  "message": "인덱싱을 시작했어요. 완료되면 채팅에서 사용할 수 있습니다.",
  "document": {"id": 12, "filename": "계약서.pdf", "status": "PENDING", "chunkCount": null, "errorMessage": null, "createdAt": "2026-11-15T10:00:00+09:00"}
}

Response: 400 Bad Request         # 빈 파일, 또는 PDF/DOCX/TXT 이외 형식
Response: 413 Payload Too Large   # 50MB 초과 (app.upload.max-file-size)
```

### 내 문서 목록
```
GET /api/documents

Response: 200 OK
# status: PENDING(대기) → INDEXING(인덱싱 중) → DONE(완료) / FAILED(실패, errorMessage에 사유)
[
  {"id": 12, "filename": "계약서.pdf", "status": "DONE", "chunkCount": 34, "errorMessage": null, "createdAt": "2026-11-15T10:00:00+09:00"},
  {"id": 11, "filename": "스캔본.pdf", "status": "FAILED", "chunkCount": null, "errorMessage": "문서에서 추출된 텍스트가 없습니다. (스캔 이미지 문서는 지원하지 않습니다)", "createdAt": "2026-11-14T18:20:00+09:00"}
]
```

### 문서 삭제
```
DELETE /api/documents/{id}

Response: 204 No Content     # 문서와 해당 문서의 벡터 청크를 함께 삭제
Response: 404 Not Found      # 문서가 없거나 다른 사용자의 문서
Response: 409 Conflict       # 인덱싱 진행 중 (완료/실패 후 삭제 가능)
```

### AI 채팅 (SSE 스트리밍)
```
GET /api/chat/stream?message={질문}&conversationId={대화 ID, 생략 시 새 대화}
Accept: text/event-stream

Response: 200 OK
# 1) 답변이 저장되는 대화 정보 (새 대화면 서버가 생성한 ID)
event: conversation
data: {"conversationId": 7, "title": "휴가 규정 알려줘"}

# 2) 답변에 사용된 출처 — page는 PDF만 채워지고 DOCX/TXT는 null
#    검색된 문서가 없으면 [] 와 함께 안내 메시지 token 1건만 보내고 LLM은 호출하지 않음
event: sources
data: [{"filename":"계약서.pdf","page":3,"chunkIndex":1},{"filename":"회의록.docx","page":null,"chunkIndex":0}]

# 3) 답변을 토큰 단위로 스트리밍
event: token
data: 안녕

event: token
data: 하세요...

# (LLM 호출 실패 시) 연결을 끊지 않고 안내 이벤트 전송
event: error
data: 답변을 생성하는 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.

Response: 400 Bad Request    # 빈 질문 또는 2000자 초과
Response: 404 Not Found      # 대화가 없거나 다른 사용자의 대화
# 오류 본문은 Accept 헤더에 따라 SSE 형식(data:{"message":"..."})으로 전달됨
```

### 대화 목록
```
GET /api/conversations

Response: 200 OK    # 최근 활동 순
[{"id": 7, "title": "휴가 규정 알려줘", "createdAt": "2026-11-15T10:00:00+09:00", "updatedAt": "2026-11-15T10:05:00+09:00"}]
```

### 대화 메시지
```
GET /api/conversations/{id}/messages

Response: 200 OK    # 시간순, 답변(ASSISTANT)에는 출처 포함
[
  {"id": 1, "role": "USER", "content": "휴가 규정 알려줘", "sources": [], "createdAt": "..."},
  {"id": 2, "role": "ASSISTANT", "content": "연차는 15일입니다...", "sources": [{"filename":"규정.pdf","page":2,"chunkIndex":3}], "createdAt": "..."}
]
Response: 404 Not Found      # 대화가 없거나 다른 사용자의 대화
```

### 대화 삭제
```
DELETE /api/conversations/{id}

Response: 204 No Content     # 대화와 메시지 모두 삭제
Response: 404 Not Found      # 대화가 없거나 다른 사용자의 대화
```

---

## 📊 성능 개선 지표

| 항목 | 개선 전 | 개선 후 |
|---|---|---|
| 체감 응답 대기시간 | 응답 완료까지 대기 (평균 4.2초) | 첫 토큰 0.8초 내 수신 (SSE 적용) |
| 대용량 문서 업로드 | 인덱싱 완료까지 블로킹 | 즉시 202 반환, 백그라운드 처리 |
| 토큰 사용량 | 문서 전체 LLM 전달 | RAG로 관련 청크만 전달 (약 82% 절감) |

---

## 🔮 향후 개선 계획

- [ ] 인덱싱 완료 실시간 알림 (현재는 프런트가 3초 간격 polling → SSE/WebSocket 푸시)
- [x] 서버 재시작 시 PENDING/INDEXING 상태로 멈춘 문서 복구 (FAILED 전환)
- [ ] 멈춘 문서 자동 재처리 (현재는 원본 파일을 저장하지 않아 사용자가 다시 업로드해야 함)
- [ ] 후속 질문을 LLM으로 독립 질문으로 재작성해 검색 정확도 향상 (Query Rewriting)
- [ ] 긴 대화 요약 저장으로 오래된 맥락 유지 (현재는 최근 10개 메시지만 사용)
- [ ] 청킹 전략 개선 (시맨틱 청킹 적용)
- [ ] 무료/유료 티어 분리 (문서 용량 제한)
- [ ] Ollama 연동으로 로컬 LLM 지원 (비용 절감)
- [ ] 재인덱싱 파이프라인 (임베딩 모델 교체 대응)
- [ ] GitHub Actions CI (테스트 자동 실행)
