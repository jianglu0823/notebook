import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../../api/qa_api.dart';

class _Turn {
  _Turn(this.question);
  final String question;
  String answer = '';
  List<dynamic> citations = [];
  bool streaming = true;
}

/// RAG 问答:SSE 流式 + 引用来源,sessionId 维持会话
class QaPage extends StatefulWidget {
  const QaPage({super.key, required this.notebookId});
  final int notebookId;

  @override
  State<QaPage> createState() => _QaPageState();
}

class _QaPageState extends State<QaPage> {
  final _inputCtrl = TextEditingController();
  final _scrollCtrl = ScrollController();
  final List<_Turn> _turns = [];
  String? _sessionId;
  bool _sending = false;
  StreamSubscription? _sub;

  String get _spKey => 'qa_session_${widget.notebookId}';

  @override
  void initState() {
    super.initState();
    // 恢复上次会话,历史消息一并拉回
    SharedPreferences.getInstance().then((sp) {
      _sessionId = sp.getString(_spKey);
      _loadHistory();
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    _inputCtrl.dispose();
    _scrollCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadHistory() async {
    if (_sessionId == null) return;
    try {
      final list = await QaApi.history(widget.notebookId, _sessionId!);
      if (!mounted) return;
      setState(() {
        _turns.clear();
        for (final h in list) {
          final t = _Turn(h['question'] as String? ?? '')
            ..answer = h['answer'] as String? ?? ''
            ..streaming = false;
          final c = h['citations'];
          if (c is String && c.isNotEmpty) {
            try {
              t.citations = jsonDecode(c) as List<dynamic>;
            } catch (_) {}
          }
          _turns.add(t);
        }
      });
      _scrollToBottom();
    } catch (_) {/* 历史拉取失败不阻塞提问 */}
  }

  Future<void> _ask() async {
    final q = _inputCtrl.text.trim();
    if (q.isEmpty || _sending) return;
    _inputCtrl.clear();
    final turn = _Turn(q);
    setState(() {
      _sending = true;
      _turns.add(turn);
    });
    _scrollToBottom();

    await _sub?.cancel();
    _sub = QaApi.ask(widget.notebookId, q, sessionId: _sessionId).listen(
      (e) {
        if (!mounted) return;
        setState(() {
          switch (e.event) {
            case 'session':
              _sessionId = e.data;
              SharedPreferences.getInstance()
                  .then((sp) => sp.setString(_spKey, e.data));
            case 'citations':
              try {
                turn.citations = jsonDecode(e.data) as List<dynamic>;
              } catch (_) {}
            case 'delta':
              turn.answer += e.data;
            case 'done':
              turn.streaming = false;
              _sending = false;
          }
        });
        _scrollToBottom();
      },
      onError: (_) {
        if (!mounted) return;
        setState(() {
          turn.streaming = false;
          if (turn.answer.isEmpty) turn.answer = '[问答失败,请重试]';
          _sending = false;
        });
      },
      onDone: () {
        if (!mounted) return;
        setState(() {
          turn.streaming = false;
          _sending = false;
        });
      },
    );
  }

  void _newSession() {
    SharedPreferences.getInstance().then((sp) => sp.remove(_spKey));
    setState(() {
      _sessionId = null;
      _turns.clear();
    });
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollCtrl.hasClients) {
        _scrollCtrl.jumpTo(_scrollCtrl.position.maxScrollExtent);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        if (_turns.isNotEmpty)
          Align(
            alignment: Alignment.centerRight,
            child: TextButton.icon(
                onPressed: _sending ? null : _newSession,
                icon: const Icon(Icons.add_comment, size: 16),
                label: const Text('新会话')),
          ),
        Expanded(
          child: _turns.isEmpty
              ? const Center(child: Text('基于本笔记本的内容提问试试'))
              : ListView.builder(
                  controller: _scrollCtrl,
                  padding: const EdgeInsets.all(12),
                  itemCount: _turns.length,
                  itemBuilder: (_, i) => _turnView(_turns[i]),
                ),
        ),
        SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 4, 12, 12),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _inputCtrl,
                    decoration: const InputDecoration(
                        hintText: '问点什么…', border: OutlineInputBorder()),
                    minLines: 1,
                    maxLines: 4,
                    onSubmitted: (_) => _ask(),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filled(
                  onPressed: _sending ? null : _ask,
                  icon: _sending
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.send),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _turnView(_Turn t) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Align(
          alignment: Alignment.centerRight,
          child: Container(
            margin: const EdgeInsets.symmetric(vertical: 4),
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            constraints: BoxConstraints(
                maxWidth: MediaQuery.of(context).size.width * 0.78),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.primary,
              borderRadius: BorderRadius.circular(16),
            ),
            child:
                Text(t.question, style: const TextStyle(color: Colors.white)),
          ),
        ),
        Align(
          alignment: Alignment.centerLeft,
          child: Container(
            margin: const EdgeInsets.symmetric(vertical: 4),
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(16),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (t.answer.isEmpty && t.streaming)
                  const Text('…')
                else
                  MarkdownBody(data: t.answer),
                if (t.citations.isNotEmpty) ...[
                  const Divider(height: 16),
                  Wrap(
                    spacing: 6,
                    runSpacing: 4,
                    children: [
                      for (var i = 0; i < t.citations.length; i++)
                        Tooltip(
                          message: t.citations[i]['sourceName'] as String? ??
                              '来源 ${i + 1}',
                          child: Chip(
                            label: Text('${i + 1}'),
                            visualDensity: VisualDensity.compact,
                          ),
                        ),
                    ],
                  ),
                ],
              ],
            ),
          ),
        ),
      ],
    );
  }
}
