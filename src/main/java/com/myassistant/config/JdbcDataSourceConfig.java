package com.myassistant.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * PgVector(JDBC) 전용 DataSource 설정
 * - R2DBC ConnectionFactory가 존재하면 Spring Boot가 DataSourceAutoConfiguration을 비활성화하므로
 *   PgVectorStore가 사용할 JDBC DataSource를 직접 정의한다
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class JdbcDataSourceConfig {

  @Bean
  public DataSource dataSource(DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder().build();
  }
}
