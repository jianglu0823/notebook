import '../core/api_client.dart';
import '../core/sse.dart';

class QaApi {
  /// SSE 流式问答:session → citations → delta* → done
  static Stream<SseEvent> ask(int notebookId, String question,
          {String? sessionId, List<int>? noteIds}) =>
      ssePost('/api/notebooks/$notebookId/qa', {
        'question': question,
        'sessionId': ?sessionId,
        'noteIds': ?noteIds,
      });

  static Future<List<dynamic>> history(int notebookId, String sessionId) async =>
      await ApiClient.get('/api/notebooks/$notebookId/qa/history?sessionId=$sessionId');
}
