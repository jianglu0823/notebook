import '../core/api_client.dart';

class NoteApi {
  static Future<List<dynamic>> all() async => await ApiClient.get('/api/notes');

  static Future<List<dynamic>> list(int notebookId) async =>
      await ApiClient.get('/api/notebooks/$notebookId/notes');

  static Future<Map<String, dynamic>> get(int notebookId, int noteId) async =>
      await ApiClient.get('/api/notebooks/$notebookId/notes/$noteId');

  static Future<Map<String, dynamic>> create(int notebookId, String title,
          [String type = 'MARKDOWN']) async =>
      await ApiClient.post(
          '/api/notebooks/$notebookId/notes', {'title': title, 'type': type});

  static Future<Map<String, dynamic>> updateBody(
          int notebookId, int noteId, String title, String content) async =>
      await ApiClient.put('/api/notebooks/$notebookId/notes/$noteId',
          {'title': title, 'content': content});

  static Future<void> delete(int notebookId, int noteId) async =>
      await ApiClient.del('/api/notebooks/$notebookId/notes/$noteId');

  static Future<List<dynamic>> sources(int notebookId, int noteId) async =>
      await ApiClient.get('/api/notebooks/$notebookId/notes/$noteId/sources');

  /// 上传文件到笔记,异步摄取,返回 PENDING Source
  static Future<Map<String, dynamic>> upload(int notebookId, int noteId,
          List<int> bytes, String filename) async =>
      await ApiClient.upload(
          '/api/notebooks/$notebookId/notes/$noteId/sources',
          'file',
          bytes,
          filename);
}
