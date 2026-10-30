import 'dart:io';
import 'dart:typed_data';

import 'package:atv_spike/atv.dart';
import 'package:atv_spike/src/framing.dart';
import 'package:test/test.dart';

String hex(Uint8List b) =>
    b.map((x) => x.toRadixString(16).padLeft(2, '0')).join();

void main() {
  group('framing', () {
    Uint8List msg(int len) => Uint8List.fromList(List.filled(len, 0x41));

    test('one whole message', () {
      final r = FrameReader();
      expect(r.add(FrameReader.frame(msg(5))).length, 1);
    });

    test('several messages in one read', () {
      final r = FrameReader();
      final wire = [...FrameReader.frame(msg(3)), ...FrameReader.frame(msg(4))];
      expect(r.add(wire).length, 2);
    });

    test('message split across reads', () {
      final r = FrameReader();
      final wire = FrameReader.frame(msg(10));
      expect(r.add(wire.sublist(0, 4)), isEmpty);
      expect(r.add(wire.sublist(4)).length, 1);
    });

    test('split inside a multi-byte varint', () {
      final r = FrameReader();
      // 300 bytes needs a 2-byte varint; split between the two.
      final wire = FrameReader.frame(msg(300));
      expect(r.add(wire.sublist(0, 1)), isEmpty);
      final out = r.add(wire.sublist(1));
      expect(out.length, 1);
      expect(out.first.length, 300);
    });

    test('byte-at-a-time delivery', () {
      final r = FrameReader();
      final wire = FrameReader.frame(msg(200));
      var got = 0;
      for (final b in wire) {
        got += r.add([b]).length;
      }
      expect(got, 1);
    });
  });

  group('pairing hash matches the Python reference', () {
    final clientKey = publicKeyFromCertPem(
        File('test/fixtures/client.pem').readAsStringSync());
    final serverKey = publicKeyFromCertPem(
        File('test/fixtures/server.pem').readAsStringSync());

    // Vectors produced by python-androidtvremote2 for the same two certs.
    const vectors = {
      '00ABCD':
          '29676ead55845d831ce82161b67d1901665412c67e51a109521632de42e5d550',
      'FF1234':
          'f5c755b693112808a61e5f8ceec19ad921c6a320b32a73a95fc84a1ac47f762b',
      '0A0000':
          '958dfadc2df327a0a47016095858db38a85e721382e35c8aca6b64109dde72e6',
    };

    vectors.forEach((code, expected) {
      test('code $code', () {
        final h = computePairingHash(
            clientKey: clientKey, serverKey: serverKey, code: code);
        expect(hex(h.digest), expected);
      });
    });

    test('checksum byte is validated', () {
      // 0x29.. is the digest for 00ABCD, so a code claiming 0x29 is consistent.
      expect(
          computePairingHash(
                  clientKey: clientKey, serverKey: serverKey, code: '29ABCD')
              .checksumOk,
          isTrue);
      expect(
          computePairingHash(
                  clientKey: clientKey, serverKey: serverKey, code: '00ABCD')
              .checksumOk,
          isFalse);
    });
  });

  group('certificate generation', () {
    test('produces a cert whose key round-trips', () {
      final id = generateClientIdentity();
      expect(id.certPem, contains('BEGIN CERTIFICATE'));
      final key = publicKeyFromCertPem(id.certPem);
      expect(key.modulus!.bitLength, 2048);
      expect(key.exponent, BigInt.from(65537));
    });
  });
}
