import 'dart:async';
import 'dart:convert';
import 'package:http/http.dart' as http;
import 'auth.dart';
import 'config.dart';

class SseEvent {
  SseEvent(this.event, this.data);
  final String event;
  final String data;
}

/// 手写 SSE 客户端:POST 后按 event:/data: 状态机逐事件吐出
Stream<SseEvent> ssePost(String path, Object body) async* {
  final req = http.Request('POST', Uri.parse('$apiBase$path'));
  req.headers.addAll(Auth.headers);
  req.headers['Accept'] = 'text/event-stream';
  req.body = jsonEncode(body);
  final resp = await http.Client().send(req);
  if (resp.statusCode >= 400) {
    throw Exception('SSE HTTP ${resp.statusCode}');
  }

  String event = '';
  final dataBuf = StringBuffer();
  await for (final line
      in resp.stream.transform(utf8.decoder).transform(const LineSplitter())) {
    if (line.isEmpty) {
      if (event.isNotEmpty || dataBuf.isNotEmpty) {
        yield SseEvent(event, dataBuf.toString());
      }
      event = '';
      dataBuf.clear();
    } else if (line.startsWith('event:')) {
      event = line.substring(6).trim();
    } else if (line.startsWith('data:')) {
      // Spring SseEmitter 原样写数据,不补空格:data: 后全部保留(否则吃掉 delta 前导空格)
      if (dataBuf.isNotEmpty) dataBuf.write('\n');
      dataBuf.write(line.substring(5));
    }
  }
  if (event.isNotEmpty || dataBuf.isNotEmpty) {
    yield SseEvent(event, dataBuf.toString());
  }
}
