import 'package:flutter/material.dart';
import '../../api/companion_api.dart';

/// 伴侣记忆列表,左滑删除
class CompanionMemoriesPage extends StatefulWidget {
  const CompanionMemoriesPage({super.key, required this.companionId});
  final int companionId;

  @override
  State<CompanionMemoriesPage> createState() => _CompanionMemoriesPageState();
}

class _CompanionMemoriesPageState extends State<CompanionMemoriesPage> {
  List<dynamic>? _list;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final l = await CompanionApi.memories(widget.companionId);
      if (mounted) setState(() => _list = l);
    } catch (_) {
      if (mounted) setState(() => _list = []);
    }
  }

  Future<void> _delete(Map<String, dynamic> m) async {
    try {
      await CompanionApi.deleteMemory(widget.companionId, m['id'] as int);
      setState(() => _list!.remove(m));
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('删除失败')));
        _load();
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('记忆')),
      body: _list == null
          ? const Center(child: CircularProgressIndicator())
          : _list!.isEmpty
              ? const Center(child: Text('还没有记忆'))
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView.builder(
                    itemCount: _list!.length,
                    itemBuilder: (_, i) {
                      final m = _list![i];
                      return Dismissible(
                        key: ValueKey(m['id']),
                        direction: DismissDirection.endToStart,
                        background: Container(
                          color: Colors.red,
                          alignment: Alignment.centerRight,
                          padding: const EdgeInsets.only(right: 16),
                          child: const Icon(Icons.delete, color: Colors.white),
                        ),
                        onDismissed: (_) => _delete(m),
                        child: ListTile(
                          leading: const Icon(Icons.psychology_outlined),
                          title: Text(m['content'] as String? ?? ''),
                          subtitle: Text(m['createdAt'] as String? ?? ''),
                        ),
                      );
                    },
                  ),
                ),
    );
  }
}
