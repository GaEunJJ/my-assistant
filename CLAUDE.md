## 코드 작성 규칙

- 모든 메서드에 Javadoc 형식의 주석을 달 것
- 복잡한 비즈니스 로직은 인라인 주석 추가
- 주석에는 반드시 다음을 포함할 것:
  - 메서드의 역할 (한 줄 요약)
  - @param 설명
  - @return 설명
  - 예외 발생 조건이 있으면 @throws

예시:
```java
/**
 * 사용자 ID로 문서를 조회하여 반환한다.
 *
 * @param userId 테넌트 식별자
 * @param docId  문서 고유 ID
 * @return 조회된 Document 객체
 * @throws DocumentNotFoundException 문서가 존재하지 않을 경우
 */
```

## Architecture Rules
- 멀티테넌트: 모든 쿼리에 user_id 필터링 필수
- 벡터 검색: PgVector metadata 필터링으로 테넌트 격리
- 문서 ingestion: 비동기 파이프라인 (동기 처리 금지)
- SSE 스트리밍: WebFlux Flux<ServerSentEvent> 방식 유지

## 주의사항
- Spring AI 의존성은 build.gradle에 수동 추가 필요
- R2DBC는 JPA 어노테이션 사용 불가
- WebFlux 환경에서 ThreadLocal 사용 금지

