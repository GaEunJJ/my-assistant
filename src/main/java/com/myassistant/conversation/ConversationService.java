package com.myassistant.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myassistant.chat.dto.SourceInfo;
import com.myassistant.conversation.dto.ChatMessageResponse;
import com.myassistant.conversation.dto.ConversationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 대화 이력 서비스
 * - 대화방 생성/조회/삭제와 질문·답변 메시지 저장을 담당한다
 * - 모든 조회/삭제는 userId 조건으로 본인 대화만 다룬다 (멀티테넌트 격리)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

  // 대화 제목 최대 길이 (첫 질문을 잘라서 사용)
  private static final int TITLE_MAX_LENGTH = 30;

  private final ConversationRepository conversationRepository;
  private final ChatMessageRepository messageRepository;
  private final ObjectMapper objectMapper;

  /**
   * 기존 대화를 조회하거나, ID가 없으면 첫 질문으로 제목을 만들어 새 대화를 생성한다.
   *
   * @param conversationId 이어갈 대화 ID (새 대화면 null)
   * @param userId         사용자 ID
   * @param firstMessage   새 대화의 제목으로 쓸 첫 질문
   * @return 조회 또는 생성된 대화
   * @throws ConversationNotFoundException 대화 ID가 없거나 다른 사용자의 대화인 경우
   */
  public Mono<Conversation> getOrCreate(Long conversationId, String userId, String firstMessage) {
    if (conversationId != null) {
      return findOwned(conversationId, userId);
    }
    OffsetDateTime now = OffsetDateTime.now();
    return conversationRepository.save(Conversation.builder()
        .userId(userId)
        .title(toTitle(firstMessage))
        .createdAt(now)
        .updatedAt(now)
        .build());
  }

  /**
   * 사용자의 대화 목록을 최근 활동 순으로 조회한다.
   *
   * @param userId 사용자 ID
   * @return 대화 응답 DTO 스트림
   */
  public Flux<ConversationResponse> findAll(String userId) {
    return conversationRepository.findAllByUserIdOrderByUpdatedAtDesc(userId)
        .map(ConversationResponse::from);
  }

  /**
   * 본인 대화의 전체 메시지를 시간순으로 조회한다.
   *
   * @param conversationId 대화 ID
   * @param userId         사용자 ID
   * @return 메시지 응답 DTO 스트림
   * @throws ConversationNotFoundException 대화가 없거나 다른 사용자의 대화인 경우
   */
  public Flux<ChatMessageResponse> findMessages(Long conversationId, String userId) {
    // 대화 소유 여부를 먼저 확인해, 다른 사용자의 대화는 빈 목록이 아니라 404로 응답한다
    return findOwned(conversationId, userId)
        .flatMapMany(conversation -> messageRepository
            .findAllByConversationIdAndUserIdOrderByIdAsc(conversation.getId(), userId))
        .map(this::toResponse);
  }

  /**
   * LLM 프롬프트에 넣을 최근 메시지를 시간순(오래된 것부터)으로 조회한다.
   *
   * @param conversationId 대화 ID
   * @param userId         사용자 ID
   * @param limit          최대 메시지 수
   * @return 시간순 메시지 목록
   */
  public Mono<List<ChatMessage>> findRecentHistory(Long conversationId, String userId, int limit) {
    return messageRepository.findRecent(conversationId, userId, limit)
        .collectList()
        .map(recent -> {
          List<ChatMessage> chronological = new ArrayList<>(recent);
          Collections.reverse(chronological);
          return chronological;
        });
  }

  /**
   * 사용자 질문 메시지를 저장한다.
   *
   * @param conversation 대화
   * @param content      질문 내용
   * @return 저장된 메시지
   */
  public Mono<ChatMessage> saveUserMessage(Conversation conversation, String content) {
    return saveMessage(conversation, MessageRole.USER, content, null);
  }

  /**
   * AI 답변 메시지를 출처와 함께 저장하고, 대화의 마지막 활동 시각을 갱신한다.
   *
   * @param conversation 대화
   * @param content      답변 전문
   * @param sources      답변에 사용된 출처 목록
   * @return 저장된 메시지
   */
  public Mono<ChatMessage> saveAssistantMessage(Conversation conversation, String content, List<SourceInfo> sources) {
    return saveMessage(conversation, MessageRole.ASSISTANT, content, toJson(sources))
        .flatMap(saved -> conversationRepository.save(conversation.touched()).thenReturn(saved));
  }

  /**
   * 본인 대화와 그 메시지를 모두 삭제한다. (chat_messages는 FK ON DELETE CASCADE로 함께 삭제)
   *
   * @param conversationId 대화 ID
   * @param userId         사용자 ID
   * @return 삭제 완료 신호
   * @throws ConversationNotFoundException 대화가 없거나 다른 사용자의 대화인 경우
   */
  public Mono<Void> delete(Long conversationId, String userId) {
    return findOwned(conversationId, userId)
        .flatMap(conversationRepository::delete)
        .doOnSuccess(unused -> log.info("대화 삭제 완료 - userId: {}, conversationId: {}", userId, conversationId));
  }

  /**
   * 본인 소유의 대화를 조회한다.
   *
   * @param conversationId 대화 ID
   * @param userId         사용자 ID
   * @return 조회된 대화
   * @throws ConversationNotFoundException 대화가 없거나 다른 사용자의 대화인 경우
   */
  private Mono<Conversation> findOwned(Long conversationId, String userId) {
    return conversationRepository.findByIdAndUserId(conversationId, userId)
        .switchIfEmpty(Mono.error(new ConversationNotFoundException(conversationId)));
  }

  /**
   * 메시지 1건을 저장한다.
   *
   * @param conversation 대화
   * @param role         작성 주체
   * @param content      내용
   * @param sourcesJson  출처 JSON (없으면 null)
   * @return 저장된 메시지
   */
  private Mono<ChatMessage> saveMessage(Conversation conversation, MessageRole role, String content, String sourcesJson) {
    return messageRepository.save(ChatMessage.builder()
        .conversationId(conversation.getId())
        .userId(conversation.getUserId())
        .role(role)
        .content(content)
        .sources(sourcesJson)
        .createdAt(OffsetDateTime.now())
        .build());
  }

  /**
   * 첫 질문으로 대화 제목을 만든다. (공백 정리 후 최대 30자, 넘으면 말줄임)
   *
   * @param message 첫 질문
   * @return 대화 제목
   */
  private String toTitle(String message) {
    String normalized = message == null ? "" : message.strip().replaceAll("\\s+", " ");
    if (normalized.isEmpty()) {
      return "새 대화";
    }
    return normalized.length() > TITLE_MAX_LENGTH
        ? normalized.substring(0, TITLE_MAX_LENGTH) + "…"
        : normalized;
  }

  /**
   * 엔티티를 응답 DTO로 변환한다. (출처 JSON → 목록)
   *
   * @param message 메시지 엔티티
   * @return 응답 DTO
   */
  private ChatMessageResponse toResponse(ChatMessage message) {
    return new ChatMessageResponse(
        message.getId(), message.getRole(), message.getContent(), fromJson(message.getSources()), message.getCreatedAt());
  }

  /**
   * 출처 목록을 JSON 문자열로 직렬화한다.
   *
   * @param sources 출처 목록
   * @return JSON 배열 문자열, 실패 시 "[]"
   */
  private String toJson(List<SourceInfo> sources) {
    try {
      return objectMapper.writeValueAsString(sources == null ? List.of() : sources);
    } catch (JsonProcessingException e) {
      log.error("출처 목록 JSON 직렬화 실패", e);
      return "[]";
    }
  }

  /**
   * JSON 문자열을 출처 목록으로 역직렬화한다.
   *
   * @param json 출처 JSON (null 가능)
   * @return 출처 목록, 값이 없거나 실패 시 빈 목록
   */
  private List<SourceInfo> fromJson(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      log.error("출처 목록 JSON 역직렬화 실패", e);
      return List.of();
    }
  }
}
