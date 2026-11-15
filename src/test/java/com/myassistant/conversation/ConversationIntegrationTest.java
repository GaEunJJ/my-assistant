package com.myassistant.conversation;

import com.myassistant.chat.dto.SourceInfo;
import com.myassistant.conversation.dto.ChatMessageResponse;
import com.myassistant.conversation.dto.ConversationResponse;
import com.myassistant.support.IntegrationTestSupport;
import com.myassistant.support.RecordingChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대화 이력 통합 테스트
 * - 새 대화 생성, 멀티턴 맥락 유지(LLM 프롬프트), 후속 질문 검색, 메시지 저장·복원, 삭제를 검증한다
 * - 대화 이력도 문서와 동일하게 다른 사용자에게 노출되지 않는지(멀티테넌트 격리) 확인한다
 */
@DisplayName("대화 이력")
class ConversationIntegrationTest extends IntegrationTestSupport {

  @Nested
  @DisplayName("대화 생성과 멀티턴")
  class MultiTurn {

    /**
     * conversationId 없이 질문하면 새 대화가 생성되고, 첫 질문이 제목이 된다.
     */
    @Test
    @DisplayName("대화 ID 없이 질문하면 새 대화가 생성되고 첫 질문이 제목이 된다")
    void firstQuestionCreatesConversation() throws Exception {
      String token = registerAndGetToken(uniqueUsername("user"));
      uploadAndAwaitIndexed(token, "rules.txt", "휴가 규정 연차 15일");

      List<ServerSentEvent<String>> events = chat(token, "휴가 규정 알려줘");
      Long conversationId = conversationIdOf(events);

      assertThat(events).extracting(ServerSentEvent::event)
          .startsWith("conversation", "sources");
      assertThat(listConversations(token)).singleElement()
          .satisfies(conversation -> {
            assertThat(conversation.id()).isEqualTo(conversationId);
            assertThat(conversation.title()).isEqualTo("휴가 규정 알려줘");
          });
    }

    /**
     * 같은 대화에서 두 번째 질문을 하면, 첫 질문과 첫 답변이 LLM 프롬프트에 함께 전달된다.
     */
    @Test
    @DisplayName("같은 대화의 이전 질문과 답변이 다음 LLM 프롬프트에 포함된다")
    void previousTurnsAreIncludedInPrompt() throws Exception {
      String token = registerAndGetToken(uniqueUsername("user"));
      uploadAndAwaitIndexed(token, "rules.txt", "휴가 규정 연차 15일");

      Long conversationId = conversationIdOf(chat(token, "휴가 규정 알려줘"));
      chat(token, "그건 몇 일이야?", conversationId);

      List<String> prompts = chatModel.recordedPromptTexts();
      assertThat(prompts).hasSize(2);
      assertThat(prompts.get(0)).doesNotContain(RecordingChatModel.FIXED_ANSWER);
      assertThat(prompts.get(1))
          .contains("휴가 규정 알려줘")
          .contains(RecordingChatModel.FIXED_ANSWER)
          .contains("그건 몇 일이야?");
    }

    /**
     * 새 대화를 시작하면 다른 대화의 맥락은 프롬프트에 들어가지 않는다.
     */
    @Test
    @DisplayName("새 대화에는 다른 대화의 맥락이 섞이지 않는다")
    void newConversationHasNoPreviousContext() throws Exception {
      String token = registerAndGetToken(uniqueUsername("user"));
      uploadAndAwaitIndexed(token, "rules.txt", "휴가 규정 연차 15일");

      chat(token, "첫번째-대화-질문 휴가 규정");
      // FakeEmbeddingModel은 겹치는 단어가 없으면 유사도가 0이라 검색 결과가 비므로, 공통 단어(휴가)를 넣어 LLM이 호출되게 한다
      chat(token, "두번째 대화 휴가 질문");

      assertThat(chatModel.recordedPromptTexts().get(1)).doesNotContain("첫번째-대화-질문");
    }

    /**
     * "그건 몇 일이야?"처럼 단독으로는 관련 문서를 찾기 어려운 후속 질문도,
     * 직전 질문을 함께 검색에 사용하므로 관련 문서가 가장 먼저 검색된다.
     */
    @Test
    @DisplayName("후속 질문은 직전 질문과 함께 검색되어 관련 문서를 찾는다")
    void followUpQuestionUsesPreviousQuestionForRetrieval() throws Exception {
      String token = registerAndGetToken(uniqueUsername("user"));
      uploadAndAwaitIndexed(token, "vacation.txt", "휴가 규정 연차 15일 휴가 신청");
      // 검색 상위 5건(TOP_K)을 채울 만큼 관련 없는 문서를 올려, 질의문에 따라 순위가 달라지게 만든다
      for (int i = 1; i <= 5; i++) {
        uploadAndAwaitIndexed(token, "noise-" + i + ".txt", "점심 메뉴 회의 " + i + " 주차장 안내");
      }

      Long conversationId = conversationIdOf(chat(token, "휴가 규정 알려줘"));
      List<SourceInfo> followUpSources = sourcesOf(chat(token, "그건 몇 일이야?", conversationId));

      assertThat(followUpSources).first()
          .extracting(SourceInfo::filename)
          .isEqualTo("vacation.txt");
    }
  }

  @Nested
  @DisplayName("저장과 복원")
  class Persistence {

    /**
     * 질문과 답변이 시간순으로 저장되고, 답변 메시지에는 출처가 함께 저장되어 새로고침 후 복원할 수 있다.
     */
    @Test
    @DisplayName("질문·답변이 시간순으로 저장되고 답변의 출처도 복원된다")
    void messagesAreSavedWithSources() throws Exception {
      String token = registerAndGetToken(uniqueUsername("user"));
      uploadAndAwaitIndexed(token, "rules.txt", "휴가 규정 연차 15일");

      Long conversationId = conversationIdOf(chat(token, "휴가 규정 알려줘"));
      chat(token, "신청 방법은?", conversationId);

      List<ChatMessageResponse> messages = listMessages(token, conversationId);
      assertThat(messages).extracting(ChatMessageResponse::role)
          .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT);
      assertThat(messages).extracting(ChatMessageResponse::content)
          .containsExactly("휴가 규정 알려줘", RecordingChatModel.FIXED_ANSWER,
              "신청 방법은?", RecordingChatModel.FIXED_ANSWER);
      assertThat(messages.get(1).sources()).extracting(SourceInfo::filename).containsExactly("rules.txt");
      assertThat(messages.get(0).sources()).isEmpty();
    }

    /**
     * 문서가 없어 LLM을 호출하지 않은 경우에도 질문과 안내 답변이 대화에 남는다.
     */
    @Test
    @DisplayName("문서가 없을 때의 안내 답변도 대화에 저장된다")
    void noDocumentsAnswerIsSaved() throws Exception {
      String token = registerAndGetToken(uniqueUsername("user"));

      Long conversationId = conversationIdOf(chat(token, "안녕"));

      assertThat(listMessages(token, conversationId)).extracting(ChatMessageResponse::content)
          .hasSize(2)
          .last().asString().contains("검색할 수 있는 문서가 없어요");
      assertThat(chatModel.recordedPromptTexts()).isEmpty();
    }

    /**
     * 대화를 삭제하면 메시지까지 모두 삭제되어 더 이상 조회되지 않는다.
     */
    @Test
    @DisplayName("대화를 삭제하면 메시지까지 함께 삭제된다")
    void deleteConversationRemovesMessages() throws Exception {
      String token = registerAndGetToken(uniqueUsername("user"));
      Long conversationId = conversationIdOf(chat(token, "안녕"));

      deleteConversation(token, conversationId).expectStatus().isNoContent();

      assertThat(listConversations(token)).isEmpty();
      messagesRequest(token, conversationId).expectStatus().isNotFound();
      Integer remaining = jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM chat_messages WHERE conversation_id = ?", Integer.class, conversationId);
      assertThat(remaining).isZero();
    }

    /**
     * 빈 질문은 400으로 거절되고 대화가 생성되지 않는다.
     */
    @Test
    @DisplayName("빈 질문은 400으로 거절되고 대화가 생성되지 않는다")
    void blankMessageIsRejected() {
      String token = registerAndGetToken(uniqueUsername("user"));

      chatRequest(token, "   ", null).expectStatus().isBadRequest();

      assertThat(listConversations(token)).isEmpty();
    }
  }

  @Nested
  @DisplayName("멀티테넌트 격리")
  class Isolation {

    /**
     * 다른 사용자의 대화는 목록에 보이지 않고, 메시지 조회·삭제는 404로 거절된다.
     */
    @Test
    @DisplayName("다른 사용자의 대화는 목록에 보이지 않고 조회·삭제할 수 없다 (404)")
    void cannotAccessOtherUsersConversation() throws Exception {
      String aliceToken = registerAndGetToken(uniqueUsername("alice"));
      String bobToken = registerAndGetToken(uniqueUsername("bob"));
      Long aliceConversationId = conversationIdOf(chat(aliceToken, "앨리스의 비밀 질문"));

      assertThat(listConversations(bobToken)).extracting(ConversationResponse::id)
          .doesNotContain(aliceConversationId);
      messagesRequest(bobToken, aliceConversationId).expectStatus().isNotFound();
      deleteConversation(bobToken, aliceConversationId).expectStatus().isNotFound();

      assertThat(listMessages(aliceToken, aliceConversationId)).hasSize(2);
    }

    /**
     * 다른 사용자의 대화 ID로 질문을 이어가려 하면 404로 거절되고,
     * 그 대화에 메시지가 추가되거나 대화 내용이 LLM 프롬프트로 새어 나가지 않는다.
     */
    @Test
    @DisplayName("다른 사용자의 대화 ID로 질문을 이어갈 수 없다 (404, 메시지 추가·맥락 유출 없음)")
    void cannotContinueOtherUsersConversation() throws Exception {
      String aliceToken = registerAndGetToken(uniqueUsername("alice"));
      String bobToken = registerAndGetToken(uniqueUsername("bob"));
      uploadAndAwaitIndexed(aliceToken, "secret.txt", "앨리스 비밀 계약 금액");
      uploadAndAwaitIndexed(bobToken, "bob.txt", "밥의 업무 문서 계약 금액 정리");
      Long aliceConversationId = conversationIdOf(chat(aliceToken, "앨리스-비밀-질문 계약 금액"));
      chatModel.clear();

      chatRequest(bobToken, "이전 대화 이어서 알려줘", aliceConversationId).expectStatus().isNotFound();

      assertThat(listMessages(aliceToken, aliceConversationId)).hasSize(2);
      assertThat(chatModel.recordedPromptTexts()).isEmpty();
    }
  }
}
