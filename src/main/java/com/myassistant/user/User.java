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
  private OffsetDateTime createdAt;
}
