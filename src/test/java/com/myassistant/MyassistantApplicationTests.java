package com.myassistant;

import com.myassistant.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

/**
 * 애플리케이션 컨텍스트 로딩 테스트
 * - Testcontainers DB와 가짜 AI 모델로 전체 빈 구성이 정상적으로 뜨는지 확인한다
 */
class MyassistantApplicationTests extends IntegrationTestSupport {

	/**
	 * 컨텍스트가 예외 없이 로딩되는지 확인한다.
	 */
	@Test
	void contextLoads() {
	}

}
