import '../core/api_client.dart';

/// 日程(schedule-agent 反代透传):事件 CRUD
class ScheduleApi {
  /// 按时间范围列出事件(from/to 为 UTC Instant)
  static Future<List<dynamic>> events(DateTime from, DateTime to) async {
    final f = from.toUtc().toIso8601String();
    final t = to.toUtc().toIso8601String();
    return await ApiClient.get('/api/schedule/v1/events?from=$f&to=$t');
  }

  static Future<Map<String, dynamic>> create(
      {required String title,
      required DateTime start,
      required DateTime end,
      String? priority,
      String? description}) async {
    return await ApiClient.post('/api/schedule/v1/events', {
      'title': title,
      'startTime': start.toUtc().toIso8601String(),
      'endTime': end.toUtc().toIso8601String(),
      'priority': ?priority,
      if ((description ?? '').isNotEmpty) 'description': description,
    });
  }

  static Future<void> delete(String eventId) async =>
      await ApiClient.del('/api/schedule/v1/events/$eventId');
}
