package com.myassistant.conversation;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

/**
 * 대화 메시지 R2DBC 레포지토리
 * - 멀티테넌트 격리를 위해 조회 메서드는 모두 userId 조건을 포함한다
 */
public interface ChatMessageRepository extends ReactiveCrudRepository<ChatMessage, Long> {

  /**
   * 대화의 전체 메시지를 시간순(오래된 것부터)으로 조회한다.
   *
   * @param conversationId 대화 ID
   * @param userId         사용자 ID
   * @return 메시지 목록
   */
  Flux<ChatMessage> findAllByConversationIdAndUserIdOrderByIdAsc(Long conversationId, String userId);

  /**
   * 대화의 최근 메시지를 최신순으로 limit개 조회한다. (LLM 프롬프트에 넣을 대화 맥락용)
   *
   * @param conversationId 대화 ID
   * @param userId         사용자 ID
   * @param limit          최대 개수
   * @return 최신순 메시지 목록 (호출부에서 시간순으로 뒤집어 사용)
   */
  @Query("SELECT * FROM chat_messages WHERE conversation_id = :conversationId AND user_id = :userId "
      + "ORDER BY id DESC LIMIT :limit")
  Flux<ChatMessage> findRecent(Long conversationId, String userId, int limit);
}
