import '../core/api_client.dart';

/// 创作中心:小红书(含短剧) + 协作写作
class StudioApi {
  // ---- 小红书 ----
  static Future<List<dynamic>> xhsList() async =>
      await ApiClient.get('/api/studio/xhs');

  static Future<Map<String, dynamic>> xhsGet(int id) async =>
      await ApiClient.get('/api/studio/xhs/$id');

  /// Step1:提交方向,异步生成候选标题
  static Future<Map<String, dynamic>> xhsCreate(String direction) async =>
      await ApiClient.post('/api/studio/xhs', {'direction': direction});

  /// Step2:选定标题,异步搜集素材
  static Future<Map<String, dynamic>> xhsResearch(int id, String title) async =>
      await ApiClient.post('/api/studio/xhs/$id/research', {'title': title});

  /// Step3:选定风格,异步生成文案
  static Future<Map<String, dynamic>> xhsCopy(int id, String style) async =>
      await ApiClient.post('/api/studio/xhs/$id/copy', {'style': style});

  /// Step4:异步生成配图
  static Future<Map<String, dynamic>> xhsImages(int id) async =>
      await ApiClient.post('/api/studio/xhs/$id/images', {});

  static Future<void> xhsDelete(int id) async =>
      await ApiClient.del('/api/studio/xhs/$id');

  // ---- 短剧 ----
  static Future<List<dynamic>> dramaList() async =>
      await ApiClient.get('/api/studio/xhs/drama');

  static Future<List<dynamic>> dramaDrafts(int id) async =>
      await ApiClient.get('/api/studio/xhs/$id/drafts');

  // ---- 协作写作 ----
  static Future<List<dynamic>> writingList() async =>
      await ApiClient.get('/api/studio/writing');

  static Future<Map<String, dynamic>> writingGet(int id) async =>
      await ApiClient.get('/api/studio/writing/$id');

  static Future<Map<String, dynamic>> writingCreate(String topic,
          {String? genre, int? maxRounds}) async =>
      await ApiClient.post('/api/studio/writing', {
        'topic': topic,
        'genre': ?genre,
        'maxRounds': ?maxRounds,
      });

  static Future<void> writingDelete(int id) async =>
      await ApiClient.del('/api/studio/writing/$id');
}
