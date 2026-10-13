import 'package:flutter/material.dart';
import '../../api/studio_api.dart';
import 'xhs_detail_page.dart';
import 'xhs_drama_detail_page.dart';

/// 小红书项目列表(drama=true 时复用为短剧列表)
class XhsListPage extends StatefulWidget {
  const XhsListPage({super.key, this.drama = false});
  final bool drama;

  @override
  State<XhsListPage> createState() => _XhsListPageState();
}

class _XhsListPageState extends State<XhsListPage> {
  List<dynamic>? _list;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final l =
          widget.drama ? await StudioApi.dramaList() : await StudioApi.xhsList();
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
        title: const Text('新建小红书项目'),
        content: TextField(
            controller: ctrl,
            autofocus: true,
            decoration: const InputDecoration(
                labelText: '创作方向', hintText: '如:秋季护肤好物分享')),
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
      final p = await StudioApi.xhsCreate(ctrl.text.trim());
      if (!mounted) return;
      Navigator.of(context)
          .push(MaterialPageRoute(
              builder: (_) => XhsDetailPage(projectId: p['id'] as int)))
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
      appBar: AppBar(title: Text(widget.drama ? '短剧' : '小红书创作')),
      floatingActionButton: widget.drama
          ? null
          : FloatingActionButton(
              onPressed: _create, child: const Icon(Icons.add)),
      body: l == null
          ? const Center(child: CircularProgressIndicator())
          : l.isEmpty
              ? Center(
                  child: Text(widget.drama ? '还没有短剧项目' : '还没有小红书项目'))
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView(children: [
                    for (final p in l)
                      ListTile(
                        leading: Icon(widget.drama
                            ? Icons.movie_outlined
                            : Icons.edit_note),
                        title: Text(
                            _title(p),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis),
                        subtitle: Text(
                            '${_statusLabel(p['status'] as String? ?? '')} · ${_date(p['createdAt'])}'),
                        trailing: const Icon(Icons.chevron_right),
                        onTap: () => Navigator.of(context)
                            .push(MaterialPageRoute(
                                builder: (_) => widget.drama
                                    ? XhsDramaDetailPage(
                                        projectId: p['id'] as int)
                                    : XhsDetailPage(
                                        projectId: p['id'] as int)))
                            .then((_) => _load()),
                      ),
                  ]),
                ),
    );
  }

  String _title(Map<String, dynamic> p) =>
      (p['chosenTitle'] ?? p['direction'] ?? p['genre'] ?? '未命名') as String;

  String _date(dynamic v) => v?.toString().substring(0, 10) ?? '';

  static String _statusLabel(String s) => switch (s) {
        'NEW' => '已创建',
        'TITLES_DONE' => '标题已生成',
        'RESEARCH_DONE' => '素材已收集',
        'COPY_DONE' => '文案已生成',
        'IMAGES_DONE' => '配图已生成',
        'VIDEO_DONE' => '视频已生成',
        'DRAMA' => '短剧',
        _ => s,
      };
}
