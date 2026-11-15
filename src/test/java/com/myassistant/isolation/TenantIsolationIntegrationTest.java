package com.myassistant.isolation;

import com.myassistant.chat.dto.SourceInfo;
import com.myassistant.document.dto.DocumentResponse;
import com.myassistant.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.codec.ServerSentEvent;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 멀티테넌트 격리 통합 테스트 (ADR-004 검증)
 * - 사용자 A의 문서가 사용자 B의 목록 조회 / 삭제 / 채팅 검색 / LLM 프롬프트 어디에도 노출되지 않는지 확인한다
 * - 실제 PgVector(Testcontainers)에서 metadata user_id 필터가 동작하는지를 HTTP API 전 구간(end-to-end)으로 검증한다
 */
@DisplayName("멀티테넌트 격리")
class TenantIsolationIntegrationTest extends IntegrationTestSupport {

  // 다른 사용자에게 절대 노출되면 안 되는 문구 (프롬프트/답변/출처에서 검색)
  private static final String SECRET_PHRASE = "alpha-secret-contract-amount-9876";

  @Nested
  @DisplayName("문서 목록")
  class DocumentList {

    /**
     * 다른 사용자가 올린 문서는 내 문서 목록에 보이지 않는다.
     */
    @Test
    @DisplayName("다른 사용자의 문서는 목록에 보이지 않는다")
    void otherUsersDocumentsAreNotListed() {
      String alice = uniqueUsername("alice");
      String bob = uniqueUsername("bob");
      String aliceToken = registerAndGetToken(alice);
      String bobToken = registerAndGetToken(bob);

      Long aliceDocId = uploadAndAwaitIndexed(aliceToken, "alice-contract.txt", "계약 금액 " + SECRET_PHRASE);
      Long bobDocId = uploadAndAwaitIndexed(bobToken, "bob-memo.txt", "점심 메뉴 회의록");

      assertThat(listDocuments(aliceToken)).extracting(DocumentResponse::id)
          .containsExactly(aliceDocId);
      assertThat(listDocuments(bobToken)).extracting(DocumentResponse::id)
          .containsExactly(bobDocId);
    }
  }

  @Nested
  @DisplayName("문서 삭제")
  class DocumentDelete {

    /**
     * 다른 사용자의 문서 ID로 삭제를 요청하면 404이고, 원래 문서와 벡터는 그대로 남는다.
     * 404로 응답해 다른 사용자 문서의 존재 여부도 노출하지 않는다.
     */
    @Test
    @DisplayName("다른 사용자의 문서는 삭제할 수 없다 (404, 문서·벡터 유지)")
    void cannotDeleteOtherUsersDocument() {
      String alice = uniqueUsername("alice");
      String aliceToken = registerAndGetToken(alice);
      String bobToken = registerAndGetToken(uniqueUsername("bob"));

      Long aliceDocId = uploadAndAwaitIndexed(aliceToken, "alice-contract.txt", "계약 금액 " + SECRET_PHRASE);
      int vectorsBefore = countVectorsOfDocument(aliceDocId);

      deleteDocument(bobToken, aliceDocId).expectStatus().isNotFound();

      assertThat(listDocuments(aliceToken)).extracting(DocumentResponse::id).contains(aliceDocId);
      assertThat(countVectorsOfDocument(aliceDocId)).isEqualTo(vectorsBefore).isPositive();
    }

    /**
     * 같은 파일명·내용의 문서를 두 사용자가 올렸을 때, 한쪽이 삭제해도 다른 사용자의 벡터는 지워지지 않는다.
     */
    @Test
    @DisplayName("내 문서를 삭제해도 같은 내용의 다른 사용자 벡터는 남는다")
    void deletingOwnDocumentKeepsOtherUsersVectors() {
      String alice = uniqueUsername("alice");
      String bob = uniqueUsername("bob");
      String aliceToken = registerAndGetToken(alice);
      String bobToken = registerAndGetToken(bob);

      String sameContent = "공통 사내 규정 문서 휴가 규정";
      Long aliceDocId = uploadAndAwaitIndexed(aliceToken, "rules.txt", sameContent);
      uploadAndAwaitIndexed(bobToken, "rules.txt", sameContent);
      int bobVectorsBefore = countVectors(bob);

      deleteDocument(aliceToken, aliceDocId).expectStatus().isNoContent();

      assertThat(countVectors(alice)).isZero();
      assertThat(countVectors(bob)).isEqualTo(bobVectorsBefore).isPositive();
    }
  }

  @Nested
  @DisplayName("채팅 검색")
  class ChatRetrieval {

    /**
     * 두 사용자가 질문과 정확히 일치하는 문서를 각각 갖고 있어도, 검색·출처·LLM 프롬프트에는 본인 문서만 포함된다.
     */
    @Test
    @DisplayName("검색 결과·출처·LLM 프롬프트에 본인 문서만 포함된다")
    void chatUsesOnlyOwnDocuments() throws Exception {
      String aliceToken = registerAndGetToken(uniqueUsername("alice"));
      String bobToken = registerAndGetToken(uniqueUsername("bob"));

      // 질문 단어를 똑같이 포함시켜, 필터가 없다면 상대 문서가 검색될 수밖에 없는 조건을 만든다
      uploadAndAwaitIndexed(aliceToken, "alice-contract.txt", "계약 금액 알려줘 " + SECRET_PHRASE);
      uploadAndAwaitIndexed(bobToken, "bob-contract.txt", "계약 금액 알려줘 비공개 아님 bob-public-note");

      List<ServerSentEvent<String>> events = chat(bobToken, "계약 금액 알려줘");

      assertThat(sourcesOf(events)).extracting(SourceInfo::filename)
          .isNotEmpty()
          .containsOnly("bob-contract.txt");
      assertThat(chatModel.recordedPromptTexts()).singleElement()
          .asString()
          .contains("bob-public-note")
          .doesNotContain(SECRET_PHRASE);
      assertThat(answerOf(events)).doesNotContain(SECRET_PHRASE);
    }

    /**
     * 문서가 없는 사용자는 다른 사용자에게 문서가 있어도 검색 결과가 0건이며, LLM도 호출되지 않는다.
     */
    @Test
    @DisplayName("내 문서가 없으면 다른 사용자 문서가 있어도 검색되지 않고 LLM을 호출하지 않는다")
    void userWithoutDocumentsGetsNoResults() throws Exception {
      String aliceToken = registerAndGetToken(uniqueUsername("alice"));
      String newbieToken = registerAndGetToken(uniqueUsername("newbie"));

      uploadAndAwaitIndexed(aliceToken, "alice-contract.txt", "계약 금액 알려줘 " + SECRET_PHRASE);

      List<ServerSentEvent<String>> events = chat(newbieToken, "계약 금액 알려줘");

      assertThat(sourcesOf(events)).isEmpty();
      assertThat(answerOf(events)).contains("검색할 수 있는 문서가 없어요").doesNotContain(SECRET_PHRASE);
      assertThat(chatModel.recordedPromptTexts()).isEmpty();
    }
  }

  @Nested
  @DisplayName("필터 우회 시도")
  class FilterInjection {

    /**
     * 아이디(username)가 그대로 벡터 검색 필터 값이 되므로, 따옴표·연산자가 섞인 아이디로
     * 필터 식을 조작해 다른 사용자의 문서를 검색하거나 서버 오류를 일으킬 수 없어야 한다.
     *
     * @param maliciousUsername 필터 식 조작을 노린 아이디 (가입 규칙 4~20자 이내)
     */
    @ParameterizedTest(name = "아이디: {0}")
    @ValueSource(strings = {
        "a\" || $.user_id != \"",  // jsonpath 문자열을 닫고 항상 참인 조건을 이어붙이는 시도
        "o'neil' OR '1'='1",       // SQL 문자열 리터럴을 깨는 시도
        "x\\\" || true || \\\""    // 이스케이프 문자로 따옴표 처리를 우회하는 시도
    })
    @DisplayName("따옴표·연산자가 포함된 아이디로 다른 사용자 문서를 검색할 수 없다")
    void maliciousUsernameCannotBypassFilter(String maliciousUsername) throws Exception {
      String aliceToken = registerAndGetToken(uniqueUsername("alice"));
      uploadAndAwaitIndexed(aliceToken, "alice-contract.txt", "계약 금액 알려줘 " + SECRET_PHRASE);

      // 테스트 실행마다 새 DB 컨테이너를 쓰고 각 아이디는 한 번만 쓰이므로 접미어 없이 그대로 가입 (20자 제한)
      String attackerToken = registerAndGetToken(maliciousUsername);

      List<ServerSentEvent<String>> events = chat(attackerToken, "계약 금액 알려줘");

      assertThat(sourcesOf(events)).extracting(SourceInfo::filename).doesNotContain("alice-contract.txt");
      assertThat(answerOf(events)).doesNotContain(SECRET_PHRASE);
      assertThat(chatModel.recordedPromptTexts()).noneMatch(prompt -> prompt.contains(SECRET_PHRASE));
      assertThat(listDocuments(attackerToken)).isEmpty();
    }
  }
}
