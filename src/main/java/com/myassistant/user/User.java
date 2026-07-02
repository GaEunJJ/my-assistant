package com.myassistant.user;

import lombok.Builder;
import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

@Getter
@Builder
@Table("users")
public class User {

  @Id
  private Long id;
  private String username;
  private String password;
  /** 소셜 로그인 제공자 (kakao / naver / google, 로컬 가입 시 null) */
  private String provider;
  /** 소셜 플랫폼에서 발급한 고유 사용자 ID */
  private String providerId;
  private OffsetDateTime createdAt;
}
