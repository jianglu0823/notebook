import 'package:flutter/material.dart';
import '../../api/note_api.dart';
import 'note_edit_page.dart';

/// 全部笔记(跨笔记本卡片流)
class AllNotesPage extends StatefulWidget {
  const AllNotesPage({super.key});

  @override
  State<AllNotesPage> createState() => _AllNotesPageState();
}

class _AllNotesPageState extends State<AllNotesPage> {
  List<dynamic>? _list;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final l = await NoteApi.all();
      if (mounted) setState(() => _list = l);
    } catch (_) {
      if (mounted) setState(() => _list = []);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('全部笔记')),
      body: _list == null
          ? const Center(child: CircularProgressIndicator())
          : _list!.isEmpty
              ? const Center(child: Text('还没有笔记,去笔记本里创建吧'))
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView.builder(
                    padding: const EdgeInsets.all(8),
                    itemCount: _list!.length,
                    itemBuilder: (_, i) {
                      final n = _list![i];
                      return Card(
                        child: ListTile(
                          title: Text(n['title'] as String? ?? '无标题'),
                          subtitle: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              const SizedBox(height: 4),
                              Text(n['preview'] as String? ?? '',
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis),
                              const SizedBox(height: 4),
                              Text(n['notebookName'] as String? ?? '',
                                  style: TextStyle(
                                      fontSize: 12,
                                      color: Theme.of(context)
                                          .colorScheme
                                          .primary)),
                            ],
                          ),
                          onTap: () async {
                            await Navigator.of(context).push(MaterialPageRoute(
                                builder: (_) => NoteEditPage(
                                    notebookId: n['notebookId'] as int,
                                    noteId: n['id'] as int,
                                    initialTitle:
                                        n['title'] as String? ?? '')));
                            _load();
                          },
                        ),
                      );
                    },
                  ),
                ),
    );
  }
}
