import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import '../../api/studio_api.dart';

/// 协作写作围观:轮询 events 时间线(作者⇄核查⇄主编),DONE 后展示终稿
class WritingDetailPage extends StatefulWidget {
  const WritingDetailPage({super.key, required this.projectId});
  final int projectId;

  @override
  State<WritingDetailPage> createState() => _WritingDetailPageState();
}

class _WritingDetailPageState extends State<WritingDetailPage> {
  Map<String, dynamic>? _p;
  Timer? _timer;

  static const _roleIcon = {
    'author': '✒️',
    'checker': '🔎',
    'editor': '🧑‍⚖️',
    'system': '⚙️',
  };

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final p = await StudioApi.writingGet(widget.projectId);
      if (!mounted) return;
      setState(() => _p = p);
      final running = p['status'] == 'PENDING' || p['status'] == 'RUNNING';
      if (running) {
        _timer ??= Timer.periodic(const Duration(seconds: 3), (_) => _load());
      } else {
        _timer?.cancel();
        _timer = null;
      }
    } catch (_) {}
  }

  List<dynamic> _events() {
    try {
      return jsonDecode(_p?['events'] as String? ?? '[]') as List;
    } catch (_) {
      return [];
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = _p;
    final events = _events();
    return Scaffold(
      appBar: AppBar(title: Text(p?['topic'] as String? ?? '协作写作')),
      body: p == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(padding: const EdgeInsets.all(16), children: [
              Row(children: [
                _chip(p['status'] as String? ?? ''),
                const SizedBox(width: 8),
                if (p['inputTokens'] != null)
                  Text(
                      'tokens ${p['inputTokens']}+${p['outputTokens']} · ¥${p['costRmb'] ?? 0}',
                      style:
                          const TextStyle(fontSize: 12, color: Colors.grey)),
              ]),
              if (p['errorMsg'] != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text('失败:${p['errorMsg']}',
                      style: const TextStyle(color: Colors.red)),
                ),
              const Divider(),
              for (final e in events)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(_roleIcon[e['role']] ?? '•'),
                        const SizedBox(width: 8),
                        Expanded(child: Text(e['text'] as String? ?? '')),
                      ]),
                ),
              if (p['status'] == 'DONE' &&
                  (p['finalText'] as String? ?? '').isNotEmpty) ...[
                const Divider(),
                const Text('终稿',
                    style: TextStyle(fontWeight: FontWeight.bold)),
                const SizedBox(height: 8),
                MarkdownBody(data: p['finalText'] as String),
              ],
            ]),
    );
  }

  Widget _chip(String s) {
    final (label, color) = switch (s) {
      'PENDING' => ('排队中', Colors.grey),
      'RUNNING' => ('协作中', Colors.blue),
      'DONE' => ('已完成', Colors.green),
      'FAILED' => ('失败', Colors.red),
      _ => (s, Colors.grey),
    };
    return Chip(
        label: Text(label, style: TextStyle(color: color, fontSize: 12)),
        side: BorderSide(color: color),
        visualDensity: VisualDensity.compact);
  }
}
