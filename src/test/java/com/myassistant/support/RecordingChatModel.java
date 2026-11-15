package com.myassistant.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 테스트용 가짜 ChatModel
 * - LLM에 전달된 프롬프트를 기록해, 다른 사용자의 문서 내용이 프롬프트에 섞이지 않았는지 검증할 수 있게 한다
 * - 실제 OpenAI를 호출하지 않고 고정 답변을 반환한다
 */
public class RecordingChatModel implements ChatModel {

  public static final String FIXED_ANSWER = "테스트 답변입니다.";

  private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

  /**
   * 동기 호출: 프롬프트를 기록하고 고정 답변을 반환한다.
   *
   * @param prompt LLM에 전달된 프롬프트
   * @return 고정 답변 응답
   */
  @Override
  public ChatResponse call(Prompt prompt) {
    prompts.add(prompt);
    return fixedResponse();
  }

  /**
   * 스트리밍 호출: 프롬프트를 기록하고 고정 답변 1건을 스트림으로 반환한다.
   *
   * @param prompt LLM에 전달된 프롬프트
   * @return 고정 답변 1건으로 구성된 Flux
   */
  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    prompts.add(prompt);
    return Flux.just(fixedResponse());
  }

  /**
   * 지금까지 기록된 모든 프롬프트의 전체 텍스트(시스템 + 사용자 메시지)를 반환한다.
   *
   * @return 프롬프트 텍스트 목록 (호출 순서)
   */
  public List<String> recordedPromptTexts() {
    return prompts.stream().map(Prompt::getContents).toList();
  }

  /**
   * 기록된 프롬프트를 비운다. (테스트 간 간섭 방지)
   */
  public void clear() {
    prompts.clear();
  }

  /**
   * 고정 답변으로 ChatResponse를 만든다.
   *
   * @return 고정 답변 응답
   */
  private ChatResponse fixedResponse() {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(FIXED_ANSWER))));
  }
}
