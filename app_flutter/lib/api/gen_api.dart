import '../core/api_client.dart';

class GenApi {
  /// kind: SUMMARY / STUDY_GUIDE / FAQ
  static Future<Map<String, dynamic>> generate(int notebookId, String kind,
          {List<int>? noteIds}) async =>
      await ApiClient.post('/api/notebooks/$notebookId/docs/$kind',
          {'noteIds': ?noteIds});

  static Future<List<dynamic>> list(int notebookId) async =>
      await ApiClient.get('/api/notebooks/$notebookId/docs');

  static Future<Map<String, dynamic>> get(int notebookId, int id) async =>
      await ApiClient.get('/api/notebooks/$notebookId/docs/$id');
}

class PodcastApi {
  static Future<Map<String, dynamic>> generate(int notebookId) async =>
      await ApiClient.post('/api/notebooks/$notebookId/podcasts', {});

  static Future<List<dynamic>> list(int notebookId) async =>
      await ApiClient.get('/api/notebooks/$notebookId/podcasts');

  static Future<Map<String, dynamic>> get(int notebookId, int id) async =>
      await ApiClient.get('/api/notebooks/$notebookId/podcasts/$id');
}
