package model;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpResponse;
import java.time.Duration;

public class LLM {

    // ===== Config =====
    private static final String MODEL = "gemini-2.5-flash";
    private static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/"
            + MODEL + ":generateContent?key=";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(12);

    private final String apiKey;
    private final HttpClient http;

    public LLM() {
        String key = "AIzaSyCSeJkWCGwwX-BABEMS7yYpkVSZMBqhJ-U";
        if (key == null || key.isBlank()) {
            // Bạn có thể đổi thành hardcode nếu muốn, nhưng nên dùng env var
            throw new IllegalStateException("Missing GEMINI_API_KEY environment variable");
        }
        this.apiKey = key;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public String getAnswer(String inputJson) {
        try {
            String prompt = buildPrompt(inputJson);
            String payload = buildRequestPayload(prompt);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + apiKey))
                    .timeout(CALL_TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();

            // Trích text ứng viên đầu tiên: candidates[0].content.parts[0].text
            // Với response_mime_type=application/json, phần text này CHÍNH LÀ JSON output.
            String text = extractFirstText(body);
            if (text == null || text.isBlank()) {
                // Fallback cứng: trả SKIP, kèm thông tin để debug
                return "{\"pick\":\"SKIP\",\"signals\":{},\"rationale\":\"Empty LLM text\"}";
            }
            return text.trim();
        } catch (HttpTimeoutException te) {
            return "{\"pick\":\"SKIP\",\"signals\":{},\"rationale\":\"LLM timeout\"}";
        } catch (Exception e) {
            return "{\"pick\":\"SKIP\",\"signals\":{},\"rationale\":\"LLM error: " + escapeForJson(e.getMessage()) + "\"}";
        }
    }

    // ===== Prompt “ưu việt” (giảm SKIP, ép JSON) =====
    private String buildPrompt(String inputJson) {
        return """
ROLE
You are a cold, rational analyst for a dice-based Tài/Xỉu game (possibly manipulated).
Input: exactly 13 most recent results, "T" = Tài, "X" = Xỉu. 
Output: one of {TAI, XIU, SKIP} with rationale.

INPUT
%s

STEP 1: Compute features
- last_streak = (sym, len)
- prev_streak = (sym, len)
- p_T, p_X = proportions in 13
- alternation = flips / 12
- oscillation = true if last.len ≥4 and prev.len ≥4 and sym ≠ prev.sym
- dominance = max(p_T, p_X)
- imbalance = |p_T - 0.5|

STEP 2: Score scenarios
- Oscillation pattern → Score(anti-run) +2
- Overextended (last.len ≥5):
    - If prev.len ≤2 → Score(anti-run) +1
    - Else → Score(skip) +1
- Short run (last.len 1–3) AND dominance ≥0.6 → Score(follow) +1
- Alternation ≥0.7 → Score(skip) +2
- dominance ≥0.65 → Score(follow majority) +1

STEP 3: Decision
- Compare scores:
    - If anti-run highest → pick opposite of last_streak.sym
    - Else if follow highest → pick last_streak.sym or majority side
    - If tie or skip highest → SKIP

OUTPUT FORMAT
{
  "pick": "T" | "X" | "S",
  "signals": {
    "last_streak": int,
    "prev_streak": int,
    "p_T": float,
    "p_X": float,
    "alternation": float,
    "oscillation": true|false,
    "scores": {"anti-run": int, "follow": int, "skip": int}
  },
  "rationale": "≤20 words, cold, mechanical"
}

STYLE
- Never emotional, never talk about money.
- If no clear edge then SKIP.
""".formatted(inputJson);
    }

    private String buildRequestPayload(String prompt) {
        String escaped = escapeForJson(prompt);
        return """
        {
          "contents": [
            {
              "parts": [
                { "text": "%s" }
              ]
            }
          ],
          "generationConfig": {
            "response_mime_type": "application/json",
            "temperature": 0.1,
            "maxOutputTokens": 256,
            "topK": 40,
            "topP": 0.8
          }
        }
        """.formatted(escaped);
    }

    // ===== Helpers =====
   
    private String extractFirstText(String responseJson) {
        if (responseJson == null) {
            return null;
        }

        // Tìm khóa "text":
        int key = responseJson.indexOf("\"text\"");
        if (key < 0) {
            return null;
        }

        // Tìm dấu ':' sau "text"
        int colon = responseJson.indexOf(':', key + 6);
        if (colon < 0) {
            return null;
        }

        // Bỏ qua whitespace tới dấu quote mở
        int i = colon + 1;
        while (i < responseJson.length() && Character.isWhitespace(responseJson.charAt(i))) {
            i++;
        }
        if (i >= responseJson.length() || responseJson.charAt(i) != '\"') {
            return null;
        }

        // Đọc chuỗi JSON (có thể có escape) cho tới dấu quote đóng
        int start = i + 1;
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int j = start; j < responseJson.length(); j++) {
            char c = responseJson.charAt(j);
            if (escape) {
                // Xử lý các escape phổ biến
                switch (c) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case '\"':
                        sb.append('\"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    default:
                        sb.append(c);
                        break;
                }
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '\"') {
                // Kết thúc chuỗi
                return sb.toString();
            }
            sb.append(c);
        }
        return null; // không tìm thấy quote đóng hợp lệ
    }

    /**
     * Escape chuỗi để nhét an toàn vào JSON (chỉ phần cần thiết).
     */
    private String escapeForJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
