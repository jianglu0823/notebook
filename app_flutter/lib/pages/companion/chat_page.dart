import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:web_socket_channel/web_socket_channel.dart';
import '../../api/companion_api.dart';
import '../../core/auth.dart';
import '../../core/config.dart';
import 'companion_settings_page.dart';

class _Msg {
  _Msg(this.role, this.text, {this.id, this.streaming = false});
  final String role; // USER | ASSISTANT | SYSTEM
  int? id; // 服务端消息 id(本地新发的用户气泡暂无)
  String text;
  bool streaming;
  String? meta;
}

class ChatPage extends StatefulWidget {
  const ChatPage({super.key, required this.companionId, required this.companionName});
  final int companionId;
  final String companionName;

  @override
  State<ChatPage> createState() => _ChatPageState();
}

class _ChatPageState extends State<ChatPage> {
  final _inputCtrl = TextEditingController();
  final _scrollCtrl = ScrollController();
  final List<_Msg> _msgs = [];
  WebSocketChannel? _ws;
  StreamSubscription? _sub;
  Timer? _pingTimer;
  Timer? _reconnectTimer;
  int _reconnectDelay = 2; // 秒,指数退避,上限 30
  final Map<int, _Msg> _pending = {}; // messageId -> 正在流式输出的气泡
  bool _wsReady = false;
  bool _sending = false;
  bool _manualClose = false;
  Map<String, dynamic>? _intimacy;
  String? _name;

  @override
  void initState() {
    super.initState();
    _name = widget.companionName;
    _loadHistory();
    _loadIntimacy();
    _connect();
  }

  Future<void> _loadIntimacy() async {
    try {
      final v = await CompanionApi.intimacy(widget.companionId);
      if (mounted) setState(() => _intimacy = v);
    } catch (_) {/* 亲密度拉取失败不阻塞聊天 */}
  }

  @override
  void dispose() {
    _manualClose = true;
    _pingTimer?.cancel();
    _reconnectTimer?.cancel();
    _sub?.cancel();
    _ws?.sink.close();
    _inputCtrl.dispose();
    _scrollCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadHistory() async {
    try {
      final list = await CompanionApi.messages(widget.companionId);
      if (!mounted) return;
      setState(() {
        for (final m in list) {
          _msgs.add(_Msg(m['role'] as String? ?? 'ASSISTANT',
              m['content'] as String? ?? '',
              id: m['id'] as int?));
        }
      });
      _scrollToBottom();
    } catch (_) {/* 历史拉取失败不阻塞聊天 */}
  }

  void _connect() {
    _reconnectTimer?.cancel();
    final uri = Uri.parse(
        '$wsBase/api/ws/companion?token=${Uri.encodeComponent(Auth.token ?? "")}');
    _ws = WebSocketChannel.connect(uri);
    _sub = _ws!.stream.listen(_onFrame,
        onDone: () => _markDisconnected(), onError: (_) => _markDisconnected());
    _pingTimer?.cancel();
    _pingTimer = Timer.periodic(const Duration(seconds: 25), (_) {
      _ws?.sink.add(jsonEncode({'kind': 'ping'}));
    });
  }

  void _markDisconnected() {
    if (!mounted) return;
    setState(() {
      _wsReady = false;
      _sending = false;
    });
    for (final m in _pending.values) {
      m.streaming = false;
      if (m.text.isEmpty) m.text = '[连接中断]';
    }
    _pending.clear();
    _scheduleReconnect();
  }

  void _scheduleReconnect() {
    if (_manualClose || !mounted) return;
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(Duration(seconds: _reconnectDelay), _connect);
    _reconnectDelay = (_reconnectDelay * 2).clamp(2, 30);
  }

  void _onFrame(dynamic data) {
    if (data is! String) return; // 二进制帧(TTS 音频)MVP 忽略
    final Map<String, dynamic> f = jsonDecode(data);
    if (!mounted) return;
    switch (f['kind']) {
      case 'ready':
        _reconnectDelay = 2;
        setState(() => _wsReady = true);
      case 'pong':
        break;
      case 'chat.delta':
        final id = f['messageId'] as int?;
        if (id != null && !_pending.containsKey(id)) {
          final m = _Msg('ASSISTANT', '', id: id, streaming: true);
          _pending[id] = m;
          setState(() => _msgs.add(m));
        }
        final m = _pending[id];
        if (m != null) {
          setState(() => m.text += f['delta'] as String? ?? '');
          _scrollToBottom();
        }
      case 'chat.done':
        final id = f['messageId'] as int?;
        var m = _pending.remove(id);
        if (m == null && (f['fullText'] as String?)?.isNotEmpty == true) {
          m = _Msg('ASSISTANT', '');
          _msgs.add(m);
        }
        setState(() {
          _sending = false;
          if (m != null) {
            m.text = f['fullText'] as String? ?? m.text;
            m.streaming = false;
            m.id ??= id;
            final inT = f['inputTokens'], outT = f['outputTokens'];
            if (inT != null && outT != null) m.meta = 'tokens $inT/$outT';
          }
        });
        _scrollToBottom();
        _loadIntimacy();
      case 'chat.error':
        _pending.remove(f['messageId'] as int?);
        setState(() {
          _sending = false;
          _msgs.add(_Msg('SYSTEM', '回复出错:${f['error'] ?? "未知错误"}'));
        });
      case 'reminder':
      case 'milestone':
        final text = f['text'] as String?;
        if (text != null && text.isNotEmpty) {
          setState(() => _msgs.add(_Msg('SYSTEM', text)));
          _scrollToBottom();
        }
      default:
        break;
    }
  }

  void _send() {
    final text = _inputCtrl.text.trim();
    if (text.isEmpty || _sending) return;
    if (!_wsReady) {
      _connect();
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('连接中,请稍候再发')));
      return;
    }
    _inputCtrl.clear();
    setState(() {
      _sending = true;
      _msgs.add(_Msg('USER', text));
    });
    _scrollToBottom();
    // 回复气泡由首帧 chat.delta(带真实 messageId)触发创建
    _ws!.sink.add(jsonEncode({
      'kind': 'chat.text',
      'companionId': widget.companionId,
      'content': text,
    }));
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
    return Scaffold(
      appBar: AppBar(
        title: Text(_name ?? widget.companionName),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 4),
            child: Icon(Icons.circle,
                size: 12, color: _wsReady ? Colors.green : Colors.grey),
          ),
          IconButton(
              icon: const Icon(Icons.settings_outlined),
              onPressed: _openSettings),
        ],
      ),
      body: Column(
        children: [
          if (_intimacy != null) _intimacyBar(),
          Expanded(
            child: ListView.builder(
              controller: _scrollCtrl,
              padding: const EdgeInsets.all(12),
              itemCount: _msgs.length,
              itemBuilder: (_, i) => _bubble(_msgs[i]),
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
                          hintText: '说点什么…', border: OutlineInputBorder()),
                      minLines: 1,
                      maxLines: 4,
                      onSubmitted: (_) => _send(),
                    ),
                  ),
                  const SizedBox(width: 8),
                  IconButton.filled(
                    onPressed: _sending ? null : _send,
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
      ),
    );
  }

  Future<void> _openSettings() async {
    final result = await Navigator.of(context).push(MaterialPageRoute(
        builder: (_) =>
            CompanionSettingsPage(companionId: widget.companionId)));
    if (!mounted) return;
    if (result == 'deleted') {
      Navigator.of(context).pop();
      return;
    }
    // 可能改了名字,刷新标题
    try {
      final c = await CompanionApi.get(widget.companionId);
      if (mounted) setState(() => _name = c['name'] as String?);
    } catch (_) {}
  }

  Future<void> _deleteMsg(_Msg m) async {
    if (m.id == null) return;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除这条消息?'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('删除')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await CompanionApi.deleteMessage(widget.companionId, m.id!);
      if (mounted) setState(() => _msgs.remove(m));
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('删除失败')));
      }
    }
  }

  Widget _intimacyBar() {
    final score = (_intimacy!['score'] as num?)?.toInt() ?? 0;
    final days = (_intimacy!['daysCount'] as num?)?.toInt() ?? 0;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: Row(children: [
        const Icon(Icons.favorite, size: 14, color: Colors.pink),
        const SizedBox(width: 6),
        Expanded(
          child: LinearProgressIndicator(
              value: (score / 100).clamp(0.0, 1.0), minHeight: 6),
        ),
        const SizedBox(width: 8),
        Text('亲密度 $score · 相伴 $days 天',
            style: const TextStyle(fontSize: 12)),
      ]),
    );
  }

  Widget _bubble(_Msg m) {
    if (m.role == 'SYSTEM') {
      return Center(
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: Text(m.text,
              style: const TextStyle(color: Colors.grey, fontSize: 12)),
        ),
      );
    }
    final isUser = m.role == 'USER';
    return Align(
      alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
      child: GestureDetector(
        onLongPress:
            m.id != null && !m.streaming ? () => _deleteMsg(m) : null,
        child: Container(
        margin: const EdgeInsets.symmetric(vertical: 4),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        constraints:
            BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.78),
        decoration: BoxDecoration(
          color: isUser
              ? Theme.of(context).colorScheme.primary
              : Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(16),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              m.text.isEmpty && m.streaming ? '…' : m.text,
              style: TextStyle(color: isUser ? Colors.white : null),
            ),
            if (m.meta != null)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(m.meta!,
                    style: TextStyle(
                        fontSize: 10,
                        color: isUser ? Colors.white70 : Colors.grey)),
              ),
          ],
        ),
        ),
      ),
    );
  }
}
