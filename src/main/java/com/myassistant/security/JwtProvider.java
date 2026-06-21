package com.myassistant.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtProvider {

  private final SecretKey signingKey;
  private final long expiration;

  public JwtProvider(
      @Value("${jwt.secret}") String secret,
      @Value("${jwt.expiration}") long expiration
  ) {
    this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    this.expiration = expiration;
  }

  /**
   * 사용자명을 subject로 하는 JWT 액세스 토큰을 생성한다.
   *
   * @param username 토큰 subject로 사용할 사용자명
   * @return 서명된 JWT 문자열
   */
  public String generateToken(String username) {
    Date now = new Date();
    return Jwts.builder()
        .subject(username)
        .issuedAt(now)
        .expiration(new Date(now.getTime() + expiration))
        .signWith(signingKey)
        .compact();
  }

  /**
   * JWT 토큰에서 사용자명(subject)을 추출한다.
   *
   * @param token 검증할 JWT 문자열
   * @return 토큰에 담긴 사용자명
   * @throws JwtException 토큰이 만료되었거나 서명이 유효하지 않을 경우
   */
  public String extractUsername(String token) {
    return parseClaims(token).getSubject();
  }

  /**
   * JWT 토큰의 서명과 만료 여부를 검증한다.
   *
   * @param token 검증할 JWT 문자열
   * @return 토큰이 유효하면 true, 그렇지 않으면 false
   */
  public boolean isValid(String token) {
    try {
      parseClaims(token);
      return true;
    } catch (JwtException | IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * JWT 토큰을 파싱하여 Claims를 반환한다.
   *
   * @param token 파싱할 JWT 문자열ㄷ
   * @return 토큰에 담긴 Claims
   * @throws JwtException 토큰이 만료되었거나 서명이 유효하지 않을 경우
   */
  private Claims parseClaims(String token) {
    return Jwts.parser()
        .verifyWith(signingKey)
        .build()
        .parseSignedClaims(token)
        .getPayload();
  }
}
