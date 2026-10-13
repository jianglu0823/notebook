import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:web_socket_channel/web_socket_channel.dart';

// 用最小成本(qwen-turbo)验证 WS 协议:连 → ready → 发 chat.text → 收 delta/done
// 用法: dart run tool/ws_probe.dart <token> <companionId>
void main(List<String> args) async {
  final token = args[0];
  final companionId = int.parse(args[1]);
  final uri = Uri.parse(
      'ws://localhost:8082/api/ws/companion?token=${Uri.encodeComponent(token)}');
  final ws = WebSocketChannel.connect(uri);
  final done = Completer<void>();

  ws.stream.listen((data) {
    if (data is! String) {
      stdout.writeln('[binary] ${data.length} bytes');
      return;
    }
    final f = jsonDecode(data) as Map<String, dynamic>;
    final kind = f['kind'];
    if (kind == 'chat.delta') {
      stdout.write(f['delta'] ?? '');
    } else {
      stdout.writeln('\n[$kind] ${const JsonEncoder().convert(f)}');
    }
    if (kind == 'chat.done' || kind == 'chat.error' || kind == 'error') {
      if (!done.isCompleted) done.complete();
    }
  }, onDone: () {
    stdout.writeln('\n[closed by server]');
    if (!done.isCompleted) done.complete();
  }, onError: (e) {
    stdout.writeln('\n[error] $e');
    if (!done.isCompleted) done.complete();
  });

  await Future.delayed(const Duration(seconds: 1));
  ws.sink.add(jsonEncode({
    'kind': 'chat.text',
    'companionId': companionId,
    'content': '用一句话介绍你自己',
    'model': 'qwen-turbo',
  }));

  await done.future.timeout(const Duration(seconds: 90),
      onTimeout: () => stdout.writeln('\n[timeout 90s]'));
  await ws.sink.close();
  exit(0);
}
