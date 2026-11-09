package com.myassistant.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myassistant.chat.dto.SourceInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 기반 AI 채팅 서비스
 * - 사용자 질문을 벡터 검색으로 관련 문서 청크를 찾고 GPT-4o에 주입하여 답변 생성
 * - 검색된 문서의 출처(파일명/페이지/청크 순번)를 답변 스트리밍 전에 별도 SSE 이벤트로 전달
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

  private final ChatClient chatClient;
  private final VectorStore vectorStore;
  private final ObjectMapper objectMapper;

  // 유사도 검색 시 가져올 최대 청크 수
  private static final int TOP_K = 5;

  // 검색된 문서 컨텍스트를 질문과 함께 LLM에 전달하기 위한 프롬프트 템플릿
  private static final String USER_PROMPT_TEMPLATE = """
      다음은 질문과 관련된 문서 내용입니다. 이 내용을 참고하여 답변하세요.

      ---
      %s
      ---

      질문: %s
      """;

  /**
   * 사용자 질문에 대해 RAG 기반 SSE 스트리밍 답변 생성
   * - 첫 이벤트로 답변에 사용된 출처(파일명/페이지/청크 순번 목록)를 전달하고, 이후 토큰 단위로 답변을 스트리밍한다.
   *
   * @param message 사용자 질문
   * @param userId  현재 사용자 ID (내 문서만 검색하기 위한 필터)
   * @return "sources"(출처 목록) 이벤트 1건과 "token"(답변 토큰) 이벤트 다수로 구성된 SSE 스트림
   */
  public Flux<ServerSentEvent<String>> streamAnswer(String message, String userId) {
    // 벡터 검색은 블로킹 I/O이므로 WebFlux 이벤트 루프를 막지 않도록 별도 스케줄러에서 실행
    return Mono.fromCallable(() -> retrieveDocuments(message, userId))
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapMany(retrievedDocs -> buildEventStream(message, retrievedDocs));
  }

  /**
   * user_id로 필터링하여 질문과 유사한 상위 문서 청크를 검색한다.
   *
   * @param message 사용자 질문
   * @param userId  현재 사용자 ID
   * @return 유사도 상위 문서 청크 목록 (멀티테넌트 격리를 위해 본인 문서만 포함)
   */
  private List<Document> retrieveDocuments(String message, String userId) {
    FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
    SearchRequest searchRequest = SearchRequest.builder()
        .query(message)
        .topK(TOP_K)
        .filterExpression(filterBuilder.eq("user_id", userId).build())
        .build();

    return vectorStore.similaritySearch(searchRequest);
  }

  /**
   * 검색된 문서를 바탕으로 출처 이벤트와 답변 토큰 스트림을 이어붙인 SSE 스트림을 구성한다.
   *
   * @param message       사용자 질문
   * @param retrievedDocs 검색된 문서 청크 목록
   * @return 출처 이벤트 1건 뒤에 답변 토큰 이벤트가 이어지는 Flux
   */
  private Flux<ServerSentEvent<String>> buildEventStream(String message, List<Document> retrievedDocs) {
    List<SourceInfo> sources = retrievedDocs.stream()
        .map(this::toSourceInfo)
        .toList();

    String context = retrievedDocs.stream()
        .map(Document::getText)
        .collect(Collectors.joining("\n---\n"));

    Flux<ServerSentEvent<String>> sourceEvent = Flux.just(
        ServerSentEvent.<String>builder()
            .event("sources")
            .data(toJson(sources))
            .build());

    Flux<ServerSentEvent<String>> tokenStream = chatClient.prompt()
        .user(USER_PROMPT_TEMPLATE.formatted(context, message))
        .stream()
        .content()
        .map(chunk -> ServerSentEvent.<String>builder().event("token").data(chunk).build());

    return Flux.concat(sourceEvent, tokenStream);
  }

  /**
   * 검색된 문서 청크의 메타데이터로부터 출처 정보(파일명/페이지/청크 순번)를 만든다.
   * - 메타데이터는 PgVector에 JSON으로 저장/조회되므로, 숫자 타입은 Number로 받아 안전하게 변환한다.
   *
   * @param doc 검색된 문서 청크
   * @return 파일명/페이지/청크 순번을 담은 출처 정보
   */
  private SourceInfo toSourceInfo(Document doc) {
    Map<String, Object> metadata = doc.getMetadata();
    String filename = (String) metadata.getOrDefault("filename", "unknown");
    Integer page = metadata.get("page") instanceof Number n ? n.intValue() : null;
    Integer chunkIndex = metadata.get("chunk_index") instanceof Number n ? n.intValue() : null;
    return new SourceInfo(filename, page, chunkIndex);
  }

  /**
   * 출처 정보 목록을 JSON 배열 문자열로 직렬화한다.
   *
   * @param sources 출처 정보 목록
   * @return JSON 배열 문자열, 직렬화 실패 시 빈 배열("[]")
   */
  private String toJson(List<SourceInfo> sources) {
    try {
      return objectMapper.writeValueAsString(sources);
    } catch (JsonProcessingException e) {
      log.error("출처 목록 JSON 직렬화 실패", e);
      return "[]";
    }
  }
}
