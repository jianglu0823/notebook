import '../core/api_client.dart';

class NotebookApi {
  static Future<List<dynamic>> list() async => await ApiClient.get('/api/notebooks');

  static Future<Map<String, dynamic>> create(String name,
          [String? description]) async =>
      await ApiClient.post(
          '/api/notebooks', {'name': name, 'description': description});

  static Future<Map<String, dynamic>> update(int id, String name,
          [String? description]) async =>
      await ApiClient.put(
          '/api/notebooks/$id', {'name': name, 'description': description});

  static Future<void> delete(int id) async =>
      await ApiClient.del('/api/notebooks/$id');

  static Future<List<dynamic>> sources(int id) async =>
      await ApiClient.get('/api/notebooks/$id/sources');
}
