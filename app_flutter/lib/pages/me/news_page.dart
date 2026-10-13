import 'package:flutter/material.dart';
import '../../api/news_api.dart';

/// 新闻收集:提交方向,异步收集成笔记
class NewsPage extends StatefulWidget {
  const NewsPage({super.key});

  @override
  State<NewsPage> createState() => _NewsPageState();
}

class _NewsPageState extends State<NewsPage> {
  List<dynamic>? _list;
  final _ctrl = TextEditingController();
  bool _submitting = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final l = await NewsApi.list();
      if (mounted) setState(() => _list = l);
    } catch (_) {
      if (mounted) setState(() => _list = []);
    }
  }

  Future<void> _submit() async {
    final topic = _ctrl.text.trim();
    if (topic.isEmpty || _submitting) return;
    setState(() => _submitting = true);
    try {
      await NewsApi.submit(topic);
      _ctrl.clear();
      _load();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('提交失败')));
      }
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = _list;
    return Scaffold(
      appBar: AppBar(title: const Text('新闻收集')),
      body: Column(children: [
        Padding(
          padding: const EdgeInsets.all(12),
          child: Row(children: [
            Expanded(
              child: TextField(
                controller: _ctrl,
                decoration: const InputDecoration(
                    hintText: '输入关注方向,如:AI 行业动态',
                    border: OutlineInputBorder(),
                    isDense: true),
              ),
            ),
            const SizedBox(width: 8),
            FilledButton(
                onPressed: _submitting ? null : _submit,
                child: const Text('收集')),
          ]),
        ),
        const Divider(height: 1),
        Expanded(
          child: l == null
              ? const Center(child: CircularProgressIndicator())
              : l.isEmpty
                  ? const Center(child: Text('还没有收集任务'))
                  : RefreshIndicator(
                      onRefresh: _load,
                      child: ListView(children: [
                        for (final j in l)
                          ListTile(
                            leading: _statusIcon(j['status'] as String? ?? ''),
                            title: Text(j['topic'] as String? ?? ''),
                            subtitle: Text(
                                '${_label(j['status'] as String? ?? '')} · ${j['createdAt']?.toString().substring(0, 16) ?? ''}'),
                          ),
                      ]),
                    ),
        ),
      ]),
    );
  }

  Widget _statusIcon(String s) => switch (s) {
        'DONE' => const Icon(Icons.check_circle, color: Colors.green),
        'FAILED' => const Icon(Icons.error, color: Colors.red),
        _ => const SizedBox(
            width: 20,
            height: 20,
            child: CircularProgressIndicator(strokeWidth: 2)),
      };

  String _label(String s) => switch (s) {
        'PENDING' => '排队中',
        'GENERATING' => '收集中',
        'DONE' => '已生成笔记',
        'FAILED' => '失败',
        _ => s,
      };
}
