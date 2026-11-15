package com.myassistant.conversation;

import lombok.Builder;
import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

/**
 * 대화방 엔티티 (R2DBC)
 * - 사용자가 질문을 이어가는 하나의 대화 단위. 목록에는 updatedAt(마지막 메시지 시각) 최신순으로 노출된다
 */
@Getter
@Builder(toBuilder = true)
@Table("conversations")
public class Conversation {

  @Id
  private Long id;
  /** 대화 소유자 ID (username, 멀티테넌트 격리 기준) */
  private String userId;
  /** 대화 제목 (첫 질문 앞부분으로 자동 생성) */
  private String title;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  /**
   * 마지막 활동 시각을 현재로 갱신한 새 인스턴스를 반환한다.
   *
   * @return updatedAt이 갱신된 Conversation
   */
  public Conversation touched() {
    return toBuilder().updatedAt(OffsetDateTime.now()).build();
  }
}
