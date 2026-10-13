import 'package:flutter/material.dart';
import 'package:flutter_html/flutter_html.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import '../../api/note_api.dart';

/// 笔记编辑:Markdown 源码编辑 / 预览切换,返回时自动保存
class NoteEditPage extends StatefulWidget {
  const NoteEditPage(
      {super.key,
      required this.notebookId,
      required this.noteId,
      required this.initialTitle});
  final int notebookId;
  final int noteId;
  final String initialTitle;

  @override
  State<NoteEditPage> createState() => _NoteEditPageState();
}

class _NoteEditPageState extends State<NoteEditPage> {
  late final TextEditingController _titleCtrl;
  final _contentCtrl = TextEditingController();
  bool _preview = false;
  bool _loading = true;
  bool _dirty = false;
  bool _saving = false;
  bool _isHtml = false; // RICHTEXT 笔记:正文是 HTML,App 内只读

  @override
  void initState() {
    super.initState();
    _titleCtrl = TextEditingController(text: widget.initialTitle);
    _load();
  }

  @override
  void dispose() {
    _titleCtrl.dispose();
    _contentCtrl.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final n = await NoteApi.get(widget.notebookId, widget.noteId);
      if (!mounted) return;
      _contentCtrl.text = n['content'] as String? ?? '';
      _isHtml = n['type'] == 'RICHTEXT';
    } catch (_) {/* 正文拉不到就从空开始 */}
    if (mounted) setState(() => _loading = false);
  }

  Future<bool> _save() async {
    if (_saving) return false;
    _saving = true;
    try {
      await NoteApi.updateBody(widget.notebookId, widget.noteId,
          _titleCtrl.text.trim(), _contentCtrl.text);
      _dirty = false;
      return true;
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('保存失败')));
      }
      return false;
    } finally {
      _saving = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        final ok = !_dirty || await _save();
        if (ok && context.mounted) Navigator.of(context).pop();
      },
      child: Scaffold(
        appBar: AppBar(
          title: TextField(
            controller: _titleCtrl,
            decoration: const InputDecoration(
                hintText: '标题', border: InputBorder.none),
            onChanged: (_) => _dirty = true,
          ),
          actions: [
            if (!_isHtml)
              IconButton(
                tooltip: _preview ? '编辑' : '预览',
                icon: Icon(_preview ? Icons.edit : Icons.visibility),
                onPressed: () => setState(() => _preview = !_preview),
              ),
            IconButton(
              tooltip: '保存',
              icon: const Icon(Icons.save_outlined),
              onPressed: () async {
                if (await _save() && mounted) setState(() {});
              },
            ),
          ],
        ),
        body: _loading
            ? const Center(child: CircularProgressIndicator())
            : _isHtml
                ? Column(children: [
                    Container(
                      width: double.infinity,
                      padding: const EdgeInsets.symmetric(
                          horizontal: 16, vertical: 8),
                      color: const Color(0xFFFFF4E0),
                      child: const Text('富文本笔记,正文编辑请用网页版',
                          style:
                                TextStyle(fontSize: 12, color: Color(0xFF9A6A00))),
                    ),
                    Expanded(
                      child: SingleChildScrollView(
                        padding: const EdgeInsets.symmetric(horizontal: 16),
                        child: Html(data: _contentCtrl.text),
                      ),
                    ),
                  ])
                : _preview
                ? SingleChildScrollView(
                    padding: const EdgeInsets.all(16),
                    child: MarkdownBody(data: _contentCtrl.text),
                  )
                : Padding(
                    padding: const EdgeInsets.all(12),
                    child: TextField(
                      controller: _contentCtrl,
                      maxLines: null,
                      expands: true,
                      textAlignVertical: TextAlignVertical.top,
                      decoration: const InputDecoration(
                          hintText: '支持 Markdown 语法…',
                          border: InputBorder.none),
                      onChanged: (_) => _dirty = true,
                    ),
                  ),
      ),
    );
  }
}
