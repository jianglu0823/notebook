import 'package:flutter/material.dart';
import '../../api/companion_api.dart';
import '../../core/api_client.dart';
import '../../core/auth.dart';
import 'chat_page.dart';
import '../login_page.dart';

class CompanionListPage extends StatefulWidget {
  const CompanionListPage({super.key});

  @override
  State<CompanionListPage> createState() => _CompanionListPageState();
}

class _CompanionListPageState extends State<CompanionListPage> {
  List<dynamic>? _list;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final l = await CompanionApi.list();
      if (mounted) setState(() => _list = l);
    } on ApiException catch (e) {
      if (e.status == 401 || e.status == 403) return;
      if (mounted) setState(() => _error = '加载失败(${e.status})');
    } catch (_) {
      if (mounted) setState(() => _error = '网络错误');
    }
  }

  Future<void> _logout() async {
    await Auth.clear();
    if (!mounted) return;
    Navigator.of(context, rootNavigator: true).pushAndRemoveUntil(
        MaterialPageRoute(builder: (_) => const LoginPage()), (_) => false);
  }

  void _create() {
    showModalBottomSheet(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          ListTile(
            leading: const Icon(Icons.edit_outlined),
            title: const Text('自定义创建'),
            subtitle: const Text('只起个名字,慢慢养成'),
            onTap: () {
              Navigator.pop(ctx);
              _createCustom();
            },
          ),
          ListTile(
            leading: const Icon(Icons.grid_view_outlined),
            title: const Text('从模板创建'),
            subtitle: const Text('挑一个现成的人设'),
            onTap: () {
              Navigator.pop(ctx);
              _pickTemplate();
            },
          ),
        ]),
      ),
    );
  }

  Future<void> _createCustom() async {
    final ctrl = TextEditingController();
    final name = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('新建伴侣'),
        content: TextField(
            controller: ctrl,
            autofocus: true,
            decoration: const InputDecoration(hintText: '给 TA 起个名字')),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, ctrl.text.trim()),
              child: const Text('创建')),
        ],
      ),
    );
    if (name == null || name.isEmpty) return;
    try {
      final c = await CompanionApi.create(name);
      await _openCreated(c, name);
    } catch (_) {
      _toast('创建失败');
    }
  }

  Future<void> _pickTemplate() async {
    List<dynamic> templates;
    try {
      templates = await CompanionApi.templates();
    } catch (_) {
      _toast('模板加载失败');
      return;
    }
    if (!mounted) return;
    final key = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => DraggableScrollableSheet(
        initialChildSize: 0.7,
        expand: false,
        builder: (_, scroll) => GridView.builder(
          controller: scroll,
          padding: const EdgeInsets.all(16),
          gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 2,
              childAspectRatio: 1.1,
              crossAxisSpacing: 12,
              mainAxisSpacing: 12),
          itemCount: templates.length,
          itemBuilder: (_, i) {
            final t = templates[i];
            final tags =
                (t['personalityTags'] as List?)?.join(' · ') ?? '';
            return Card(
              clipBehavior: Clip.antiAlias,
              child: InkWell(
                onTap: () => Navigator.pop(ctx, t['templateKey'] as String),
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(t['name'] as String? ?? '',
                          style: const TextStyle(
                              fontSize: 16, fontWeight: FontWeight.bold)),
                      const SizedBox(height: 4),
                      Text(tags,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                              fontSize: 12, color: Colors.grey)),
                      const SizedBox(height: 4),
                      Expanded(
                        child: Text(t['bio'] as String? ?? '',
                            maxLines: 3,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(fontSize: 12)),
                      ),
                    ],
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
    if (key == null) return;
    try {
      final c = await CompanionApi.createFromTemplate(key);
      await _openCreated(c, c['name'] as String? ?? '');
    } catch (_) {
      _toast('创建失败');
    }
  }

  Future<void> _openCreated(Map<String, dynamic> c, String name) async {
    await _load();
    if (!mounted) return;
    Navigator.of(context).push(MaterialPageRoute(
        builder: (_) => ChatPage(
            companionId: c['id'] as int,
            companionName: c['name'] as String? ?? name)));
  }

  void _toast(String msg) {
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(msg)));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('AI 伴侣 · ${Auth.username ?? ""}'),
        actions: [
          IconButton(icon: const Icon(Icons.logout), onPressed: _logout),
        ],
      ),
      floatingActionButton:
          FloatingActionButton(onPressed: _create, child: const Icon(Icons.add)),
      body: _error != null
          ? Center(child: Text(_error!))
          : _list == null
              ? const Center(child: CircularProgressIndicator())
              : _list!.isEmpty
                  ? const Center(child: Text('还没有伴侣,点右下角创建一个吧'))
                  : RefreshIndicator(
                      onRefresh: _load,
                      child: ListView.builder(
                        itemCount: _list!.length,
                        itemBuilder: (_, i) {
                          final c = _list![i];
                          final tags = (c['personalityTags'] as List?)?.join(' · ') ?? '';
                          return ListTile(
                            leading: CircleAvatar(
                                child: Text(
                                    (c['name'] as String? ?? '?').characters.first)),
                            title: Text(c['name'] as String? ?? ''),
                            subtitle: Text(
                                '亲密度 ${c['intimacyScore'] ?? 0}${tags.isEmpty ? '' : ' · $tags'}'),
                            trailing: const Icon(Icons.chevron_right),
                            onTap: () async {
                              await Navigator.of(context).push(MaterialPageRoute(
                                  builder: (_) => ChatPage(
                                      companionId: c['id'] as int,
                                      companionName:
                                          c['name'] as String? ?? '')));
                              _load();
                            },
                          );
                        },
                      ),
                    ),
    );
  }
}
