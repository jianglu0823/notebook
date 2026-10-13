import '../core/api_client.dart';

/// 新闻收集:提交方向异步收集成笔记
class NewsApi {
  static Future<List<dynamic>> list() async => await ApiClient.get('/api/news');

  static Future<Map<String, dynamic>> get(int id) async =>
      await ApiClient.get('/api/news/$id');

  static Future<Map<String, dynamic>> submit(String topic) async =>
      await ApiClient.post('/api/news', {'topic': topic});
}
