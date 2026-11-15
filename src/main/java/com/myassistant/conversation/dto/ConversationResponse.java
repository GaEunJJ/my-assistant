package com.myassistant.conversation.dto;

import com.myassistant.conversation.Conversation;

import java.time.OffsetDateTime;

/**
 * 대화 목록 응답 DTO
 *
 * @param id        대화 ID
 * @param title     대화 제목
 * @param createdAt 생성 시각
 * @param updatedAt 마지막 메시지 시각
 */
public record ConversationResponse(Long id, String title, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

  /**
   * 엔티티를 응답 DTO로 변환한다.
   *
   * @param conversation 대화 엔티티
   * @return 응답 DTO
   */
  public static ConversationResponse from(Conversation conversation) {
    return new ConversationResponse(
        conversation.getId(), conversation.getTitle(), conversation.getCreatedAt(), conversation.getUpdatedAt());
  }
}
