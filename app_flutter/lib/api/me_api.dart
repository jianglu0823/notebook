import '../core/api_client.dart';

/// 「我的」附属只读接口:计费说明 + 访问日志
class MeApi {
  static Future<Map<String, dynamic>> pricing() async =>
      await ApiClient.get('/api/pricing');

  static Future<List<dynamic>> myLogs() async =>
      await ApiClient.get('/api/logs');
}
