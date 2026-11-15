package com.myassistant.conversation;

import lombok.Builder;
import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

/**
 * 대화 메시지 엔티티 (R2DBC)
 * - 질문(USER)과 답변(ASSISTANT)을 시간순으로 저장한다
 * - 답변 메시지는 화면 복원을 위해 출처 목록을 JSON 문자열로 함께 저장한다
 */
@Getter
@Builder
@Table("chat_messages")
public class ChatMessage {

  @Id
  private Long id;
  private Long conversationId;
  /** 메시지 소유자 ID (username, 멀티테넌트 격리 기준) */
  private String userId;
  private MessageRole role;
  private String content;
  /** 출처 목록 JSON 배열 문자열 (질문 메시지는 null) */
  private String sources;
  private OffsetDateTime createdAt;
}
