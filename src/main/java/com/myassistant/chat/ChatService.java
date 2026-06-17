package com.myassistant.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * RAG 기반 AI 채팅 서비스
 * - 사용자 질문을 벡터 검색으로 관련 문서 청크를 찾고 GPT-4o에 주입하여 답변 생성
 */
@Service
@RequiredArgsConstructor
public class ChatService {

  private final ChatClient chatClient;
  private final VectorStore vectorStore;

  // 유사도 검색 시 가져올 최대 청크 수
  private static final int TOP_K = 5;

  /**
   * 사용자 질문에 대해 RAG 기반 SSE 스트리밍 답변 생성
   *
   * @param message 사용자 질문
   * @param userId  현재 사용자 ID (내 문서만 검색하기 위한 필터)
   * @return 토큰 단위 스트리밍 응답
   */
  public Flux<String> streamAnswer(String message, String userId) {
    // user_id 필터로 내 문서만 검색 (멀티테넌트 격리)
    FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
    SearchRequest searchRequest = SearchRequest.builder()
        .query(message)
        .topK(TOP_K)
        .filterExpression(filterBuilder.eq("user_id", userId).build())
        .build();

    return chatClient.prompt()
        .user(message)
        .advisors(new QuestionAnswerAdvisor(vectorStore, searchRequest))
        .stream()
        .content();
  }
}
