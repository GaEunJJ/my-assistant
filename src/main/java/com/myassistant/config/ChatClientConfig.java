package com.myassistant.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI ChatClient 빈 설정
 */
@Configuration
public class ChatClientConfig {

  @Bean
  public ChatClient chatClient(ChatModel chatModel) {
    return ChatClient.builder(chatModel)
        .defaultSystem("""
            당신은 사용자가 업로드한 문서를 기반으로 질문에 답변하는 AI 어시스턴트입니다.
            제공된 문서 내용을 근거로 정확하게 답변하고, 문서에 없는 내용은 모른다고 답변하세요.
            """)
        .build();
  }
}
