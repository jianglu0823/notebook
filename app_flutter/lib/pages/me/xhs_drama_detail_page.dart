import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import '../../api/studio_api.dart';

/// 短剧详情:候选脚本(人物设定/旁白/分镜) + 渲染状态,只读展示
class XhsDramaDetailPage extends StatefulWidget {
  const XhsDramaDetailPage({super.key, required this.projectId});
  final int projectId;

  @override
  State<XhsDramaDetailPage> createState() => _XhsDramaDetailPageState();
}

class _XhsDramaDetailPageState extends State<XhsDramaDetailPage> {
  Map<String, dynamic>? _p;
  List<dynamic> _drafts = [];
  Timer? _timer;

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
      final p = await StudioApi.xhsGet(widget.projectId);
      final d = await StudioApi.dramaDrafts(widget.projectId);
      if (!mounted) return;
      setState(() {
        _p = p;
        _drafts = d;
      });
      // 脚本还在生成时轮询
      final generating = d.isEmpty && p['errorMsg'] == null;
      final rendering = p['dramaStatus'] == 'RENDERING';
      if (generating || rendering) {
        _timer ??= Timer.periodic(const Duration(seconds: 3), (_) => _load());
      } else {
        _timer?.cancel();
        _timer = null;
      }
    } catch (_) {}
  }

  List<String> _scenes(Map<String, dynamic> draft) {
    try {
      final list = jsonDecode(draft['scenes'] as String? ?? '[]') as List;
      return [
        for (final s in list)
          s is Map
              ? '${s['desc'] ?? ''} ${s['dialogue'] ?? ''}'.trim()
              : '$s'
      ];
    } catch (_) {
      return [];
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = _p;
    return Scaffold(
      appBar: AppBar(title: const Text('短剧项目')),
      body: p == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(padding: const EdgeInsets.all(16), children: [
              Text(p['genre'] != null ? '类型:${p['genre']}' : '',
                  style: const TextStyle(color: Colors.grey)),
              const SizedBox(height: 8),
              if (p['errorMsg'] != null)
                Text('生成失败:${p['errorMsg']}',
                    style: const TextStyle(color: Colors.red)),
              if (p['dramaStatus'] == 'RENDERING')
                Text(
                    '渲染中,已完成 ${p['dramaSegmentsDone'] ?? 0}/6 段…'),
              if (p['dramaStatus'] == 'DONE')
                const ListTile(
                    leading: Icon(Icons.check_circle, color: Colors.green),
                    title: Text('成片已生成')),
              const SizedBox(height: 8),
              if (_drafts.isEmpty && p['errorMsg'] == null)
                const Row(children: [
                  SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2)),
                  SizedBox(width: 8),
                  Text('脚本生成中…'),
                ]),
              for (final d in _drafts) _draftCard(d, p),
            ]),
    );
  }

  Widget _draftCard(Map<String, dynamic> d, Map<String, dynamic> p) {
    final chosen = p['chosenDraftId'] == d['id'];
    final scenes = _scenes(d);
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: ExpansionTile(
        title: Text('第 ${d['versionNo']} 版${chosen ? ' · 已选中' : ''}'),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        children: [
          if (d['characterProfile'] != null) ...[
            const Text('人物设定',
                style: TextStyle(fontWeight: FontWeight.bold)),
            Text(d['characterProfile'] as String),
            const SizedBox(height: 8),
          ],
          if (d['narration'] != null) ...[
            const Text('旁白', style: TextStyle(fontWeight: FontWeight.bold)),
            Text(d['narration'] as String),
            const SizedBox(height: 8),
          ],
          if (scenes.isNotEmpty) ...[
            const Text('分镜', style: TextStyle(fontWeight: FontWeight.bold)),
            for (var i = 0; i < scenes.length; i++)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text('${i + 1}. ${scenes[i]}'),
              ),
          ],
        ],
      ),
    );
  }
}
