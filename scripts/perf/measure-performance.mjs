// 성능 지표 측정 스크립트 (로컬 백엔드 대상, README "성능 지표" 섹션의 근거)
// 1) 업로드 응답 시간 vs 인덱싱 완료 시간
// 2) 질문별 첫 토큰 도착 시간(TTFT) vs 답변 완료 시간
// 3) 질문별로 LLM에 전달된 청크 목록(sources) → 문서 전체 대비 비율은 DB에서 계산 (CountTokens.java 참고)
//
// 사용법 (백엔드가 localhost:8080에서 실행 중이어야 함, Node 18+):
//   node scripts/perf/measure-performance.mjs <업로드할 txt 파일> <결과 json 경로>
// 주의: 실제 OpenAI API를 호출하므로 비용이 발생하고(임베딩 1회 + GPT-4o 질문 5회),
//       측정용 임시 계정(perfXXXX)이 생성되므로 측정 후 DB에서 해당 계정 데이터를 지워야 한다.
import { readFile, writeFile } from "node:fs/promises";

const API = "http://localhost:8080";
const docPath = process.argv[2];
const outPath = process.argv[3];

const QUESTIONS = [
  "이 프로젝트에서 WebFlux를 선택한 이유가 뭐야?",
  "멀티테넌트 격리는 어떻게 구현했어?",
  "문서 인덱싱 흐름을 단계별로 설명해줘",
  "대화 이력은 어떤 테이블에 저장돼?",
  "로컬에서 실행하려면 어떤 환경변수가 필요해?",
];

const now = () => performance.now();
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const username = `perf${Math.random().toString(36).slice(2, 10)}`;
const reg = await fetch(`${API}/api/auth/register`, {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ username, password: "password1234" }),
});
const { accessToken } = await reg.json();
const auth = { Authorization: `Bearer ${accessToken}` };

// 1) 업로드
const form = new FormData();
form.append("file", new Blob([await readFile(docPath)], { type: "text/plain" }), "myassistant-readme.txt");
const uploadStart = now();
const uploadRes = await fetch(`${API}/api/documents/upload`, { method: "POST", headers: auth, body: form });
const uploadMs = now() - uploadStart;
const { document } = await uploadRes.json();

let indexedMs = null;
let finalDoc = null;
for (let i = 0; i < 300; i++) {
  const list = await (await fetch(`${API}/api/documents`, { headers: auth })).json();
  finalDoc = list.find((d) => d.id === document.id);
  if (finalDoc && (finalDoc.status === "DONE" || finalDoc.status === "FAILED")) {
    indexedMs = now() - uploadStart;
    break;
  }
  await sleep(100);
}

// 2) 질문별 TTFT / 완료 시간 (매 질문 새 대화 — 대화 이력이 프롬프트 크기에 영향을 주지 않도록)
const chats = [];
for (const question of QUESTIONS) {
  const start = now();
  const res = await fetch(`${API}/api/chat/stream?${new URLSearchParams({ message: question })}`, {
    headers: { ...auth, Accept: "text/event-stream" },
  });
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let firstTokenMs = null;
  let sources = [];
  let answer = "";
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const events = buffer.split("\n\n");
    buffer = events.pop() ?? "";
    for (const raw of events) {
      const event = raw.match(/^event:(.*)$/m)?.[1]?.trim();
      const data = raw
        .split("\n")
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5))
        .join("\n");
      if (event === "sources") sources = JSON.parse(data);
      if (event === "token") {
        if (firstTokenMs == null) firstTokenMs = now() - start;
        answer += data;
      }
    }
  }
  chats.push({
    question,
    status: res.status,
    firstTokenMs: Math.round(firstTokenMs),
    totalMs: Math.round(now() - start),
    answerChars: answer.length,
    chunkIndexes: sources.map((s) => s.chunkIndex),
  });
}

const result = {
  username,
  documentId: document.id,
  status: finalDoc?.status,
  chunkCount: finalDoc?.chunkCount,
  uploadMs: Math.round(uploadMs),
  indexedMs: Math.round(indexedMs),
  chats,
};
await writeFile(outPath, JSON.stringify(result, null, 2));
console.log(JSON.stringify(result, null, 2));
