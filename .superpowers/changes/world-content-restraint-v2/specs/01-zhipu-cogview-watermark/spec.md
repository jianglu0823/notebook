# Spec: world-content-restraint-v2 / Zhipu cogview 水印修复

论坛配图(走 `ForumMediaService` → `ZhipuMediaClient.generateImage`,智谱
`cogview-3-flash`)右下角仍有"AI生成"水印。当前代码已设
`body.put("watermark_enabled", false)`,但实际部署后图片仍带水印,说明该参数
未生效。本 spec 做 belt-and-suspenders:同时设多个可能的去水印参数,选有效的
那个写进回归测试。

## ADDED Requirements

### Requirement: ZhipuMediaClient generateImage 至少 1 个去水印参数生效

`ZhipuMediaClient.generateImage` MUST 设置**至少一个** Zhipu 文档支持的
"关闭水印"参数,且**部署后新生成**的论坛配图右下角无"AI生成"字样。

#### Scenario: 顶层 watermark_enabled: false 仍存在
- WHEN 调 `ZhipuMediaClient.generateImage(prompt)`
- THEN HTTP body 顶层仍含 `watermark_enabled: false`(保留现有实现,不破坏)

#### Scenario: 嵌套 watermark: false 冗余
- WHEN 调 `ZhipuMediaClient.generateImage(prompt)`
- THEN HTTP body 同时含 `watermark: false`(在 body 顶层或合适位置),作为冗余
- AND 若 Zhipu API 解析"watermark"嵌套对象,该参数被命中

#### Scenario: 单元测试断言两个字段都在
- WHEN mock HTTP client,捕获发送的 body JSON
- THEN body 字符串同时含 `watermark_enabled` 键和 `watermark` 键
- AND 两者的 value 都是 `false`

#### Scenario: 部署后 curl 验证(部署前必做)
- WHEN 部署前在 dev 环境用 Zhipu API key curl 一发真实请求
- AND body 含 `watermark_enabled: false` + `watermark: false`
- THEN 返回的图片 URL 下载后右下角无"AI生成"字样
- AND 把验证结果(哪个字段真正生效)写进 W1-review.md

#### Scenario: 论坛新帖配图无水印(部署后)
- WHEN nblm-app 容器部署新版本
- AND forum 发 1 条 `category=daily` 帖,触发 `ForumMediaService.generateOneImage`
- THEN 生成的图片右下角无"AI生成"水印
- AND 用户截图确认

#### Scenario: Zhipu submitVideo 不变
- WHEN 调 `ZhipuMediaClient.submitVideo(...)`
- THEN body 仍只有 `watermark_enabled: false`(本 spec 不动 video 路径)

## MODIFIED Requirements

### Requirement: 修复机制不在 SDK 层(只动应用层)

本修复 MUST 仅在 `ZhipuMediaClient` 应用代码中设置参数,**不引入新 SDK 依赖**
或包替换。

#### Scenario: pom.xml 无新增依赖
- WHEN 部署新版本
- THEN `notebooklm-app/pom.xml` 的 `<dependencies>` 无新增坐标
- AND `ZhipuMediaClient.java` 无新增 `import` SDK 类
