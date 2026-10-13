import 'package:flutter/material.dart';
import '../../api/studio_api.dart';
import 'writing_detail_page.dart';

/// 协作写作项目列表
class WritingListPage extends StatefulWidget {
  const WritingListPage({super.key});

  @override
  State<WritingListPage> createState() => _WritingListPageState();
}

class _WritingListPageState extends State<WritingListPage> {
  List<dynamic>? _list;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final l = await StudioApi.writingList();
      if (mounted) setState(() => _list = l);
    } catch (_) {
      if (mounted) setState(() => _list = []);
    }
  }

  Future<void> _create() async {
    final ctrl = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('新建协作写作'),
        content: TextField(
            controller: ctrl,
            autofocus: true,
            decoration: const InputDecoration(
                labelText: '写作主题', hintText: '如:人工智能对教育的影响')),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('创建')),
        ],
      ),
    );
    if (ok != true || ctrl.text.trim().isEmpty) return;
    try {
      final p = await StudioApi.writingCreate(ctrl.text.trim());
      if (!mounted) return;
      Navigator.of(context)
          .push(MaterialPageRoute(
              builder: (_) => WritingDetailPage(projectId: p['id'] as int)))
          .then((_) => _load());
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('创建失败')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = _list;
    return Scaffold(
      appBar: AppBar(title: const Text('协作写作')),
      floatingActionButton:
          FloatingActionButton(onPressed: _create, child: const Icon(Icons.add)),
      body: l == null
          ? const Center(child: CircularProgressIndicator())
          : l.isEmpty
              ? const Center(child: Text('还没有写作项目'))
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView(children: [
                    for (final p in l)
                      ListTile(
                        leading: const Icon(Icons.groups_outlined),
                        title: Text(p['topic'] as String? ?? '',
                            maxLines: 1, overflow: TextOverflow.ellipsis),
                        subtitle: Text(
                            '${_statusLabel(p)} · ${p['createdAt']?.toString().substring(0, 10) ?? ''}'),
                        trailing: const Icon(Icons.chevron_right),
                        onTap: () => Navigator.of(context)
                            .push(MaterialPageRoute(
                                builder: (_) => WritingDetailPage(
                                    projectId: p['id'] as int)))
                            .then((_) => _load()),
                      ),
                  ]),
                ),
    );
  }

  String _statusLabel(Map<String, dynamic> p) {
    final s = p['status'] as String? ?? '';
    return switch (s) {
      'PENDING' => '排队中',
      'RUNNING' => '协作中',
      'DONE' => (p['approved'] == true) ? '已通过' : '已完成',
      'FAILED' => '失败',
      _ => s,
    };
  }
}
