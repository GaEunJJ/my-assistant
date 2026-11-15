package com.myassistant.conversation.dto;

import com.myassistant.chat.dto.SourceInfo;
import com.myassistant.conversation.MessageRole;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 대화 메시지 응답 DTO
 *
 * @param id        메시지 ID
 * @param role      작성 주체 (USER / ASSISTANT)
 * @param content   메시지 내용
 * @param sources   답변에 사용된 출처 목록 (질문 메시지는 빈 목록)
 * @param createdAt 작성 시각
 */
public record ChatMessageResponse(
    Long id,
    MessageRole role,
    String content,
    List<SourceInfo> sources,
    OffsetDateTime createdAt
) {
}
