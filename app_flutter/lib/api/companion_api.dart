import '../core/api_client.dart';

class CompanionApi {
  static Future<List<dynamic>> list() async => await ApiClient.get('/api/companion');

  static Future<Map<String, dynamic>> get(int id) async =>
      await ApiClient.get('/api/companion/$id');

  static Future<Map<String, dynamic>> create(String name) async =>
      await ApiClient.post('/api/companion', {'name': name});

  static Future<List<dynamic>> templates() async =>
      await ApiClient.get('/api/companion/templates');

  static Future<Map<String, dynamic>> createFromTemplate(String key) async =>
      await ApiClient.post('/api/companion/templates/$key', {});

  static Future<List<dynamic>> models() async =>
      await ApiClient.get('/api/companion/models');

  /// 部分更新:只传要改的字段
  static Future<Map<String, dynamic>> patch(
          int id, Map<String, dynamic> fields) async =>
      await ApiClient.patch('/api/companion/$id', fields);

  static Future<void> delete(int id) async =>
      await ApiClient.del('/api/companion/$id');

  static Future<List<dynamic>> messages(int companionId,
      {int limit = 50, int? beforeId}) async {
    var url = '/api/companion/$companionId/messages?limit=$limit';
    if (beforeId != null) url += '&beforeId=$beforeId';
    return await ApiClient.get(url);
  }

  /// action 目前后端只实现 delete
  static Future<void> deleteMessage(int companionId, int messageId) async =>
      await ApiClient.patch(
          '/api/companion/$companionId/messages/$messageId',
          {'action': 'delete'});

  static Future<List<dynamic>> memories(int companionId) async =>
      await ApiClient.get('/api/companion/$companionId/memories');

  static Future<void> deleteMemory(int companionId, int memoryId) async =>
      await ApiClient.del('/api/companion/$companionId/memories/$memoryId');

  static Future<Map<String, dynamic>> intimacy(int companionId) async =>
      await ApiClient.get('/api/companion/$companionId/intimacy');
}
