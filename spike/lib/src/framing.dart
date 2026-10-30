import 'dart:typed_data';

/// Length-delimited protobuf framing: [varint length][message bytes].
class FrameReader {
  Uint8List _pending = Uint8List(0);

  /// Feed raw socket bytes; returns every complete message now available.
  List<Uint8List> add(List<int> data) {
    final combined = Uint8List(_pending.length + data.length)
      ..setAll(0, _pending)
      ..setAll(_pending.length, data);
    _pending = combined;
    final out = <Uint8List>[];
    var offset = 0;
    while (true) {
      final header = _readVarint(_pending, offset);
      if (header == null) break; // varint itself incomplete
      final (len, pos) = header;
      if (_pending.length - pos < len) break; // body incomplete
      out.add(Uint8List.sublistView(_pending, pos, pos + len));
      offset = pos + len;
    }
    _pending = Uint8List.fromList(_pending.sublist(offset));
    return out;
  }

  static (int, int)? _readVarint(Uint8List b, int start) {
    var result = 0, shift = 0, i = start;
    while (true) {
      if (i >= b.length) return null;
      final byte = b[i++];
      result |= (byte & 0x7F) << shift;
      if (byte & 0x80 == 0) return (result, i);
      shift += 7;
      if (shift > 35) throw FormatException('varint too long');
    }
  }

  static Uint8List encodeVarint(int value) {
    final out = <int>[];
    var v = value;
    while (v > 0x7F) {
      out.add((v & 0x7F) | 0x80);
      v >>= 7;
    }
    out.add(v);
    return Uint8List.fromList(out);
  }

  /// Frame a serialized message for sending.
  static Uint8List frame(List<int> message) =>
      Uint8List.fromList([...encodeVarint(message.length), ...message]);
}
