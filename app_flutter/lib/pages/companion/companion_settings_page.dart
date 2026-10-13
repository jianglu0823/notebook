import 'package:flutter/material.dart';
import '../../api/companion_api.dart';
import 'companion_memories_page.dart';

/// 伴侣设置:重命名 / 模型切换 / 记忆入口 / 删除
class CompanionSettingsPage extends StatefulWidget {
  const CompanionSettingsPage({super.key, required this.companionId});
  final int companionId;

  @override
  State<CompanionSettingsPage> createState() => _CompanionSettingsPageState();
}

class _CompanionSettingsPageState extends State<CompanionSettingsPage> {
  Map<String, dynamic>? _c;
  List<dynamic> _models = [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final c = await CompanionApi.get(widget.companionId);
      final m = await CompanionApi.models();
      if (mounted) {
        setState(() {
          _c = c;
          _models = m;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _c ??= {});
    }
  }

  Future<void> _rename() async {
    final ctrl = TextEditingController(text: _c?['name'] as String? ?? '');
    final name = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('重命名'),
        content: TextField(controller: ctrl, autofocus: true),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, ctrl.text.trim()),
              child: const Text('保存')),
        ],
      ),
    );
    if (name == null || name.isEmpty) return;
    try {
      final c = await CompanionApi.patch(widget.companionId, {'name': name});
      if (mounted) setState(() => _c = c);
    } catch (_) {
      _toast('保存失败');
    }
  }

  Future<void> _switchModel(String name) async {
    try {
      final c =
          await CompanionApi.patch(widget.companionId, {'defaultModel': name});
      if (mounted) setState(() => _c = c);
    } catch (_) {
      _toast('切换失败');
    }
  }

  Future<void> _delete() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除伴侣?'),
        content: const Text('删除后聊天记录与记忆将无法恢复'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          FilledButton(
              style: FilledButton.styleFrom(backgroundColor: Colors.red),
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('删除')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await CompanionApi.delete(widget.companionId);
      if (mounted) Navigator.of(context).pop('deleted');
    } catch (_) {
      _toast('删除失败');
    }
  }

  void _toast(String msg) {
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(msg)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = _c;
    return Scaffold(
      appBar: AppBar(title: const Text('伴侣设置')),
      body: c == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              children: [
                ListTile(
                  leading: const Icon(Icons.badge_outlined),
                  title: const Text('名字'),
                  subtitle: Text(c['name'] as String? ?? ''),
                  trailing: const Icon(Icons.edit_outlined),
                  onTap: _rename,
                ),
                ListTile(
                  leading: const Icon(Icons.smart_toy_outlined),
                  title: const Text('模型'),
                  subtitle: Text(_modelLabel(c['defaultModel'] as String?)),
                  onTap: _models.isEmpty ? null : _pickModel,
                ),
                ListTile(
                  leading: const Icon(Icons.psychology_outlined),
                  title: const Text('记忆'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => Navigator.of(context).push(MaterialPageRoute(
                      builder: (_) => CompanionMemoriesPage(
                          companionId: widget.companionId))),
                ),
                const Divider(),
                ListTile(
                  leading: const Icon(Icons.delete_outline, color: Colors.red),
                  title: const Text('删除伴侣',
                      style: TextStyle(color: Colors.red)),
                  onTap: _delete,
                ),
              ],
            ),
    );
  }

  String _modelLabel(String? name) {
    if (name == null) return '默认';
    final m = _models.where((x) => x['name'] == name).firstOrNull;
    return m != null ? '${m['label']} ($name)' : name;
  }

  void _pickModel() {
    showModalBottomSheet(
      context: context,
      builder: (ctx) => ListView(
        shrinkWrap: true,
        children: [
          for (final m in _models)
            ListTile(
              title: Text(m['label'] as String? ?? ''),
              subtitle: Text(
                  '输入 ¥${m['inputPer1k']}/1k · 输出 ¥${m['outputPer1k']}/1k'),
              trailing: m['name'] == _c?['defaultModel']
                  ? const Icon(Icons.check, color: Colors.green)
                  : null,
              onTap: () {
                Navigator.pop(ctx);
                _switchModel(m['name'] as String);
              },
            ),
        ],
      ),
    );
  }
}
