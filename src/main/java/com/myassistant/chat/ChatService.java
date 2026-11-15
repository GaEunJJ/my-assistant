package com.myassistant.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myassistant.chat.dto.SourceInfo;
import com.myassistant.conversation.ChatMessage;
import com.myassistant.conversation.Conversation;
import com.myassistant.conversation.ConversationService;
import com.myassistant.conversation.MessageRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 기반 AI 채팅 서비스 (대화 이력 포함)
 * - 사용자 질문을 벡터 검색으로 관련 문서 청크를 찾고 GPT-4o에 주입하여 답변 생성
 * - 같은 대화의 최근 메시지를 프롬프트에 함께 넣어 "그건 왜 그래?" 같은 후속 질문을 이해하게 한다
 * - 질문/답변은 conversations/chat_messages 테이블에 저장되어 새로고침 후에도 복원된다
 * - SSE 이벤트 순서: conversation(대화 ID) → sources(출처) → token(답변 토큰) N건 [→ error]
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

  private final ChatClient chatClient;
  private final VectorStore vectorStore;
  private final ObjectMapper objectMapper;
  private final ConversationService conversationService;

  // 유사도 검색 시 가져올 최대 청크 수
  private static final int TOP_K = 5;

  // LLM 프롬프트에 넣을 최근 메시지 수 (질문+답변 5턴) — 토큰 비용과 맥락 유지의 절충
  private static final int HISTORY_LIMIT = 10;

  // 검색 가능한 문서가 없을 때 LLM 대신 보내는 안내 메시지
  private static final String NO_DOCUMENTS_MESSAGE =
      "아직 검색할 수 있는 문서가 없어요. '내 문서'에서 문서를 업로드하고 인덱싱이 완료된 뒤 질문해 주세요.";

  // LLM 호출 실패 시 클라이언트에 보내는 안내 메시지 (내부 오류 상세는 노출하지 않음)
  private static final String LLM_ERROR_MESSAGE = "답변을 생성하는 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.";

  // 스트리밍 도중 연결이 끊겨 답변이 끝까지 생성되지 않았음을 표시하는 문구
  private static final String INTERRUPTED_SUFFIX = "\n\n(답변이 중단되었습니다)";

  // 검색된 문서 컨텍스트를 질문과 함께 LLM에 전달하기 위한 프롬프트 템플릿
  private static final String USER_PROMPT_TEMPLATE = """
      다음은 질문과 관련된 문서 내용입니다. 이 내용을 참고하여 답변하세요.

      ---
      %s
      ---

      질문: %s
      """;

  /**
   * 사용자 질문에 대해 대화 맥락을 반영한 RAG 기반 SSE 스트리밍 답변을 생성한다.
   * - conversationId가 없으면 새 대화를 만들고, 첫 이벤트로 대화 ID를 알려준다.
   *
   * @param message        사용자 질문
   * @param conversationId 이어갈 대화 ID (새 대화면 null)
   * @param userId         현재 사용자 ID (내 문서·내 대화만 다루기 위한 필터)
   * @return conversation / sources / token [/ error] 이벤트로 구성된 SSE 스트림
   * @throws com.myassistant.conversation.ConversationNotFoundException 대화가 없거나 다른 사용자의 대화인 경우
   */
  public Flux<ServerSentEvent<String>> streamAnswer(String message, Long conversationId, String userId) {
    return conversationService.getOrCreate(conversationId, userId, message)
        .flatMapMany(conversation -> conversationService
            // 이번 질문을 저장하기 전에 이력을 읽어야 "직전까지의 대화"만 맥락으로 들어간다
            .findRecentHistory(conversation.getId(), userId, HISTORY_LIMIT)
            .flatMapMany(history -> conversationService.saveUserMessage(conversation, message)
                .then(retrieveDocuments(buildRetrievalQuery(message, history), userId))
                .flatMapMany(retrievedDocs -> buildEventStream(conversation, message, history, retrievedDocs))));
  }

  /**
   * 벡터 검색에 사용할 질의문을 만든다.
   * - "그건 왜 그래?"처럼 대명사만 있는 후속 질문은 단독으로 검색하면 관련 문서를 못 찾으므로,
   *   직전 사용자 질문을 앞에 붙여 검색한다. (LLM으로 질문을 재작성하는 방식은 호출 비용이 추가되어 사용하지 않음)
   *
   * @param message 현재 질문
   * @param history 직전까지의 대화 메시지 (시간순)
   * @return 검색 질의문
   */
  private String buildRetrievalQuery(String message, List<ChatMessage> history) {
    for (int i = history.size() - 1; i >= 0; i--) {
      ChatMessage previous = history.get(i);
      if (previous.getRole() == MessageRole.USER) {
        return previous.getContent() + "\n" + message;
      }
    }
    return message;
  }

  /**
   * user_id로 필터링하여 질의문과 유사한 상위 문서 청크를 검색한다.
   * - 벡터 검색은 블로킹 I/O(JDBC)이므로 WebFlux 이벤트 루프를 막지 않도록 별도 스케줄러에서 실행한다.
   *
   * @param query  검색 질의문
   * @param userId 현재 사용자 ID
   * @return 유사도 상위 문서 청크 목록 (멀티테넌트 격리를 위해 본인 문서만 포함)
   */
  private Mono<List<Document>> retrieveDocuments(String query, String userId) {
    FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
    SearchRequest searchRequest = SearchRequest.builder()
        .query(query)
        .topK(TOP_K)
        .filterExpression(filterBuilder.eq("user_id", userId).build())
        .build();

    return Mono.fromCallable(() -> vectorStore.similaritySearch(searchRequest))
        .subscribeOn(Schedulers.boundedElastic());
  }

  /**
   * 대화 ID 이벤트, 출처 이벤트, 답변 토큰 스트림을 이어붙이고, 답변이 끝나면 대화 이력에 저장한다.
   *
   * @param conversation  현재 대화
   * @param message       사용자 질문
   * @param history       직전까지의 대화 메시지 (시간순)
   * @param retrievedDocs 검색된 문서 청크 목록
   * @return conversation → sources → token N건 [→ error] 순서의 Flux
   */
  private Flux<ServerSentEvent<String>> buildEventStream(
      Conversation conversation, String message, List<ChatMessage> history, List<Document> retrievedDocs) {
    Flux<ServerSentEvent<String>> conversationEvent = Flux.just(event("conversation", toConversationJson(conversation)));

    // 검색 결과가 0건이면(인덱싱 완료된 문서가 없음) 빈 문맥으로 LLM을 호출하지 않는다
    // → 불필요한 토큰 비용과 문서 근거 없는 답변(환각)을 막는다
    if (retrievedDocs.isEmpty()) {
      return Flux.concat(
          conversationEvent,
          Flux.just(event("sources", "[]"), event("token", NO_DOCUMENTS_MESSAGE)),
          conversationService.saveAssistantMessage(conversation, NO_DOCUMENTS_MESSAGE, List.of())
              .then(Mono.empty()));
    }

    List<SourceInfo> sources = retrievedDocs.stream()
        .map(this::toSourceInfo)
        .toList();

    String context = retrievedDocs.stream()
        .map(Document::getText)
        .collect(Collectors.joining("\n---\n"));

    // 스트리밍된 토큰을 모아 두었다가 완료 시 답변 전문으로 저장
    StringBuilder answer = new StringBuilder();

    Flux<ServerSentEvent<String>> answerStream = chatClient.prompt()
        .messages(toPromptMessages(history))
        .user(USER_PROMPT_TEMPLATE.formatted(context, message))
        .stream()
        .content()
        .doOnNext(answer::append)
        .map(chunk -> event("token", chunk))
        .concatWith(Mono.defer(() -> conversationService
                .saveAssistantMessage(conversation, answer.toString(), sources))
            .then(Mono.empty()))
        // LLM 오류 시 SSE 연결을 그냥 끊지 않고 error 이벤트로 안내 (오류 상세는 로그로만 남김)
        .onErrorResume(e -> {
          log.error("답변 생성 실패 - userId: {}, conversationId: {}", conversation.getUserId(), conversation.getId(), e);
          return Flux.just(event("error", LLM_ERROR_MESSAGE));
        })
        // 사용자가 스트리밍 도중 페이지를 떠나면, 받은 데까지의 답변이라도 이력에 남긴다
        .doOnCancel(() -> {
          if (!answer.isEmpty()) {
            conversationService.saveAssistantMessage(conversation, answer + INTERRUPTED_SUFFIX, sources)
                .subscribe(
                    saved -> log.info("중단된 답변 저장 - conversationId: {}", conversation.getId()),
                    e -> log.error("중단된 답변 저장 실패 - conversationId: {}", conversation.getId(), e));
          }
        });

    return Flux.concat(conversationEvent, Flux.just(event("sources", toJson(sources))), answerStream);
  }

  /**
   * 저장된 대화 메시지를 Spring AI 메시지로 변환한다. (LLM이 이전 대화 맥락을 알 수 있도록)
   *
   * @param history 대화 메시지 (시간순)
   * @return UserMessage / AssistantMessage 목록
   */
  private List<Message> toPromptMessages(List<ChatMessage> history) {
    return history.stream()
        .<Message>map(saved -> saved.getRole() == MessageRole.USER
            ? new UserMessage(saved.getContent())
            : new AssistantMessage(saved.getContent()))
        .toList();
  }

  /**
   * 이벤트 이름과 데이터로 SSE 이벤트를 만든다.
   *
   * @param name 이벤트 이름
   * @param data 이벤트 데이터
   * @return SSE 이벤트
   */
  private ServerSentEvent<String> event(String name, String data) {
    return ServerSentEvent.<String>builder().event(name).data(data).build();
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
   * conversation 이벤트 데이터(대화 ID, 제목)를 JSON으로 만든다.
   *
   * @param conversation 현재 대화
   * @return {"conversationId": 1, "title": "..."} 형태의 JSON
   */
  private String toConversationJson(Conversation conversation) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("conversationId", conversation.getId());
    data.put("title", conversation.getTitle());
    return writeJson(data, "{}");
  }

  /**
   * 출처 정보 목록을 JSON 배열 문자열로 직렬화한다.
   *
   * @param sources 출처 정보 목록
   * @return JSON 배열 문자열, 직렬화 실패 시 빈 배열("[]")
   */
  private String toJson(List<SourceInfo> sources) {
    return writeJson(sources, "[]");
  }

  /**
   * 객체를 JSON 문자열로 직렬화한다.
   *
   * @param value    직렬화할 객체
   * @param fallback 실패 시 반환할 기본값
   * @return JSON 문자열
   */
  private String writeJson(Object value, String fallback) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      log.error("SSE 이벤트 JSON 직렬화 실패", e);
      return fallback;
    }
  }
}
