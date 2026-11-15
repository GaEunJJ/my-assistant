package com.myassistant.document;

import lombok.Builder;
import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

/**
 * 업로드 문서 엔티티 (R2DBC)
 * - Spring AI의 Document(벡터 청크)와 이름이 겹치지 않도록 UploadedDocument로 명명
 * - 벡터 청크는 메타데이터의 document_id로 이 엔티티와 연결된다
 */
@Getter
@Builder(toBuilder = true)
@Table("documents")
public class UploadedDocument {

  @Id
  private Long id;
  /** 업로드한 사용자 ID (username, 멀티테넌트 격리 기준) */
  private String userId;
  private String filename;
  private DocumentStatus status;
  /** 인덱싱 완료 시 저장된 청크 수 */
  private Integer chunkCount;
  /** 인덱싱 실패 사유 */
  private String errorMessage;
  private OffsetDateTime createdAt;
  /** 마지막 상태 변경·진행 신호 시각 (멈춘 문서 판단 기준) */
  private OffsetDateTime updatedAt;
}
