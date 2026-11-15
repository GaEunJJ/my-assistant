# 🏗 아키텍처

> [← README로 돌아가기](../README.md) · [아키텍처](ARCHITECTURE.md) · [설계 결정](ADR.md) · [API](API.md) · [테스트](TESTING.md) · [성능](PERFORMANCE.md)

## 목차

- [문서 인덱싱 흐름](#문서-인덱싱-흐름-업로드-시-1회)
- [멈춘 문서 복구](#멈춘-문서-복구-1분-주기--서버-시작-직후)
- [질의응답 흐름](#질의응답-흐름-질문마다)
- [데이터 모델](#데이터-모델)
- [프로젝트 구조](#프로젝트-구조)
- [기술 스택 상세](#기술-스택-상세)


## 문서 인덱싱 흐름 (업로드 시 1회)

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

## 멈춘 문서 복구 (1분 주기 + 서버 시작 직후)

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

## 질의응답 흐름 (질문마다)

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

## 데이터 모델
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

## 프로젝트 구조
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

scripts/perf/
├── measure-performance.mjs       # 업로드·인덱싱 시간, 첫 토큰·완료 시간 측정 (Node)
└── CountTokens.java              # GPT-4o 토크나이저로 문서 전체 vs RAG 전달 토큰 계산
```


---

## 기술 스택 상세

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

