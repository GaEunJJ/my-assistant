import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import java.nio.file.*;

/**
 * 파일별 GPT-4o(o200k_base) 토큰 수를 출력한다.
 * - 문서 전체 청크 텍스트와, 질문별로 LLM에 전달된 청크 텍스트를 각각 파일로 뽑아 비교하는 데 사용한다.
 * - 사용법 (jtokkit은 Spring AI 의존성으로 Gradle 캐시에 있음):
 *   java -cp jtokkit-1.1.0.jar scripts/perf/CountTokens.java full.txt q1.txt ...
 */
public class CountTokens {

  /**
   * 인자로 받은 파일마다 토큰 수를 출력한다.
   *
   * @param args 토큰을 셀 텍스트 파일 경로 목록
   * @throws Exception 파일을 읽지 못한 경우
   */
  public static void main(String[] args) throws Exception {
    Encoding enc = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
    for (String f : args) {
      System.out.println(Path.of(f).getFileName() + "\t" + enc.countTokens(Files.readString(Path.of(f))));
    }
  }
}
