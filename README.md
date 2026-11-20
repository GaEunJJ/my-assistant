# 📄 myassistant — 내 문서로 만드는 나만의 AI 어시스턴트

> PDF, DOCX 등 문서를 업로드하면, 그 문서를 기반으로 AI가 실시간으로 답변해주는 멀티테넌트 RAG 챗봇 서비스

[![CI](https://github.com/GaEunJJ/my-assistant/actions/workflows/ci.yml/badge.svg)](https://github.com/GaEunJJ/my-assistant/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5-brightgreen?style=flat-square&logo=springboot)
![Spring AI](https://img.shields.io/badge/Spring_AI-1.1-green?style=flat-square)
![WebFlux](https://img.shields.io/badge/WebFlux-Reactive-blue?style=flat-square)
![PostgreSQL](https://img.shields.io/badge/PgVector-PostgreSQL-336791?style=flat-square&logo=postgresql)

> 프런트엔드 저장소: [myassistant-frontend](https://github.com/GaEunJJ/myassistant-frontend) (Next.js 16 / React 19)

### 📚 상세 문서

| 문서 | 내용 |
|---|---|
| [🏗 아키텍처](docs/ARCHITECTURE.md) | 인덱싱·질의응답 흐름도, 멈춘 문서 복구, 데이터 모델, 프로젝트 구조, 기술 선택 이유 |
| [📐 설계 결정 (ADR)](docs/ADR.md) | 기술 선택의 문제·대안·결정 6건 |
| [📡 API 명세](docs/API.md) | 전체 엔드포인트 요청/응답, SSE 이벤트 형식 |
| [🧪 테스트](docs/TESTING.md) | 통합 테스트 22건 상세 |
| [📊 성능 지표](docs/PERFORMANCE.md) | 응답 시간·업로드·토큰 실측값과 측정 방법 |

---

## 💡 프로젝트 개요

사용자가 업로드한 문서(PDF, DOCX, TXT)를 기반으로 AI와 대화하는 **멀티테넌트 RAG(Retrieval-Augmented Generation) 챗봇 서비스**입니다.

일반 챗봇은 LLM이 학습한 지식만 사용해 다음 문제가 있습니다. myassistant는 **사용자 본인의 문서**를 근거로 답변해 이를 해결합니다.

- 내부 문서나 개인 자료에 대한 질문에 정확한 답변 불가능
- 답변 근거를 확인할 수 없어 신뢰도 부족
- LLM 응답 지연으로 인한 사용자 경험 저하

---

## ✨ 핵심 기능

| 기능 | 설명 |
|---|---|
| 회원가입 / 로그인 | JWT 기반 무상태 인증, 카카오 · 네이버 · 구글 소셜 로그인 |
| 문서 업로드 · 관리 | PDF/DOCX/TXT(최대 50MB) 업로드, 인덱싱 상태 추적(대기/인덱싱 중/완료/실패), 벡터 포함 삭제 |
| 비동기 인덱싱 | 업로드 즉시 202 응답, 백그라운드에서 추출 → 청킹 → 임베딩. 서버 재시작으로 멈춘 문서는 자동 실패 처리 |
| AI 채팅 | 내 문서 기반 실시간 스트리밍(SSE) 답변, 파일명 · 페이지 · 청크 출처 표시 |
| 대화 이력 | 대화별 저장 · 복원, 이전 맥락을 이어가는 후속 질문 |
| 비용 · 환각 방지 | 검색할 문서가 없으면 LLM을 호출하지 않고 안내 메시지 응답 |
| 멀티테넌트 격리 | 문서 · 벡터 · 대화 모두 사용자별 격리, 통합 테스트로 검증 |

---

## 🛠 기술 스택

| 분류 | 기술 |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Spring WebFlux, Spring Security (JWT · OAuth2), Spring Data R2DBC |
| AI | Spring AI 1.1, OpenAI GPT-4o, text-embedding-3-small |
| Data | PostgreSQL + PgVector (HNSW, 코사인 거리), Apache Tika · PDFBox (텍스트 추출) |
| Infra · Test | Docker Compose, Testcontainers |

---

## 🏗 아키텍처 요약

```
[업로드]  검증(형식·50MB) → documents(PENDING) 저장 → 즉시 202
                                  │ @Async
                                  ▼
          텍스트 추출 → 페이지별 500토큰 청킹 → 임베딩 → PgVector 저장(user_id·document_id 메타데이터) → DONE / FAILED

[질문]    대화 조회/생성 ─▶ SSE conversation
            → 최근 메시지 10개 + (직전 질문 + 현재 질문)으로 벡터 검색 (user_id 필터, 상위 5개)
            → 0건이면 LLM 호출 없이 안내
            → 출처 ─▶ SSE sources
            → GPT-4o(이전 대화 + 검색 문서 + 질문) 토큰 스트리밍 ─▶ SSE token
            → 질문·답변·출처 저장
```

블로킹이 불가피한 작업(PgVector JDBC, 임베딩 호출)은 `boundedElastic` 스케줄러나 `@Async` 스레드에서 실행해 WebFlux 이벤트 루프를 막지 않습니다.  
→ 상세 흐름도, 데이터 모델(테이블 5개), 프로젝트 구조는 [아키텍처 문서](docs/ARCHITECTURE.md) 참고

---

## 📐 핵심 설계 결정

| # | 결정 | 이유 |
|---|---|---|
| 001 | Spring MVC 대신 **WebFlux + SSE** | LLM 응답 대기 중 스레드 고갈 방지, 첫 토큰부터 화면에 표시 |
| 002 | Fine-tuning 대신 **RAG** | 사용자별 문서에 즉시 대응, 문서 추가·삭제 자유, 출처 추적 가능 |
| 003 | **고정 500토큰 청킹** (PDF는 페이지별) | MVP에 적합한 단순함, 출처에 정확한 페이지 번호 유지 |
| 004 | **user_id 메타데이터 필터**로 격리 | 모든 검색·삭제·조회에 user_id 조건, 타인 리소스는 404로 존재도 숨김 |
| 005 | 세션 대신 **JWT** | 서버 확장 시 세션 동기화 불필요, `/api/**` 미인증은 401로 일관 처리 |
| 006 | 대화 이력 **자체 테이블(R2DBC)** | 대화 목록·출처 저장과 격리 규칙 적용, 최근 10개 메시지로 비용과 맥락 절충 |

→ 각 결정의 문제 정의 · 비교 검토 · 결과는 [ADR 문서](docs/ADR.md) 참고

---

## 📊 성능 지표 (로컬 실측)

| 항목 | 결과 |
|---|---|
| SSE 스트리밍 | 첫 토큰 평균 **1.59초** vs 답변 완료 2.63초 → 대기시간 **39% 단축** (긴 답변은 최대 62%) |
| 비동기 인덱싱 | 업로드 응답 **0.17초** (인덱싱 완료까지는 3.2초 — 사용자는 기다리지 않음) |
| RAG 토큰 절감 | 문서 8,171토큰 중 질문당 평균 1,804토큰만 전달 → **약 78% 절감** (문서가 클수록 증가) |

→ 측정 환경 · 방법 · 질문별 수치 · 한계는 [성능 문서](docs/PERFORMANCE.md) 참고 (재현 스크립트: [`scripts/perf/`](scripts/perf))

---

## 🧪 테스트

```bash
./gradlew test   # Docker Desktop 필요 (Testcontainers)
```

- **22개 통합 테스트**: 멀티테넌트 격리 8 · 대화 이력 10 · 멈춘 문서 복구 3 · 컨텍스트 로딩 1
- 운영과 같은 `pgvector/pgvector:pg16` 컨테이너에서 HTTP API 전 구간(가입 → 업로드 → 인덱싱 → 채팅)을 검증
- OpenAI 대신 가짜 임베딩·LLM을 사용해 **API 키·비용 없이** 반복 실행, LLM 프롬프트에 타인 문서가 섞이지 않는지까지 확인
- 따옴표·연산자가 들어간 아이디로 필터를 조작하는 우회 시도도 검증
- 일부러 격리 필터를 제거하면 테스트가 실패하는 것을 확인 (테스트가 실제로 결함을 잡아냄)

→ 테스트별 검증 내용은 [테스트 문서](docs/TESTING.md) 참고

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

## 📡 API 요약

> `/api/auth/**`와 소셜 로그인 경로를 제외한 모든 API는 `Authorization: Bearer {JWT}` 헤더가 필요합니다. (없거나 유효하지 않으면 401)

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/auth/register` · `/api/auth/login` | 회원가입 · 로그인 → `accessToken` 발급 |
| GET | `/oauth2/authorization/{kakao\|naver\|google}` | 소셜 로그인 → 프런트 콜백으로 JWT 전달 |
| POST | `/api/documents/upload` | 문서 업로드 (202, 백그라운드 인덱싱) |
| GET | `/api/documents` | 내 문서 목록 (인덱싱 상태 포함) |
| DELETE | `/api/documents/{id}` | 문서 삭제 (벡터 포함) |
| GET | `/api/chat/stream?message=&conversationId=` | SSE 채팅 (`conversation` → `sources` → `token` [→ `error`]) |
| GET | `/api/conversations` | 내 대화 목록 |
| GET | `/api/conversations/{id}/messages` | 대화 메시지 (출처 포함) |
| DELETE | `/api/conversations/{id}` | 대화 삭제 (메시지 포함) |

→ 요청/응답 예시와 오류 코드는 [API 문서](docs/API.md) 참고

---

## 🔮 향후 개선 계획

- [x] 서버 재시작 시 PENDING/INDEXING 상태로 멈춘 문서 복구 (FAILED 전환)
- [ ] 멈춘 문서 자동 재처리 (현재는 원본 파일을 저장하지 않아 사용자가 다시 업로드해야 함)
- [ ] 인덱싱 완료 실시간 알림 (현재는 프런트가 3초 간격 polling → SSE/WebSocket 푸시)
- [ ] 후속 질문을 LLM으로 독립 질문으로 재작성해 검색 정확도 향상 (Query Rewriting)
- [ ] 긴 대화 요약 저장으로 오래된 맥락 유지 (현재는 최근 10개 메시지만 사용)
- [ ] 청킹 전략 개선 (시맨틱 청킹 적용)
- [ ] 무료/유료 티어 분리 (문서 용량 제한)
- [ ] Ollama 연동으로 로컬 LLM 지원 (비용 절감)
- [ ] 재인덱싱 파이프라인 (임베딩 모델 교체 대응)
- [x] GitHub Actions CI (push·PR마다 통합 테스트 자동 실행)
