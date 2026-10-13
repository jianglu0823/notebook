import '../core/api_client.dart';

/// 小世界只读接口
class WorldApi {
  static Future<List<dynamic>> agents() async =>
      await ApiClient.get('/api/world/agents');

  static Future<Map<String, dynamic>> gallery(
          {String category = 'image', int page = 0, int size = 24}) async =>
      await ApiClient.get(
          '/api/world/gallery?category=$category&page=$page&size=$size');

  static Future<List<dynamic>> books() async =>
      await ApiClient.get('/api/world/books');

  static Future<Map<String, dynamic>> book(int agentId) async =>
      await ApiClient.get('/api/world/books/$agentId');

  static Future<List<dynamic>> feed() async =>
      await ApiClient.get('/api/world/feed');

  /// 具名地点地图:世界尺寸 + 地点清单(key/name/emoji/x/y)
  static Future<Map<String, dynamic>> places() async =>
      await ApiClient.get('/api/world/places');

  /// 与居民的私聊历史(按 owner 隔离)
  static Future<List<dynamic>> agentChatHistory(int agentId) async =>
      await ApiClient.get('/api/world/agents/$agentId/chat');

  /// 给居民发消息,返回居民的回复(LLM)
  static Future<Map<String, dynamic>> agentChatSend(
      int agentId, String message) async =>
      await ApiClient.post(
          '/api/world/agents/$agentId/chat', {'message': message});

  // ---- 经济面板 ----
  static Future<Map<String, dynamic>> economySummary() async =>
      await ApiClient.get('/api/world/economy/summary');

  static Future<Map<String, dynamic>> economyPlaces() async =>
      await ApiClient.get('/api/world/economy/places');

  static Future<List<dynamic>> richList() async =>
      await ApiClient.get('/api/world/economy/rich');

  static Future<List<dynamic>> stocks() async =>
      await ApiClient.get('/api/world/stocks');
}
