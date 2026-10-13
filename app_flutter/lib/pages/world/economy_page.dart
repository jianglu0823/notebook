import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import '../../api/world_api.dart';

/// 小镇经济面板:快照 + 30 日金币曲线 + 场所营收 + 富豪榜 + 股市行情,全部只读
class EconomyPage extends StatefulWidget {
  const EconomyPage({super.key});

  @override
  State<EconomyPage> createState() => _EconomyPageState();
}

class _EconomyPageState extends State<EconomyPage> {
  Map<String, dynamic>? _summary;
  Map<String, dynamic>? _places;
  List<dynamic>? _rich;
  List<dynamic>? _stocks;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final s = await WorldApi.economySummary();
      final pl = await WorldApi.economyPlaces();
      final r = await WorldApi.richList();
      final st = await WorldApi.stocks();
      if (!mounted) return;
      setState(() {
        _summary = s;
        _places = pl;
        _rich = r;
        _stocks = st;
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          _summary ??= {};
          _places ??= {};
          _rich ??= [];
          _stocks ??= [];
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = _summary;
    if (s == null) {
      return const Center(child: CircularProgressIndicator());
    }
    final latest = s['latest'] as Map<String, dynamic>?;
    final series = (s['series'] as List? ?? []);
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(padding: const EdgeInsets.all(16), children: [
        if (latest != null) _snapshot(latest),
        if (series.isNotEmpty) ...[
          const _SectionTitle('近 30 日金币总量'),
          _curve([
            for (final d in series)
              (d['totalCoins'] as num?)?.toDouble() ?? 0
          ]),
        ],
        const _SectionTitle('场所营收'),
        _placesCard(),
        const _SectionTitle('富豪榜'),
        _richCard(),
        const _SectionTitle('股市行情'),
        _stocksCard(),
      ]),
    );
  }

  Widget _snapshot(Map<String, dynamic> l) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('${l['simDate'] ?? ''} 快照',
              style: const TextStyle(fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          Wrap(spacing: 16, runSpacing: 8, children: [
            _kv('流通金币', l['totalCoins']),
            _kv('总收入', l['totalIncome']),
            _kv('总支出', l['totalExpense']),
            _kv('场所营收', l['totalPlaceRevenue']),
          ]),
        ]),
      ),
    );
  }

  Widget _kv(String k, dynamic v) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(k, style: const TextStyle(fontSize: 12, color: Colors.grey)),
      Text('${v ?? 0}',
          style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
    ]);
  }

  /// 极简折线:无数轴,只展示走势
  Widget _curve(List<double> values, {double height = 140}) {
    if (values.length < 2) return const SizedBox.shrink();
    return SizedBox(
      height: height,
      child: LineChart(LineChartData(
        lineBarsData: [
          LineChartBarData(
            spots: [
              for (var i = 0; i < values.length; i++)
                FlSpot(i.toDouble(), values[i])
            ],
            isCurved: true,
            dotData: const FlDotData(show: false),
            barWidth: 2,
          ),
        ],
        titlesData: const FlTitlesData(show: false),
        gridData: const FlGridData(show: false),
        borderData: FlBorderData(show: false),
      )),
    );
  }

  Widget _placesCard() {
    final places = (_places?['places'] as List? ?? []);
    if (places.isEmpty) {
      return const Text('暂无数据', style: TextStyle(color: Colors.grey));
    }
    return Card(
      child: Column(children: [
        for (final p in places)
          ListTile(
            dense: true,
            title: Text(p['name'] as String? ?? ''),
            trailing: Text('${p['amount'] ?? 0} 金币'),
          ),
      ]),
    );
  }

  Widget _richCard() {
    final rich = _rich ?? [];
    if (rich.isEmpty) {
      return const Text('暂无数据', style: TextStyle(color: Colors.grey));
    }
    return Card(
      child: Column(children: [
        for (var i = 0; i < rich.length; i++)
          ListTile(
            dense: true,
            leading: CircleAvatar(radius: 12, child: Text('${i + 1}',
                style: const TextStyle(fontSize: 12))),
            title: Text(rich[i]['name'] as String? ?? ''),
            subtitle: Text(rich[i]['occupation'] as String? ?? ''),
            trailing: Text('${rich[i]['netWorth'] ?? 0}'),
          ),
      ]),
    );
  }

  Widget _stocksCard() {
    final stocks = _stocks ?? [];
    if (stocks.isEmpty) {
      return const Text('暂无数据', style: TextStyle(color: Colors.grey));
    }
    return Column(children: [
      for (final st in stocks)
        Card(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child:
                Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Expanded(
                  child: Text('${st['name'] ?? ''} (${st['code'] ?? ''})',
                      style: const TextStyle(fontWeight: FontWeight.bold)),
                ),
                Text('${st['price'] ?? 0}',
                    style: const TextStyle(
                        fontSize: 16, fontWeight: FontWeight.bold)),
                const SizedBox(width: 8),
                _changePct(st['changePct']),
              ]),
              const SizedBox(height: 8),
              _curve([
                for (final d in (st['series'] as List? ?? []))
                  (d['close'] as num?)?.toDouble() ?? 0
              ], height: 80),
            ]),
          ),
        ),
    ]);
  }

  Widget _changePct(dynamic pct) {
    final v = (pct as num?)?.toDouble() ?? 0;
    final up = v >= 0;
    return Text('${up ? '+' : ''}${v.toStringAsFixed(2)}%',
        style: TextStyle(color: up ? Colors.red : Colors.green));
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);
  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 16, 4, 8),
      child: Text(text,
          style: const TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
    );
  }
}
