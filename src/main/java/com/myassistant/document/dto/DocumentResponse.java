package com.myassistant.document.dto;

import com.myassistant.document.DocumentStatus;
import com.myassistant.document.UploadedDocument;

import java.time.OffsetDateTime;

/**
 * 문서 목록/업로드 응답 DTO
 *
 * @param id           문서 ID
 * @param filename     원본 파일명
 * @param status       인덱싱 상태
 * @param chunkCount   저장된 청크 수 (완료 전에는 null)
 * @param errorMessage 실패 사유 (실패 시에만 값 존재)
 * @param createdAt    업로드 시각
 */
public record DocumentResponse(
    Long id,
    String filename,
    DocumentStatus status,
    Integer chunkCount,
    String errorMessage,
    OffsetDateTime createdAt
) {

  /**
   * 엔티티를 응답 DTO로 변환한다.
   *
   * @param document 업로드 문서 엔티티
   * @return 응답 DTO
   */
  public static DocumentResponse from(UploadedDocument document) {
    return new DocumentResponse(
        document.getId(),
        document.getFilename(),
        document.getStatus(),
        document.getChunkCount(),
        document.getErrorMessage(),
        document.getCreatedAt()
    );
  }
}
