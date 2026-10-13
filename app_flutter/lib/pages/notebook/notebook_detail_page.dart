import 'dart:async';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import '../../api/note_api.dart';
import '../../api/notebook_api.dart';
import '../note/note_edit_page.dart';
import 'gen_tab.dart';
import 'qa_page.dart';

/// 笔记本详情:笔记 / 问答 / 来源 / 生成物 四个 Tab
class NotebookDetailPage extends StatelessWidget {
  const NotebookDetailPage({super.key, required this.notebook});
  final Map<String, dynamic> notebook;

  @override
  Widget build(BuildContext context) {
    final nbId = notebook['id'] as int;
    return DefaultTabController(
      length: 4,
      child: Scaffold(
        appBar: AppBar(
          title: Text(notebook['name'] as String? ?? ''),
          bottom: const TabBar(tabs: [
            Tab(text: '笔记'),
            Tab(text: '问答'),
            Tab(text: '来源'),
            Tab(text: '生成物'),
          ]),
        ),
        body: TabBarView(children: [
          _NotesTab(nbId: nbId),
          QaPage(notebookId: nbId),
          _SourcesTab(nbId: nbId),
          GenTab(nbId: nbId),
        ]),
      ),
    );
  }
}

class _NotesTab extends StatefulWidget {
  const _NotesTab({required this.nbId});
  final int nbId;

  @override
  State<_NotesTab> createState() => _NotesTabState();
}

class _NotesTabState extends State<_NotesTab> {
  List<dynamic>? _list;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final l = await NoteApi.list(widget.nbId);
      if (mounted) setState(() => _list = l);
    } catch (_) {
      if (mounted) setState(() => _list = []);
    }
  }

  Future<void> _open([Map<String, dynamic>? note]) async {
    Map<String, dynamic>? target = note;
    if (target == null) {
      final ctrl = TextEditingController();
      final title = await showDialog<String>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('新建笔记'),
          content: TextField(
              controller: ctrl,
              autofocus: true,
              decoration: const InputDecoration(hintText: '标题')),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
            FilledButton(
                onPressed: () => Navigator.pop(ctx, ctrl.text.trim()),
                child: const Text('创建')),
          ],
        ),
      );
      if (title == null || title.isEmpty) return;
      try {
        target = await NoteApi.create(widget.nbId, title);
      } catch (_) {
        if (mounted) {
          ScaffoldMessenger.of(context)
              .showSnackBar(const SnackBar(content: Text('创建失败')));
        }
        return;
      }
    }
    if (!mounted) return;
    await Navigator.of(context).push(MaterialPageRoute(
        builder: (_) => NoteEditPage(
            notebookId: widget.nbId,
            noteId: target!['id'] as int,
            initialTitle: target['title'] as String? ?? '')));
    _load();
  }

  Future<void> _delete(Map<String, dynamic> note) async {
    try {
      await NoteApi.delete(widget.nbId, note['id'] as int);
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
      floatingActionButton: FloatingActionButton.small(
          onPressed: _open, child: const Icon(Icons.add)),
      body: _list == null
          ? const Center(child: CircularProgressIndicator())
          : _list!.isEmpty
              ? const Center(child: Text('还没有笔记'))
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView.builder(
                    itemCount: _list!.length,
                    itemBuilder: (_, i) {
                      final n = _list![i];
                      return ListTile(
                        leading: const Icon(Icons.edit_note),
                        title: Text(n['title'] as String? ?? '无标题'),
                        subtitle: Text(n['updatedAt'] as String? ?? '',
                            maxLines: 1, overflow: TextOverflow.ellipsis),
                        trailing: IconButton(
                            icon: const Icon(Icons.delete_outline),
                            onPressed: () => _delete(n)),
                        onTap: () => _open(n),
                      );
                    },
                  ),
                ),
    );
  }
}

class _SourcesTab extends StatefulWidget {
  const _SourcesTab({required this.nbId});
  final int nbId;

  @override
  State<_SourcesTab> createState() => _SourcesTabState();
}

class _SourcesTabState extends State<_SourcesTab> {
  List<dynamic>? _list;
  List<dynamic> _notes = [];
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      // 过滤 NOTE_BODY(笔记正文源,不算上传文件)
      final l = (await NotebookApi.sources(widget.nbId))
          .where((s) => s['type'] != 'NOTE_BODY')
          .toList();
      final notes = await NoteApi.list(widget.nbId);
      if (!mounted) return;
      setState(() {
        _list = l;
        _notes = notes;
      });
      // 有非终态源时轮询
      _poll?.cancel();
      final pending = l.any((s) =>
          s['status'] == 'PENDING' ||
          s['status'] == 'PARSING' ||
          s['status'] == 'EMBEDDING');
      if (pending) {
        _poll = Timer(const Duration(seconds: 3), _load);
      }
    } catch (_) {
      if (mounted) setState(() => _list ??= []);
    }
  }

  Future<void> _upload() async {
    // 上传必须挂到某条笔记下
    if (_notes.isEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('请先在「笔记」Tab 创建一条笔记')));
      return;
    }
    int noteId = _notes.first['id'] as int;
    if (_notes.length > 1) {
      final picked = await showModalBottomSheet<int>(
        context: context,
        builder: (ctx) => ListView(
          shrinkWrap: true,
          children: _notes
              .map((n) => ListTile(
                  title: Text(n['title'] as String? ?? '无标题'),
                  onTap: () => Navigator.pop(ctx, n['id'] as int)))
              .toList(),
        ),
      );
      if (picked == null) return;
      noteId = picked;
    }
    final result = await FilePicker.platform
        .pickFiles(withData: true, type: FileType.custom, allowedExtensions: [
      'pdf', 'docx', 'txt', 'md', 'png', 'jpg', 'jpeg', 'mp3', 'wav', 'm4a'
    ]);
    final f = result?.files.first;
    if (f?.bytes == null) return;
    try {
      await NoteApi.upload(widget.nbId, noteId, f!.bytes!, f.name);
      _load();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('上传失败')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      floatingActionButton: FloatingActionButton.small(
          onPressed: _upload, child: const Icon(Icons.upload_file)),
      body: _list == null
          ? const Center(child: CircularProgressIndicator())
          : _list!.isEmpty
              ? const Center(child: Text('还没有上传源文件'))
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView.builder(
                    itemCount: _list!.length,
                    itemBuilder: (_, i) {
                      final s = _list![i];
                      final status = s['status'] as String? ?? '';
                      return ListTile(
                        leading: Icon(_statusIcon(status)),
                        title: Text(s['name'] as String? ?? ''),
                        subtitle: Text(
                            '${s['type'] ?? ''} · ${_statusText(status)}'
                            '${s['chunkCount'] != null ? ' · ${s['chunkCount']} 块' : ''}'),
                      );
                    },
                  ),
                ),
    );
  }

  IconData _statusIcon(String s) => switch (s) {
        'DONE' => Icons.check_circle_outline,
        'FAILED' => Icons.error_outline,
        _ => Icons.hourglass_top,
      };

  String _statusText(String s) => switch (s) {
        'PENDING' => '排队中',
        'PARSING' => '解析中',
        'EMBEDDING' => '嵌入中',
        'DONE' => '已完成',
        'FAILED' => '失败',
        _ => s,
      };
}
