package io.llmnote.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实端到端:连 nblm-app WS,发一条让 AI 加日程的消息,验证 schedule-agent 收到了事件。
 * 需要 nblm-app(8080)+ schedule-agent(8081)同时跑,JWT 写在 env NBLM_TEST_JWT。
 */
@EnabledIfEnvironmentVariable(named = "NBLM_TEST_JWT", matches = ".+")
class CompanionA2AEndToEndTest {

    private static final String APP = "http://127.0.0.1:8080";
    private static final String SCHED = "http://127.0.0.1:8081";
    private static final String INTERNAL_TOKEN = "dev-internal-token-change-me";

    @Test
    void companion_chat_actually_creates_event_via_a2a() throws Exception {
        String jwt = System.getenv("NBLM_TEST_JWT");
        HttpClient http = HttpClient.newHttpClient();
        // 从 /api/auth/me 拿真实 userId(避免硬编码 1;companion / schedule-agent 都按这个 userId 走)
        HttpRequest meReq = HttpRequest.newBuilder(URI.create(APP + "/api/auth/me"))
                .header("Authorization", "Bearer " + jwt).GET().build();
        HttpResponse<String> meResp = http.send(meReq, HttpResponse.BodyHandlers.ofString());
        assertThat(meResp.statusCode()).isEqualTo(200);
        long userId = new ObjectMapper().readTree(meResp.body()).path("userId").asLong();
        System.out.println("[setup] userId = " + userId);
        HttpRequest listReq = HttpRequest.newBuilder(URI.create(SCHED
                        + "/api/schedule/v1/events?from=2026-08-07T00:00:00%2B08:00&to=2026-08-08T00:00:00%2B08:00"))
                .header("X-User-Id", String.valueOf(userId))
                .header("X-Schedule-Agent-Token", INTERNAL_TOKEN)
                .GET().build();
        HttpResponse<String> listBefore = http.send(listReq, HttpResponse.BodyHandlers.ofString());
        assertThat(listBefore.statusCode()).isEqualTo(200);
        int before = countEvents(listBefore.body());
        System.out.println("[before] events today = " + before);

        HttpRequest cpnList = HttpRequest.newBuilder(URI.create(APP + "/api/companion"))
                .header("Authorization", "Bearer " + jwt).GET().build();
        HttpResponse<String> cpnResp = http.send(cpnList, HttpResponse.BodyHandlers.ofString());
        assertThat(cpnResp.statusCode()).isEqualTo(200);
        long companionId = parseFirstCompanionId(cpnResp.body());
        System.out.println("[setup] companionId = " + companionId);

        String wsUrl = "ws://127.0.0.1:8080/api/ws/companion?token=" + jwt;
        StringBuilder acc = new StringBuilder();
        String[] errHolder = new String[1];
        CompletableFuture<Void> doneFuture = new CompletableFuture<>();

        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        WebSocket.Listener.super.onOpen(webSocket);
                        ObjectMapper om = new ObjectMapper();
                        ObjectNode msg = om.createObjectNode();
                        msg.put("kind", "chat.text");
                        msg.put("companionId", companionId);
                        msg.put("content", "今天下午 2 点帮我加个会议,叫 'A2A 集成验收',30 分钟");
                        msg.put("model", "qwen-plus");
                        webSocket.sendText(msg.toString(), true);
                    }
                    @Override
                    public CompletionStage onText(WebSocket webSocket, CharSequence data, boolean last) {
                        try {
                            String s = data.toString();
                            ObjectMapper om = new ObjectMapper();
                            var n = om.readTree(s);
                            String kind = n.path("kind").asText("");
                            switch (kind) {
                                case "chat.delta" -> acc.append(n.path("delta").asText(""));
                                case "chat.done" -> {
                                    System.out.println("[done] fullText=" + n.path("fullText").asText(""));
                                    doneFuture.complete(null);
                                }
                                case "chat.error", "error" -> {
                                    errHolder[0] = (kind + ": " + n.path("error").asText(""));
                                    doneFuture.complete(null);
                                }
                            }
                        } catch (Exception e) { e.printStackTrace(); }
                        return WebSocket.Listener.super.onText(webSocket, data, last);
                    }
                }).get(10, TimeUnit.SECONDS);

        doneFuture.get(60, TimeUnit.SECONDS);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        System.out.println("[ai]   acc=" + acc);
        assertThat(errHolder[0]).as("no error from chat").isNull();
        assertThat(acc.toString()).as("AI replied something").isNotBlank();

        Thread.sleep(800);
        HttpResponse<String> listAfter = http.send(listReq, HttpResponse.BodyHandlers.ofString());
        int after = countEvents(listAfter.body());
        System.out.println("[after] events today = " + after);
        assertThat(after).as("schedule-agent should have +1 event").isEqualTo(before + 1);
    }

    private static int countEvents(String body) throws Exception {
        var arr = new ObjectMapper().readTree(body);
        return arr.isArray() ? arr.size() : 0;
    }

    private static long parseFirstCompanionId(String body) throws Exception {
        var arr = new ObjectMapper().readTree(body);
        return arr.get(0).path("id").asLong();
    }
}
