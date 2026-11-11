# 04: 智谱水印回归测试

## ADDED Requirements

### Requirement: ZhipuMediaClient.generateImage request body MUST include watermark_enabled=false
A `ZhipuMediaClientTest` MUST use Mockito to mock the entire `HttpClient`, call `zhipuMediaClient.generateImage("test prompt")`, capture the `HttpRequest`, parse the `BodyPublishers.ofString(...)` body as JSON, and assert `body.get("watermark_enabled").asBoolean() === false`.

#### Scenario: 正常调用
- WHEN 调 generateImage("a cat")
- THEN 抓到的 body JSON 中 watermark_enabled 字段 MUST 存在且为 false

#### Scenario: 故意把 watermark_enabled 删掉
- WHEN 开发者删掉 `body.put("watermark_enabled", false)` 这一行
- THEN 该测试 case FAIL,提示"watermark_enabled 字段缺失"

#### Scenario: 误改为 true
- WHEN 开发者把 false 改成 true
- THEN 该测试 case FAIL,提示"watermark_enabled 必须为 false"

### Requirement: ZhipuMediaClient.submitVideo request body MUST include watermark_enabled=false
The same test approach MUST cover both `submitVideo(prompt)` and `submitVideo(prompt, size, withAudio)` code paths.

#### Scenario: submitVideo 无 size
- WHEN 调 submitVideo("a dog")
- THEN body 含 watermark_enabled=false

#### Scenario: submitVideo 带 size
- WHEN 调 submitVideo("a dog", "1920x1080", true)
- THEN body 含 watermark_enabled=false 且 size=1920x1080 且 with_audio=true

### Requirement: Tests MUST mock the HTTP layer and MUST NOT hit the real API
All cases MUST mock `httpClient.send(req, handler)` and return a mocked `HttpResponse<String>` (body may be a minimal valid JSON like `{"data":[{"url":"http://x"}]}`). Tests MUST NOT depend on a real ZHIPU_API_KEY or network access. Running `mvn test` MUST NOT consume any Zhipu quota.

#### Scenario: 测试无 API key 也能跑
- WHEN 环境变量无 ZHIPU_API_KEY
- THEN mvn test 仍可通过(ZhipuMediaClientTest 不读 env)

## Out of Scope

- 不测 `pollVideo`(与水印无关)
- 不测 `downloadToWorld`(与水印无关)
- 不改 `ZhipuMediaClient` 实际代码(只加测试)
