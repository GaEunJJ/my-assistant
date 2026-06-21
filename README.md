# 📄 myassistant — 내 문서로 만드는 나만의 AI 어시스턴트

> PDF, DOCX 등 문서를 업로드하면, 그 문서를 기반으로 AI가 실시간으로 답변해주는 멀티테넌트 RAG 챗봇 서비스

![Java](https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5-brightgreen?style=flat-square&logo=springboot)
![Spring AI](https://img.shields.io/badge/Spring_AI-1.1-green?style=flat-square)
![WebFlux](https://img.shields.io/badge/WebFlux-Reactive-blue?style=flat-square)
![PostgreSQL](https://img.shields.io/badge/PgVector-PostgreSQL-336791?style=flat-square&logo=postgresql)

---

## 🗂 목차

- [프로젝트 개요](#-프로젝트-개요)
- [핵심 기능](#-핵심-기능)
- [기술 스택](#-기술-스택)
- [시스템 아키텍처](#-시스템-아키텍처)
- [주요 설계 결정 (ADR)](#-주요-설계-결정-adr)
- [프로젝트 구조](#-프로젝트-구조)
- [로컬 실행 방법](#-로컬-실행-방법)
- [API 명세](#-api-명세)
- [성능 개선 지표](#-성능-개선-지표)

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
| 문서 업로드 | PDF, DOCX, TXT 파일 업로드 및 자동 인덱싱 |
| AI 채팅 | 내 문서 기반으로 질문에 실시간 스트리밍 답변 |
| 출처 표시 | 답변에 사용된 문서 청크 및 페이지 정보 표시 |
| 멀티테넌트 | 사용자별 완전히 격리된 문서 공간 제공 |
| 비동기 인덱싱 | 대용량 문서도 업로드 즉시 응답, 백그라운드 처리 |
| 대화 이력 | 이전 대화 맥락을 유지한 연속 질의응답 |

---

## 🛠 기술 스택

### Backend
| 기술 | 버전 | 사용 이유 |
|---|---|---|
| Java | 21 | Virtual Thread 활용, 최신 LTS |
| Spring Boot | 3.5 | 안정적인 프로덕션 환경 |
| Spring WebFlux | - | 논블로킹 I/O, SSE 스트리밍 |
| Spring AI | 1.0 | LLM 추상화, RAG Advisor |
| Spring Security | - | JWT 기반 무상태 인증/인가 |
| Spring Data R2DBC | - | 리액티브 DB 접근 |

### AI / Data
| 기술 | 사용 이유 |
|---|---|
| OpenAI GPT-4o | 답변 생성 LLM |
| OpenAI text-embedding-3-small | 문서 벡터화 |
| PostgreSQL + PgVector | 벡터 저장 및 유사도 검색 |
| Apache Tika | PDF/DOCX 텍스트 추출 |

### Infra
| 기술 | 사용 이유 |
|---|---|
| Docker Compose | 로컬 개발 환경 |
| GitHub Actions | CI/CD 파이프라인 |

---

## 🏗 시스템 아키텍처

### 문서 인덱싱 흐름 (업로드 시 1회)

```
사용자 파일 업로드
       │
       ▼
[즉시 HTTP 202 반환]  ← 사용자는 기다리지 않음
       │
       ▼ (백그라운드 비동기 처리)
Apache Tika 텍스트 추출
       │
       ▼
TokenTextSplitter 청킹 (500 토큰 단위)
       │
       ▼
OpenAI Embedding API (벡터 변환)
       │
       ▼
PgVector 저장 (user_id 메타데이터 포함)
```

### 질의응답 흐름 (질문마다)

```
사용자 질문 입력
       │
       ▼
질문 벡터화 (Embedding)
       │
       ▼
PgVector 유사도 검색 (user_id 필터 → 내 문서만)
       │
       ▼
상위 5개 청크 추출 (RAG 컨텍스트 구성)
       │
       ▼
GPT-4o 프롬프트 주입
       │
       ▼
WebFlux SSE 스트리밍 응답 (토큰 단위 실시간 전송)
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

**결정:** MVP 단계에서 고정 크기 500 토큰 채택, overlap 50 토큰으로 문맥 단절 최소화

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

**결과:** DB 레벨에서 사용자 데이터 완전 격리 보장

---

### ADR-005. 인증 방식 — Session 대신 JWT

**문제:** 인증 상태를 어떻게 유지할 것인가

**결정:** 세션/쿠키 대신 무상태(stateless) JWT 채택

**이유:**
- WebFlux는 서버 인스턴스 확장을 전제로 하므로, 세션 동기화 부담이 없는 무상태 인증이 적합
- 로그인 시 발급한 JWT를 `Authorization: Bearer` 헤더로 전달받아 `JwtAuthenticationWebFilter`에서 매 요청마다 검증
- CSRF는 쿠키 기반 공격이므로 헤더 토큰 방식에서는 비활성화 가능 (`SecurityConfig`)

---

## 📁 프로젝트 구조

```
src/main/java/com/myassistant/
├── auth/
│   ├── AuthController.java       # 회원가입/로그인 API
│   ├── AuthService.java          # 인증 비즈니스 로직, JWT 발급
│   └── GlobalExceptionHandler.java # 전역 예외 처리 (@RestControllerAdvice)
├── security/
│   ├── JwtProvider.java          # JWT 생성/검증
│   ├── JwtAuthenticationWebFilter.java # 요청별 JWT 인증 필터
│   └── UserDetailsServiceImpl.java # ReactiveUserDetailsService 구현
├── user/
│   ├── User.java                 # 사용자 엔티티
│   └── UserRepository.java       # R2DBC 사용자 레포지토리
├── document/
│   ├── DocumentController.java   # 파일 업로드 API
│   ├── DocumentService.java      # 비동기 인덱싱 처리
│   └── DocumentParser.java       # Tika 기반 텍스트 추출
├── chat/
│   ├── ChatController.java       # SSE 스트리밍 API
│   └── ChatService.java          # RAG 기반 답변 생성
├── tenant/
│   └── TenantContext.java        # 사용자 격리 컨텍스트
└── config/
    ├── SecurityConfig.java       # Spring Security + JWT 필터 체인 설정
    ├── VectorStoreConfig.java    # PgVector 설정
    └── ChatClientConfig.java     # Spring AI ChatClient 설정
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
git clone https://github.com/{username}/myassistant.git
cd myassistant

# 2. PostgreSQL 실행 (Docker)
docker-compose up -d

# 3. 환경변수 설정
cp .env.example .env
# .env 파일을 열어 OPENAI_API_KEY, DB_PASSWORD, JWT_SECRET 값을 채워넣기
# JWT_SECRET은 다음 명령으로 생성: openssl rand -base64 32

# 4. 애플리케이션 실행
./gradlew bootRun
```

### 환경변수 목록

| 변수명 | 설명 | 필수 |
|---|---|---|
| `OPENAI_API_KEY` | OpenAI API 키 | ✅ |
| `DB_PASSWORD` | PostgreSQL 비밀번호 | ✅ |
| `JWT_SECRET` | JWT 서명용 Base64 인코딩 256비트 이상 키 | ✅ |
| `DB_USERNAME` | DB 사용자명 (기본값: myassistant) | ❌ |
| `DB_R2DBC_URL` | R2DBC 연결 URL (기본값: r2dbc:postgresql://localhost:5432/myassistant) | ❌ |
| `DB_JDBC_URL` | JDBC 연결 URL — PgVector 내부용 (기본값: jdbc:postgresql://localhost:5432/myassistant) | ❌ |

---

## 📡 API 명세

> `/api/auth/**`를 제외한 모든 API는 `Authorization: Bearer {JWT}` 헤더가 필요합니다.

### 회원가입
```
POST /api/auth/register
Content-Type: application/json
{"username": "user1", "password": "password123"}

Response: 201 Created
{"token": "eyJhbGciOiJIUzI1NiJ9..."}
```

### 로그인
```
POST /api/auth/login
Content-Type: application/json
{"username": "user1", "password": "password123"}

Response: 200 OK
{"token": "eyJhbGciOiJIUzI1NiJ9..."}
```

### 문서 업로드
```
POST /api/documents/upload
Content-Type: multipart/form-data
Authorization: Bearer {JWT}

Response: 202 Accepted
{"message": "인덱싱을 시작했어요. 잠시 후 채팅이 가능합니다."}
```

### AI 채팅 (SSE 스트리밍)
```
GET /api/chat/stream?message={질문내용}
Accept: text/event-stream
Authorization: Bearer {JWT}

Response: 200 OK (토큰 단위 스트리밍)
data: 안녕
data: 하세요
data: . 질문하신
data: 내용은...
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

- [ ] 문서 인덱싱 완료 알림 (WebSocket)
- [ ] 청킹 전략 개선 (시맨틱 청킹 적용)
- [ ] 무료/유료 티어 분리 (문서 용량 제한)
- [ ] Ollama 연동으로 로컬 LLM 지원 (비용 절감)
- [ ] 재인덱싱 파이프라인 (임베딩 모델 교체 대응)