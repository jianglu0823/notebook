import 'package:flutter/material.dart';
import '../../api/notebook_api.dart';
import 'notebook_detail_page.dart';

class NotebookListPage extends StatefulWidget {
  const NotebookListPage({super.key});

  @override
  State<NotebookListPage> createState() => _NotebookListPageState();
}

class _NotebookListPageState extends State<NotebookListPage> {
  List<dynamic>? _list;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final l = await NotebookApi.list();
      if (mounted) setState(() { _list = l; _error = null; });
    } catch (e) {
      if (mounted) setState(() => _error = '加载失败');
    }
  }

  Future<void> _edit([Map<String, dynamic>? nb]) async {
    final nameCtrl = TextEditingController(text: nb?['name'] as String? ?? '');
    final descCtrl =
        TextEditingController(text: nb?['description'] as String? ?? '');
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(nb == null ? '新建笔记本' : '编辑笔记本'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
                controller: nameCtrl,
                autofocus: true,
                decoration: const InputDecoration(hintText: '名称')),
            TextField(
                controller: descCtrl,
                decoration: const InputDecoration(hintText: '描述(可选)')),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('保存')),
        ],
      ),
    );
    if (ok != true || nameCtrl.text.trim().isEmpty) return;
    try {
      if (nb == null) {
        await NotebookApi.create(nameCtrl.text.trim(), descCtrl.text.trim());
      } else {
        await NotebookApi.update(
            nb['id'] as int, nameCtrl.text.trim(), descCtrl.text.trim());
      }
      _load();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('保存失败')));
      }
    }
  }

  Future<void> _delete(Map<String, dynamic> nb) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('删除「${nb['name']}」?'),
        content: const Text('笔记本及其笔记、源文件将一并删除'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('删除')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await NotebookApi.delete(nb['id'] as int);
      _load();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('删除失败')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('笔记本')),
      floatingActionButton:
          FloatingActionButton(onPressed: _edit, child: const Icon(Icons.add)),
      body: _error != null
          ? Center(child: Text(_error!))
          : _list == null
              ? const Center(child: CircularProgressIndicator())
              : _list!.isEmpty
                  ? const Center(child: Text('还没有笔记本,点右下角创建一个吧'))
                  : RefreshIndicator(
                      onRefresh: _load,
                      child: ListView.builder(
                        itemCount: _list!.length,
                        itemBuilder: (_, i) {
                          final nb = _list![i];
                          return ListTile(
                            leading: const CircleAvatar(
                                child: Icon(Icons.menu_book)),
                            title: Text(nb['name'] as String? ?? ''),
                            subtitle:
                                Text(nb['description'] as String? ?? ''),
                            trailing: PopupMenuButton<String>(
                              onSelected: (v) =>
                                  v == 'edit' ? _edit(nb) : _delete(nb),
                              itemBuilder: (_) => const [
                                PopupMenuItem(value: 'edit', child: Text('编辑')),
                                PopupMenuItem(value: 'delete', child: Text('删除')),
                              ],
                            ),
                            onTap: () async {
                              await Navigator.of(context).push(
                                  MaterialPageRoute(
                                      builder: (_) =>
                                          NotebookDetailPage(notebook: nb)));
                              _load();
                            },
                          );
                        },
                      ),
                    ),
    );
  }
}
