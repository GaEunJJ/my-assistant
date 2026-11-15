# 📡 API 명세

> [← README로 돌아가기](../README.md) · [아키텍처](ARCHITECTURE.md) · [설계 결정](ADR.md) · [API](API.md) · [테스트](TESTING.md) · [성능](PERFORMANCE.md)

> `/api/auth/**`, 소셜 로그인 경로를 제외한 모든 API는 `Authorization: Bearer {JWT}` 헤더가 필요합니다.  
> 토큰이 없거나 유효하지 않으면 `401 Unauthorized`를 반환합니다. 오류 응답 본문은 `{"message": "..."}` 형식입니다.

## 회원가입
```
POST /api/auth/register
Content-Type: application/json
{"username": "user1", "password": "password123"}    # 아이디 4~20자, 비밀번호 8자 이상

Response: 201 Created
{"accessToken": "eyJhbGciOiJIUzI1NiJ9..."}
```

## 로그인
```
POST /api/auth/login
Content-Type: application/json
{"username": "user1", "password": "password123"}

Response: 200 OK
{"accessToken": "eyJhbGciOiJIUzI1NiJ9..."}
```

## 소셜 로그인
```
GET /oauth2/authorization/{kakao|naver|google}    # 브라우저로 이동

→ 로그인 성공 시 302 {OAUTH2_REDIRECT_URI}?token={JWT}
```

## 문서 업로드
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

## 내 문서 목록
```
GET /api/documents

Response: 200 OK
# status: PENDING(대기) → INDEXING(인덱싱 중) → DONE(완료) / FAILED(실패, errorMessage에 사유)
[
  {"id": 12, "filename": "계약서.pdf", "status": "DONE", "chunkCount": 34, "errorMessage": null, "createdAt": "2026-11-15T10:00:00+09:00"},
  {"id": 11, "filename": "스캔본.pdf", "status": "FAILED", "chunkCount": null, "errorMessage": "문서에서 추출된 텍스트가 없습니다. (스캔 이미지 문서는 지원하지 않습니다)", "createdAt": "2026-11-14T18:20:00+09:00"}
]
```

## 문서 삭제
```
DELETE /api/documents/{id}

Response: 204 No Content     # 문서와 해당 문서의 벡터 청크를 함께 삭제
Response: 404 Not Found      # 문서가 없거나 다른 사용자의 문서
Response: 409 Conflict       # 인덱싱 진행 중 (완료/실패 후 삭제 가능)
```

## AI 채팅 (SSE 스트리밍)
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

## 대화 목록
```
GET /api/conversations

Response: 200 OK    # 최근 활동 순
[{"id": 7, "title": "휴가 규정 알려줘", "createdAt": "2026-11-15T10:00:00+09:00", "updatedAt": "2026-11-15T10:05:00+09:00"}]
```

## 대화 메시지
```
GET /api/conversations/{id}/messages

Response: 200 OK    # 시간순, 답변(ASSISTANT)에는 출처 포함
[
  {"id": 1, "role": "USER", "content": "휴가 규정 알려줘", "sources": [], "createdAt": "..."},
  {"id": 2, "role": "ASSISTANT", "content": "연차는 15일입니다...", "sources": [{"filename":"규정.pdf","page":2,"chunkIndex":3}], "createdAt": "..."}
]
Response: 404 Not Found      # 대화가 없거나 다른 사용자의 대화
```

## 대화 삭제
```
DELETE /api/conversations/{id}

Response: 204 No Content     # 대화와 메시지 모두 삭제
Response: 404 Not Found      # 대화가 없거나 다른 사용자의 대화
```

