package com.myassistant;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

// @EnableAsync가 없으면 DocumentService의 @Async가 무시되어 인덱싱(임베딩 호출 등 블로킹 I/O)이
// 요청 스레드(WebFlux 이벤트 루프)에서 그대로 실행되어 버린다
@EnableAsync
@SpringBootApplication
public class MyassistantApplication {

	public static void main(String[] args) {
		loadDotenvIntoSystemProperties();
		SpringApplication.run(MyassistantApplication.class, args);
	}

	// .env 파일이 있으면 시스템 프로퍼티로 로드 (이미 설정된 환경변수/시스템 프로퍼티는 덮어쓰지 않음)
	private static void loadDotenvIntoSystemProperties() {
		Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
		dotenv.entries().forEach(entry -> {
			if (System.getProperty(entry.getKey()) == null && System.getenv(entry.getKey()) == null) {
				System.setProperty(entry.getKey(), entry.getValue());
			}
		});
	}

}
