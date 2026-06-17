package com.myassistant.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PgVector 벡터 스토어 설정
 * - 사용자 문서 임베딩 저장 및 유사도 검색에 사용
 */
@Configuration
public class VectorStoreConfig {

  @Bean
  public VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
    return PgVectorStore.builder(jdbcTemplate, embeddingModel)
        .dimensions(1536)             // text-embedding-3-small 차원수
        .initializeSchema(true)       // 최초 실행 시 스키마 자동 생성
        .build();
  }
}
