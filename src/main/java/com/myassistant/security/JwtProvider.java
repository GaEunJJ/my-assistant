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

  public String generateToken(String username) {
    Date now = new Date();
    return Jwts.builder()
        .subject(username)
        .issuedAt(now)
        .expiration(new Date(now.getTime() + expiration))
        .signWith(signingKey)
        .compact();
  }

  public String extractUsername(String token) {
    return parseClaims(token).getSubject();
  }

  public boolean isValid(String token) {
    try {
      parseClaims(token);
      return true;
    } catch (JwtException | IllegalArgumentException e) {
      return false;
    }
  }

  private Claims parseClaims(String token) {
    return Jwts.parser()
        .verifyWith(signingKey)
        .build()
        .parseSignedClaims(token)
        .getPayload();
  }
}
