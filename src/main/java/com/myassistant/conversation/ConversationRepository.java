package com.myassistant.conversation;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 대화방 R2DBC 레포지토리
 * - 멀티테넌트 격리를 위해 조회 메서드는 모두 userId 조건을 포함한다
 */
public interface ConversationRepository extends ReactiveCrudRepository<Conversation, Long> {

  /**
   * 사용자의 대화 목록을 최근 활동 순으로 조회한다.
   *
   * @param userId 사용자 ID
   * @return 대화 목록
   */
  Flux<Conversation> findAllByUserIdOrderByUpdatedAtDesc(String userId);

  /**
   * 사용자 본인의 대화를 ID로 조회한다.
   *
   * @param id     대화 ID
   * @param userId 사용자 ID
   * @return 조회된 대화, 없거나 다른 사용자의 대화면 empty
   */
  Mono<Conversation> findByIdAndUserId(Long id, String userId);
}
