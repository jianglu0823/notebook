import 'package:flutter/material.dart';
import 'package:table_calendar/table_calendar.dart';
import '../../api/schedule_api.dart';

/// 日程:月历 + 当天事件 + 新建/删除
class SchedulePage extends StatefulWidget {
  const SchedulePage({super.key});

  @override
  State<SchedulePage> createState() => _SchedulePageState();
}

class _SchedulePageState extends State<SchedulePage> {
  DateTime _focused = DateTime.now();
  DateTime _selected = DateTime.now();
  List<dynamic> _events = [];
  bool _loaded = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    // 拉聚焦月前后各一周,覆盖整月视图
    final first = DateTime(_focused.year, _focused.month - 1, 1);
    final last = DateTime(_focused.year, _focused.month + 2, 0);
    try {
      final list = await ScheduleApi.events(first, last);
      if (mounted) {
        setState(() {
          _events = list;
          _loaded = true;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _loaded = true);
    }
  }

  List<dynamic> _eventsOf(DateTime day) => _events.where((e) {
        final st = DateTime.tryParse(e['startTime'] as String? ?? '');
        if (st == null) return false;
        final l = st.toLocal();
        return l.year == day.year && l.month == day.month && l.day == day.day;
      }).toList();

  Future<void> _create() async {
    final titleCtrl = TextEditingController();
    var start = DateTime(_selected.year, _selected.month, _selected.day,
        DateTime.now().hour + 1);
    var end = start.add(const Duration(hours: 1));
    String priority = 'P2';

    String fmt(DateTime d) =>
        '${d.month}/${d.day} ${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';

    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setD) => AlertDialog(
          title: const Text('新建日程'),
          content: Column(mainAxisSize: MainAxisSize.min, children: [
            TextField(
                controller: titleCtrl,
                decoration: const InputDecoration(labelText: '标题')),
            const SizedBox(height: 8),
            Row(children: [
              Expanded(child: Text('开始 ${fmt(start)}')),
              TextButton(
                  onPressed: () async {
                    final t = await showTimePicker(
                        context: ctx,
                        initialTime: TimeOfDay.fromDateTime(start));
                    if (t != null) {
                      setD(() {
                        start = DateTime(_selected.year, _selected.month,
                            _selected.day, t.hour, t.minute);
                        if (!end.isAfter(start)) {
                          end = start.add(const Duration(hours: 1));
                        }
                      });
                    }
                  },
                  child: const Text('修改')),
            ]),
            Row(children: [
              Expanded(child: Text('结束 ${fmt(end)}')),
              TextButton(
                  onPressed: () async {
                    final t = await showTimePicker(
                        context: ctx, initialTime: TimeOfDay.fromDateTime(end));
                    if (t != null) {
                      setD(() => end = DateTime(_selected.year,
                          _selected.month, _selected.day, t.hour, t.minute));
                    }
                  },
                  child: const Text('修改')),
            ]),
            DropdownButtonFormField<String>(
              initialValue: priority,
              decoration: const InputDecoration(labelText: '优先级'),
              items: const [
                DropdownMenuItem(value: 'P0', child: Text('P0 紧急')),
                DropdownMenuItem(value: 'P1', child: Text('P1 重要')),
                DropdownMenuItem(value: 'P2', child: Text('P2 普通')),
              ],
              onChanged: (v) => setD(() => priority = v ?? 'P2'),
            ),
          ]),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(ctx, false),
                child: const Text('取消')),
            FilledButton(
                onPressed: () => Navigator.pop(ctx, true),
                child: const Text('保存')),
          ],
        ),
      ),
    );
    if (ok != true || titleCtrl.text.trim().isEmpty) return;
    try {
      await ScheduleApi.create(
          title: titleCtrl.text.trim(),
          start: start,
          end: end.isAfter(start)
              ? end
              : start.add(const Duration(hours: 1)),
          priority: priority);
      _load();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('创建失败')));
      }
    }
  }

  Future<void> _delete(Map<String, dynamic> e) async {
    try {
      await ScheduleApi.delete(e['eventId'] as String);
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
    final dayEvents = _eventsOf(_selected);
    return Scaffold(
      appBar: AppBar(title: const Text('日程')),
      floatingActionButton:
          FloatingActionButton(onPressed: _create, child: const Icon(Icons.add)),
      body: !_loaded
          ? const Center(child: CircularProgressIndicator())
          : Column(children: [
              TableCalendar(
                firstDay: DateTime(2024),
                lastDay: DateTime(2030),
                focusedDay: _focused,
                selectedDayPredicate: (d) => isSameDay(d, _selected),
                calendarFormat: CalendarFormat.month,
                availableCalendarFormats: const {CalendarFormat.month: '月'},
                eventLoader: _eventsOf,
                onDaySelected: (sel, foc) =>
                    setState(() {
                      _selected = sel;
                      _focused = foc;
                    }),
                onPageChanged: (foc) {
                  _focused = foc;
                  _load();
                },
              ),
              const Divider(height: 1),
              Expanded(
                child: dayEvents.isEmpty
                    ? const Center(child: Text('当天没有日程'))
                    : ListView(children: [
                        for (final e in dayEvents)
                          Dismissible(
                            key: ValueKey(e['eventId']),
                            direction: DismissDirection.endToStart,
                            background: Container(
                                color: Colors.red,
                                alignment: Alignment.centerRight,
                                padding: const EdgeInsets.only(right: 16),
                                child: const Icon(Icons.delete,
                                    color: Colors.white)),
                            onDismissed: (_) => _delete(e),
                            child: ListTile(
                              leading: Text(e['emoji'] as String? ?? '📅',
                                  style: const TextStyle(fontSize: 20)),
                              title: Text(e['title'] as String? ?? ''),
                              subtitle: Text(_timeRange(e)),
                              trailing: _priorityChip(
                                  e['priority'] as String? ?? 'P2'),
                            ),
                          ),
                      ]),
              ),
            ]),
    );
  }

  String _timeRange(Map<String, dynamic> e) {
    String fmt(String? iso) {
      final d = DateTime.tryParse(iso ?? '');
      if (d == null) return '';
      final l = d.toLocal();
      return '${l.hour.toString().padLeft(2, '0')}:${l.minute.toString().padLeft(2, '0')}';
    }

    return '${fmt(e['startTime'] as String?)} - ${fmt(e['endTime'] as String?)}';
  }

  Widget _priorityChip(String p) {
    final color = switch (p) {
      'P0' => Colors.red,
      'P1' => Colors.orange,
      _ => Colors.grey,
    };
    return Chip(
        label: Text(p, style: TextStyle(color: color, fontSize: 11)),
        side: BorderSide(color: color),
        visualDensity: VisualDensity.compact);
  }
}
