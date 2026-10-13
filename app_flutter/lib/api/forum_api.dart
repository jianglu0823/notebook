import '../core/api_client.dart';

class ForumApi {
  static Future<Map<String, dynamic>> posts(
      {String? category, int page = 0, int size = 20}) async {
    var url = '/api/world/forum/posts?page=$page&size=$size';
    if (category != null) url += '&category=$category';
    return await ApiClient.get(url);
  }

  static Future<Map<String, dynamic>> post(int id) async =>
      await ApiClient.get('/api/world/forum/posts/$id');

  static Future<Map<String, dynamic>> comments(int id,
          {int page = 0, int size = 20}) async =>
      await ApiClient.get('/api/world/forum/posts/$id/comments?page=$page&size=$size');

  static Future<List<dynamic>> categories() async =>
      await ApiClient.get('/api/world/forum/categories');

  static Future<void> like(int id, bool on) async => on
      ? await ApiClient.post('/api/world/forum/posts/$id/like', {})
      : await ApiClient.del('/api/world/forum/posts/$id/like');

  static Future<void> favorite(int id, bool on) async => on
      ? await ApiClient.post('/api/world/forum/posts/$id/favorite', {})
      : await ApiClient.del('/api/world/forum/posts/$id/favorite');

  static Future<Map<String, dynamic>> addComment(int id, String content) async =>
      await ApiClient.post('/api/world/forum/posts/$id/comments', {'content': content});
}
