package com.myassistant.support;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 테스트용 가짜 임베딩 모델
 * - 단어를 해시하여 1536차원 벡터의 버킷에 더하는 방식(bag-of-words)으로, 같은 단어를 공유할수록 유사도가 높다
 * - OpenAI 호출 없이 결정적(항상 같은 결과)으로 동작하므로 격리 테스트를 안정적으로 반복할 수 있다
 */
public class FakeEmbeddingModel implements EmbeddingModel {

  // application.yml의 spring.ai.vectorstore.pgvector.dimensions와 동일해야 한다
  public static final int DIMENSIONS = 1536;

  /**
   * 요청된 텍스트 목록을 각각 임베딩한다.
   *
   * @param request 임베딩 요청
   * @return 입력 순서대로 매핑된 임베딩 응답
   */
  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    List<Embedding> embeddings = new ArrayList<>();
    List<String> inputs = request.getInstructions();
    for (int i = 0; i < inputs.size(); i++) {
      embeddings.add(new Embedding(embedText(inputs.get(i)), i));
    }
    return new EmbeddingResponse(embeddings);
  }

  /**
   * 문서 본문을 임베딩한다.
   *
   * @param document 임베딩할 문서
   * @return 1536차원 벡터
   */
  @Override
  public float[] embed(Document document) {
    return embedText(document.getText());
  }

  /**
   * 벡터 차원 수를 반환한다. (기본 구현은 실제 API를 호출하므로 재정의)
   *
   * @return 1536
   */
  @Override
  public int dimensions() {
    return DIMENSIONS;
  }

  /**
   * 텍스트를 단어 해시 기반 정규화 벡터로 변환한다.
   *
   * @param text 입력 텍스트
   * @return L2 정규화된 1536차원 벡터 (단어가 없으면 첫 원소만 1인 벡터)
   */
  private float[] embedText(String text) {
    float[] vector = new float[DIMENSIONS];
    if (text != null) {
      for (String word : text.toLowerCase(Locale.ROOT).split("\\s+")) {
        if (!word.isBlank()) {
          vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1f;
        }
      }
    }

    double norm = 0;
    for (float value : vector) {
      norm += value * value;
    }
    // 코사인 거리 계산 시 0 벡터는 NaN이 되므로 기본 방향을 준다
    if (norm == 0) {
      vector[0] = 1f;
      return vector;
    }
    float length = (float) Math.sqrt(norm);
    for (int i = 0; i < vector.length; i++) {
      vector[i] /= length;
    }
    return vector;
  }
}
