package com.myassistant.oauth2;

import com.myassistant.user.User;
import com.myassistant.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 소셜 플랫폼에서 받은 OAuth2 사용자 정보를 DB와 연동하는 서비스.
 * 신규 사용자면 자동 가입, 기존 사용자면 조회 후 CustomOAuth2User로 래핑하여 반환한다.
 */
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService implements ReactiveOAuth2UserService<OAuth2UserRequest, OAuth2User> {

  private final UserRepository userRepository;

  /**
   * 소셜 플랫폼 userinfo 엔드포인트를 호출하여 사용자 정보를 가져오고,
   * DB에 사용자를 저장 또는 조회한 뒤 CustomOAuth2User로 반환한다.
   *
   * @param userRequest 액세스 토큰과 클라이언트 등록 정보를 담은 요청
   * @return DB username이 담긴 CustomOAuth2User
   * @throws OAuth2AuthenticationException userinfo 호출 실패 시
   */
  @Override
  public Mono<OAuth2User> loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
    DefaultReactiveOAuth2UserService delegate = new DefaultReactiveOAuth2UserService();

    return delegate.loadUser(userRequest)
        .flatMap(oAuth2User -> {
          String registrationId = userRequest.getClientRegistration().getRegistrationId();
          String userNameAttributeName = userRequest.getClientRegistration()
              .getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();

          OAuthAttributes attrs = OAuthAttributes.of(
              registrationId, userNameAttributeName, oAuth2User.getAttributes()
          );

          return saveOrFind(attrs)
              .map(user -> (OAuth2User) new CustomOAuth2User(oAuth2User, user.getUsername()));
        });
  }

  /**
   * provider + providerId로 기존 사용자를 조회하고, 없으면 신규 사용자를 생성하여 반환한다.
   *
   * @param attrs 정규화된 소셜 사용자 정보
   * @return 저장된 또는 기존 User 엔티티
   */
  private Mono<User> saveOrFind(OAuthAttributes attrs) {
    return userRepository.findByProviderAndProviderId(attrs.provider(), attrs.providerId())
        .switchIfEmpty(Mono.defer(() -> {
          // {provider}_{providerId} 형태로 고유 username 생성
          String username = attrs.provider() + "_" + attrs.providerId();
          User newUser = User.builder()
              .username(username)
              .provider(attrs.provider())
              .providerId(attrs.providerId())
              .build();
          return userRepository.save(newUser);
        }));
  }
}
