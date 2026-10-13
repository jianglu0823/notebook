import 'dart:async';
import 'package:flutter/material.dart';
import '../../api/world_api.dart';
import 'world_tab.dart';

/// 自由世界:摇杆操控小鹿在小镇漫游,靠近居民可对话/派任务
class GameWorldPage extends StatefulWidget {
  const GameWorldPage({super.key});

  @override
  State<GameWorldPage> createState() => _GameWorldPageState();
}

class _GameWorldPageState extends State<GameWorldPage> {
  /// 渲染倍率:世界 1360×700 逻辑单位,放大后可漫游
  static const double _scale = 1.6;
  static const double _speed = 150; // 逻辑单位/秒
  static const double _talkDist = 80; // 可对话距离

  double _worldW = 1360;
  double _worldH = 700;
  List<dynamic> _places = [];
  List<dynamic> _agents = [];

  Offset _player = const Offset(680, 400); // 出生:古井广场旁
  Offset _joyDir = Offset.zero;
  bool _joyHeld = false; // 手指是否按住摇杆(按住不衰减)
  DateTime _lastPan = DateTime.fromMillisecondsSinceEpoch(0);
  Map<String, dynamic>? _nearby;
  Timer? _agentTimer;
  // 游戏循环用 Timer 而非 Ticker:模拟器上 vsync 驱动的 Ticker 有停摆坑,Timer 不依赖帧调度
  Timer? _gameTimer;
  DateTime _last = DateTime.now();

  @override
  void initState() {
    super.initState();
    _load();
    _agentTimer =
        Timer.periodic(const Duration(seconds: 5), (_) => _refreshAgents());
    _gameTimer =
        Timer.periodic(const Duration(milliseconds: 16), (_) => _tick());
  }

  @override
  void dispose() {
    _agentTimer?.cancel();
    _gameTimer?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final p = await WorldApi.places();
      if (!mounted) return;
      setState(() {
        _worldW = (p['worldW'] as num?)?.toDouble() ?? 1360;
        _worldH = (p['worldH'] as num?)?.toDouble() ?? 700;
        _places = p['places'] as List? ?? [];
      });
    } catch (_) {}
    await _refreshAgents();
  }

  Future<void> _refreshAgents() async {
    try {
      final a = await WorldApi.agents();
      if (!mounted) return;
      setState(() => _agents = a);
      _checkNearby();
    } catch (_) {}
  }

  void _tick() {
    final now = DateTime.now();
    var dt = now.difference(_last).inMicroseconds / 1e6;
    _last = now;
    // 卡顿帧钳位 dt 而不是丢弃:主线程被摇杆事件刷爆时仍能移动
    if (dt > 0.1) dt = 0.1;
    // 松手 150ms 后方向清零(adb 合成手势一帧内发完 down+up,靠这段窗口产生位移)
    if (!_joyHeld &&
        _joyDir != Offset.zero &&
        DateTime.now().difference(_lastPan).inMilliseconds > 150) {
      _joyDir = Offset.zero;
    }
    if (_joyDir == Offset.zero || dt <= 0) return;
    setState(() {
      _player = Offset(
        (_player.dx + _joyDir.dx * _speed * dt).clamp(20, _worldW - 20),
        (_player.dy + _joyDir.dy * _speed * dt).clamp(30, _worldH - 20),
      );
    });
    _checkNearby();
  }

  void _checkNearby() {
    Map<String, dynamic>? best;
    var bd = _talkDist;
    for (var i = 0; i < _agents.length; i++) {
      final a = _agents[i] as Map<String, dynamic>;
      final d = (_agentPos(a, i) - _player).distance;
      if (d < bd) {
        bd = d;
        best = a;
      }
    }
    if (best?['id'] != _nearby?['id'] && mounted) {
      setState(() => _nearby = best);
    }
  }

  Offset _agentPos(Map<String, dynamic> a, int index) {
    final px = (a['posX'] as num?)?.toDouble();
    final py = (a['posY'] as num?)?.toDouble();
    if (px != null && py != null) return Offset(px, py);
    var fx = _worldW / 2;
    var fy = _worldH / 2;
    for (final p in _places) {
      if (p['key'] == a['location']) {
        fx = (p['x'] as num).toDouble();
        fy = (p['y'] as num).toDouble();
        break;
      }
    }
    return Offset(fx + (index % 3 - 1) * 24, fy + (index % 2) * 16 - 8);
  }

  String get _currentPlace {
    var name = '郊外';
    var bd = 1e9;
    for (final p in _places) {
      final d = (Offset((p['x'] as num).toDouble(), (p['y'] as num).toDouble()) -
              _player)
          .distance;
      if (d < bd) {
        bd = d;
        name = p['name'] as String? ?? '';
      }
    }
    return name;
  }

  void _openChat() {
    final agent = _nearby;
    if (agent == null) return;
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => FractionallySizedBox(
          heightFactor: 0.85, child: _AgentChatSheet(agent: agent)),
    );
  }

  @override
  Widget build(BuildContext context) {
    final worldPxW = _worldW * _scale;
    final worldPxH = _worldH * _scale;
    return Scaffold(
      appBar: AppBar(
        title: const Text('小世界'),
        actions: [
          IconButton(
            tooltip: '经典视图',
            icon: const Icon(Icons.view_list_outlined),
            onPressed: () => Navigator.of(context)
                .push(MaterialPageRoute(builder: (_) => const WorldTab())),
          ),
        ],
      ),
      body: LayoutBuilder(builder: (context, c) {
        final camX =
            (_player.dx * _scale - c.maxWidth / 2).clamp(0.0, worldPxW - c.maxWidth);
        final camY = (_player.dy * _scale - c.maxHeight / 2)
            .clamp(0.0, worldPxH - c.maxHeight);
        return Stack(children: [
          // 世界画布(摄像机跟随)
          Positioned.fill(
            child: ClipRect(
              child: Transform.translate(
                offset: Offset(-camX, -camY),
                // OverflowBox 放开父级 tight 约束,让世界画布按真实尺寸布局
                child: OverflowBox(
                  minWidth: 0,
                  maxWidth: worldPxW,
                  minHeight: 0,
                  maxHeight: worldPxH,
                  alignment: Alignment.topLeft,
                  child: SizedBox(
                    width: worldPxW,
                    height: worldPxH,
                    child: Stack(children: [
                    Positioned.fill(
                      child: CustomPaint(
                          painter: _StreetPainter(_worldW, _worldH, _scale)),
                    ),
                    for (final p in _places) _placeMarker(p),
                      for (var i = 0; i < _agents.length; i++)
                        _agentMarker(_agents[i] as Map<String, dynamic>, i),
                      _playerMarker(),
                    ]),
                  ),
                ),
              ),
            ),
          ),
          // HUD:当前位置
          Positioned(
            top: 8,
            left: 0,
            right: 0,
            child: Center(
              child: Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.9),
                  borderRadius: BorderRadius.circular(20),
                  boxShadow: const [
                    BoxShadow(color: Colors.black12, blurRadius: 4)
                  ],
                ),
                child: Text('📍 $_currentPlace',
                    style: const TextStyle(
                        fontSize: 12, fontWeight: FontWeight.w600)),
              ),
            ),
          ),
          // 摇杆
          Positioned(left: 24, bottom: 28, child: _joystick()),
          // 对话入口
          if (_nearby != null)
            Positioned(
              right: 20,
              bottom: 34,
              child: FilledButton.icon(
                style: FilledButton.styleFrom(
                    backgroundColor: const Color(0xFF8B5E3C),
                    padding: const EdgeInsets.symmetric(
                        horizontal: 16, vertical: 12)),
                onPressed: _openChat,
                icon: Text(_nearby!['avatar'] as String? ?? '🙂',
                    style: const TextStyle(fontSize: 18)),
                label: Text('和${_nearby!['name'] ?? '居民'}聊聊'),
              ),
            ),
        ]);
      }),
    );
  }

  Widget _placeMarker(dynamic p) {
    final x = (p['x'] as num).toDouble() * _scale;
    final y = (p['y'] as num).toDouble() * _scale;
    return Positioned(
      left: x - 40,
      top: y - 26,
      width: 80,
      child: Column(children: [
        Text('${p['emoji'] ?? ''}', style: const TextStyle(fontSize: 30)),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: 0.75),
            borderRadius: BorderRadius.circular(6),
          ),
          child: Text('${p['name'] ?? ''}',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                  fontSize: 10,
                  fontWeight: FontWeight.w600,
                  color: Color(0xFF4A5D3A))),
        ),
      ]),
    );
  }

  Widget _agentMarker(Map<String, dynamic> a, int i) {
    final pos = _agentPos(a, i);
    return AnimatedPositioned(
      duration: const Duration(milliseconds: 800),
      curve: Curves.easeInOut,
      left: pos.dx * _scale - 18,
      top: pos.dy * _scale - 44,
      width: 60,
      child: Column(children: [
        Container(
          width: 26,
          height: 26,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: Colors.white,
            shape: BoxShape.circle,
            border: Border.all(
                color: _nearby?['id'] == a['id']
                    ? const Color(0xFF8B5E3C)
                    : const Color(0xFF7A9B5E),
                width: _nearby?['id'] == a['id'] ? 2 : 1.2),
            boxShadow: const [
              BoxShadow(color: Colors.black26, blurRadius: 2, offset: Offset(0, 1))
            ],
          ),
          child: Text(a['avatar'] as String? ?? '🙂',
              style: const TextStyle(fontSize: 13)),
        ),
        Text(a['name'] as String? ?? '',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
                fontSize: 9,
                fontWeight: FontWeight.w600,
                color: Color(0xFF3C4A30),
                shadows: [Shadow(color: Colors.white70, blurRadius: 2)])),
      ]),
    );
  }

  Widget _playerMarker() {
    return Positioned(
      left: _player.dx * _scale - 22,
      top: _player.dy * _scale - 46,
      width: 44,
      child: Column(children: [
        const Text('🦌', style: TextStyle(fontSize: 32, shadows: [
          Shadow(color: Colors.black26, blurRadius: 3, offset: Offset(0, 2))
        ])),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 5),
          decoration: BoxDecoration(
            color: const Color(0xFF8B5E3C),
            borderRadius: BorderRadius.circular(6),
          ),
          child: const Text('我',
              style: TextStyle(
                  fontSize: 9,
                  color: Colors.white,
                  fontWeight: FontWeight.bold)),
        ),
      ]),
    );
  }

  Widget _joystick() {
    return _Joystick(
      onStart: () {
        _joyHeld = true;
        _lastPan = DateTime.now();
      },
      onMove: (dir) {
        // 只更新字段不 setState:事件洪峰下避免全量重建世界
        _joyDir = dir;
        _lastPan = DateTime.now();
      },
      onEnd: () => _joyHeld = false,
    );
  }
}

/// 摇杆:knob 视觉局部重建,方向变化回调给父级
class _Joystick extends StatefulWidget {
  const _Joystick(
      {required this.onStart, required this.onMove, required this.onEnd});
  final VoidCallback onStart;
  final ValueChanged<Offset> onMove;
  final VoidCallback onEnd;

  @override
  State<_Joystick> createState() => _JoystickState();
}

class _JoystickState extends State<_Joystick> {
  Offset _delta = Offset.zero;

  @override
  Widget build(BuildContext context) {
    const r = 38.0;
    return GestureDetector(
      onPanDown: (_) => widget.onStart(),
      onPanUpdate: (d) {
        final raw = d.localPosition - const Offset(60, 60);
        final clamped = raw.distance > r ? raw / raw.distance * r : raw;
        widget.onMove(clamped / r);
        if ((clamped - _delta).distance < 1.5) return;
        setState(() => _delta = clamped);
      },
      onPanEnd: (_) {
        setState(() => _delta = Offset.zero);
        widget.onEnd();
      },
      onPanCancel: () {
        setState(() => _delta = Offset.zero);
        widget.onEnd();
      },
      child: Container(
        width: 120,
        height: 120,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: Colors.white.withValues(alpha: 0.35),
          border: Border.all(color: Colors.white70, width: 1.5),
        ),
        child: Center(
          child: Transform.translate(
            offset: _delta,
            child: Container(
              width: 48,
              height: 48,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: Colors.white.withValues(alpha: 0.9),
                boxShadow: const [
                  BoxShadow(color: Colors.black26, blurRadius: 4)
                ],
              ),
              child: const Icon(Icons.directions_walk,
                  size: 22, color: Color(0xFF8B5E3C)),
            ),
          ),
        ),
      ),
    );
  }
}

/// 街道 + 草地绘制(路网来自 TownMap:纵街 x=305/555/805/1055,横街 y=262/478)
class _StreetPainter extends CustomPainter {
  _StreetPainter(this.w, this.h, this.scale);
  final double w;
  final double h;
  final double scale;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(
        Offset.zero & size,
        Paint()
          ..shader = const LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [Color(0xFFE9F4E2), Color(0xFFCBE4B8)])
              .createShader(Offset.zero & size));

    final road = Paint()..color = const Color(0xFFC9BFAE);
    const roadW = 40.0;
    for (final x in [305.0, 555.0, 805.0, 1055.0]) {
      canvas.drawRect(
          Rect.fromLTWH((x - roadW / 2) * scale, 0, roadW * scale, h * scale),
          road);
    }
    for (final y in [262.0, 478.0]) {
      canvas.drawRect(
          Rect.fromLTWH(0, (y - roadW / 2) * scale, w * scale, roadW * scale),
          road);
    }

    // 车道虚线
    final dash = Paint()
      ..color = const Color(0xFFF5F1E6)
      ..strokeWidth = 2;
    for (final x in [305.0, 555.0, 805.0, 1055.0]) {
      for (var y = 8.0; y < h; y += 28) {
        canvas.drawLine(Offset(x * scale, y * scale),
            Offset(x * scale, (y + 14) * scale), dash);
      }
    }
    for (final y in [262.0, 478.0]) {
      for (var x = 8.0; x < w; x += 28) {
        canvas.drawLine(Offset(x * scale, y * scale),
            Offset((x + 14) * scale, y * scale), dash);
      }
    }
  }

  @override
  bool shouldRepaint(_StreetPainter old) =>
      old.w != w || old.h != h || old.scale != scale;
}

/// 居民对话弹层:历史 + 发送 + 派任务快捷 chip
class _AgentChatSheet extends StatefulWidget {
  const _AgentChatSheet({required this.agent});
  final Map<String, dynamic> agent;

  @override
  State<_AgentChatSheet> createState() => _AgentChatSheetState();
}

class _AgentChatSheetState extends State<_AgentChatSheet> {
  final _ctrl = TextEditingController();
  final _scroll = ScrollController();
  List<dynamic>? _msgs;
  bool _sending = false;

  @override
  void initState() {
    super.initState();
    WorldApi.agentChatHistory(widget.agent['id'] as int).then((l) {
      if (mounted) {
        setState(() => _msgs = l);
        _toBottom();
      }
    }).catchError((_) {
      if (mounted) setState(() => _msgs = []);
    });
  }

  @override
  void dispose() {
    _ctrl.dispose();
    _scroll.dispose();
    super.dispose();
  }

  void _toBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) {
        _scroll.jumpTo(_scroll.position.maxScrollExtent);
      }
    });
  }

  Future<void> _send() async {
    final text = _ctrl.text.trim();
    if (text.isEmpty || _sending) return;
    setState(() {
      _sending = true;
      _msgs = [...?_msgs, {'role': 'user', 'content': text}];
      _ctrl.clear();
    });
    _toBottom();
    try {
      final reply =
          await WorldApi.agentChatSend(widget.agent['id'] as int, text);
      if (!mounted) return;
      setState(() => _msgs = [...?_msgs, reply]);
      _toBottom();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('发送失败,稍后再试')));
      }
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final a = widget.agent;
    return Column(children: [
      ListTile(
        leading: CircleAvatar(
            backgroundColor: const Color(0xFFF3EDE4),
            child: Text(a['avatar'] as String? ?? '🙂')),
        title: Text(a['name'] as String? ?? '',
            style: const TextStyle(fontWeight: FontWeight.bold)),
        subtitle: Text(
            '${a['moodEmoji'] ?? ''} ${a['occupation'] ?? a['title'] ?? ''}'),
      ),
      const Divider(height: 1),
      Expanded(
        child: _msgs == null
            ? const Center(child: CircularProgressIndicator())
            : _msgs!.isEmpty
                ? const Center(
                    child: Text('打个招呼吧,或者给 TA 派个任务',
                        style: TextStyle(color: Colors.grey)))
                : ListView.builder(
                    controller: _scroll,
                    padding: const EdgeInsets.all(12),
                    itemCount: _msgs!.length,
                    itemBuilder: (_, i) => _bubble(_msgs![i]),
                  ),
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(12, 4, 12, 0),
        child: Row(children: [
          _chip('📋 派任务', () {
            _ctrl.text = '【任务】';
            _ctrl.selection = TextSelection.collapsed(offset: _ctrl.text.length);
          }),
          const SizedBox(width: 8),
          _chip('👋 打招呼', () {
            _ctrl.text = '你好呀,最近在忙什么?';
            _send();
          }),
          const SizedBox(width: 8),
          _chip('❓ 在干嘛', () {
            _ctrl.text = '你现在在做什么?';
            _send();
          }),
        ]),
      ),
      SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Row(children: [
            Expanded(
              child: TextField(
                controller: _ctrl,
                decoration: InputDecoration(
                  hintText: '和${a['name'] ?? '居民'}说点什么…',
                  border: const OutlineInputBorder(),
                  isDense: true,
                ),
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
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: Colors.white))
                  : const Icon(Icons.send),
            ),
          ]),
        ),
      ),
    ]);
  }

  Widget _chip(String label, VoidCallback onTap) {
    return ActionChip(
      label: Text(label, style: const TextStyle(fontSize: 12)),
      onPressed: _sending ? null : onTap,
      visualDensity: VisualDensity.compact,
    );
  }

  Widget _bubble(dynamic m) {
    final mine = m['role'] == 'user';
    return Align(
      alignment: mine ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.only(bottom: 8),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        constraints: const BoxConstraints(maxWidth: 280),
        decoration: BoxDecoration(
          color: mine ? const Color(0xFF8B5E3C) : const Color(0xFFF3EDE4),
          borderRadius: BorderRadius.circular(14),
        ),
        child: Text('${m['content'] ?? ''}',
            style: TextStyle(
                fontSize: 14,
                height: 1.4,
                color: mine ? Colors.white : Colors.black87)),
      ),
    );
  }
}
