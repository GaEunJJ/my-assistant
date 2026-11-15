package com.myassistant.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myassistant.chat.dto.SourceInfo;
import com.myassistant.document.DocumentStatus;
import com.myassistant.document.dto.DocumentResponse;
import com.myassistant.document.dto.UploadResponse;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 통합 테스트 공통 기반 클래스
 * - 운영과 같은 pgvector/pgvector:pg16 컨테이너를 테스트 JVM 전체에서 1개만 띄워 재사용한다 (싱글톤 컨테이너)
 * - OpenAI 대신 FakeEmbeddingModel / RecordingChatModel을 빈으로 등록한다
 * - 회원가입, 업로드, 인덱싱 완료 대기, 채팅 등 HTTP API 호출 헬퍼를 제공한다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
@ActiveProfiles("test")
@Import(IntegrationTestSupport.FakeAiModelConfig.class)
public abstract class IntegrationTestSupport {

  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
      .withDatabaseName("myassistant")
      .withUsername("myassistant")
      .withPassword("test");

  static {
    // 테스트 클래스마다 컨테이너를 새로 띄우지 않도록 정적 블록에서 1회만 시작 (JVM 종료 시 Ryuk가 정리)
    POSTGRES.start();
  }

  @Autowired
  protected WebTestClient webTestClient;

  @Autowired
  protected RecordingChatModel chatModel;

  @Autowired
  protected JdbcTemplate jdbcTemplate;

  @Autowired
  protected ObjectMapper objectMapper;

  /**
   * Testcontainers DB 접속 정보를 R2DBC(사용자/문서)와 JDBC(PgVector) 양쪽에 주입한다.
   *
   * @param registry 동적 프로퍼티 등록기
   */
  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    String hostAndPort = POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + hostAndPort + "/" + POSTGRES.getDatabaseName());
    registry.add("spring.r2dbc.username", POSTGRES::getUsername);
    registry.add("spring.r2dbc.password", POSTGRES::getPassword);
  }

  /**
   * 테스트 간 프롬프트 기록이 섞이지 않도록 매 테스트 전에 비운다.
   */
  @BeforeEach
  void clearRecordedPrompts() {
    chatModel.clear();
  }

  /**
   * 테스트용 가짜 AI 모델 빈 설정
   * - application-test.yml에서 spring.ai.model.embedding/chat=none으로 OpenAI 자동 구성을 꺼두었으므로 유일한 빈이 된다
   */
  @TestConfiguration
  static class FakeAiModelConfig {

    /**
     * 가짜 임베딩 모델 빈을 등록한다.
     *
     * @return 단어 해시 기반 FakeEmbeddingModel
     */
    @Bean
    FakeEmbeddingModel fakeEmbeddingModel() {
      return new FakeEmbeddingModel();
    }

    /**
     * 프롬프트를 기록하는 가짜 ChatModel 빈을 등록한다.
     *
     * @return RecordingChatModel
     */
    @Bean
    RecordingChatModel recordingChatModel() {
      return new RecordingChatModel();
    }
  }

  // ===================== API 호출 헬퍼 =====================

  /**
   * 테스트마다 겹치지 않는 아이디를 만든다. (가입 규칙: 4~20자)
   *
   * @param prefix 아이디 접두어 (최대 11자 권장)
   * @return prefix + 8자리 랜덤 문자열
   */
  protected String uniqueUsername(String prefix) {
    return prefix + UUID.randomUUID().toString().substring(0, 8);
  }

  /**
   * 회원가입 후 발급된 JWT를 반환한다.
   *
   * @param username 아이디
   * @return 액세스 토큰
   */
  protected String registerAndGetToken(String username) {
    Map<String, String> body = webTestClient.post().uri("/api/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("username", username, "password", "password1234"))
        .exchange()
        .expectStatus().is2xxSuccessful()
        .expectBody(new ParameterizedTypeReference<Map<String, String>>() {})
        .returnResult()
        .getResponseBody();
    return Objects.requireNonNull(body).get("accessToken");
  }

  /**
   * 텍스트 파일을 업로드하고 생성된 문서 ID를 반환한다. (인덱싱 완료는 기다리지 않음)
   *
   * @param token    JWT
   * @param filename 파일명
   * @param content  파일 내용
   * @return 생성된 문서 ID
   */
  protected Long upload(String token, String filename, String content) {
    MultipartBodyBuilder builder = new MultipartBodyBuilder();
    builder.part("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)))
        .filename(filename)
        .contentType(MediaType.TEXT_PLAIN);

    UploadResponse response = webTestClient.post().uri("/api/documents/upload")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .bodyValue(builder.build())
        .exchange()
        .expectStatus().isAccepted()
        .expectBody(UploadResponse.class)
        .returnResult()
        .getResponseBody();
    return Objects.requireNonNull(response).document().id();
  }

  /**
   * 업로드 후 백그라운드 인덱싱이 DONE이 될 때까지 기다린다.
   *
   * @param token    JWT
   * @param filename 파일명
   * @param content  파일 내용
   * @return 인덱싱이 완료된 문서 ID
   */
  protected Long uploadAndAwaitIndexed(String token, String filename, String content) {
    Long documentId = upload(token, filename, content);
    await().atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(listDocuments(token))
            .filteredOn(document -> document.id().equals(documentId))
            .singleElement()
            .extracting(DocumentResponse::status)
            .isEqualTo(DocumentStatus.DONE));
    return documentId;
  }

  /**
   * 내 문서 목록을 조회한다.
   *
   * @param token JWT
   * @return 문서 목록
   */
  protected List<DocumentResponse> listDocuments(String token) {
    return webTestClient.get().uri("/api/documents")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .exchange()
        .expectStatus().isOk()
        .expectBodyList(DocumentResponse.class)
        .returnResult()
        .getResponseBody();
  }

  /**
   * 문서 삭제를 요청하고 응답을 검증할 수 있도록 반환한다.
   *
   * @param token      JWT
   * @param documentId 삭제할 문서 ID
   * @return 상태 코드 검증용 응답 스펙
   */
  protected WebTestClient.ResponseSpec deleteDocument(String token, Long documentId) {
    return webTestClient.delete().uri("/api/documents/{id}", documentId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .exchange();
  }

  /**
   * 채팅 SSE 스트림을 끝까지 받아 이벤트 목록으로 반환한다.
   *
   * @param token   JWT
   * @param message 질문
   * @return 수신한 SSE 이벤트 목록 (sources 1건 + token N건)
   */
  protected List<ServerSentEvent<String>> chat(String token, String message) {
    List<ServerSentEvent<String>> events = webTestClient.get()
        .uri(uriBuilder -> uriBuilder.path("/api/chat/stream").queryParam("message", message).build())
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .exchange()
        .expectStatus().isOk()
        .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
        .getResponseBody()
        .collectList()
        .block(Duration.ofSeconds(20));
    return Objects.requireNonNull(events);
  }

  /**
   * SSE 이벤트 목록에서 sources 이벤트의 출처 목록을 꺼낸다.
   *
   * @param events 채팅 SSE 이벤트 목록
   * @return 출처 목록
   * @throws Exception sources 이벤트 JSON 파싱에 실패한 경우
   */
  protected List<SourceInfo> sourcesOf(List<ServerSentEvent<String>> events) throws Exception {
    String json = events.stream()
        .filter(event -> "sources".equals(event.event()))
        .findFirst()
        .map(ServerSentEvent::data)
        .orElseThrow(() -> new AssertionError("sources 이벤트가 없습니다."));
    return objectMapper.readValue(json, new TypeReference<>() {});
  }

  /**
   * SSE 이벤트 목록에서 token 이벤트를 이어붙여 답변 전문을 만든다.
   *
   * @param events 채팅 SSE 이벤트 목록
   * @return 답변 전문
   */
  protected String answerOf(List<ServerSentEvent<String>> events) {
    StringBuilder answer = new StringBuilder();
    events.stream()
        .filter(event -> "token".equals(event.event()))
        .map(ServerSentEvent::data)
        .filter(Objects::nonNull)
        .forEach(answer::append);
    return answer.toString();
  }

  /**
   * PgVector에 저장된 특정 사용자의 벡터 청크 수를 센다. (API를 거치지 않고 DB를 직접 확인)
   *
   * @param userId 사용자 ID(username)
   * @return 해당 사용자의 청크 수
   */
  protected int countVectors(String userId) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM vector_store WHERE metadata->>'user_id' = ?", Integer.class, userId);
    return count == null ? 0 : count;
  }

  /**
   * PgVector에 저장된 특정 문서의 벡터 청크 수를 센다.
   *
   * @param documentId 문서 ID
   * @return 해당 문서의 청크 수
   */
  protected int countVectorsOfDocument(Long documentId) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM vector_store WHERE metadata->>'document_id' = ?", Integer.class, String.valueOf(documentId));
    return count == null ? 0 : count;
  }
}
