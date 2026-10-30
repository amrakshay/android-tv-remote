import 'dart:convert';
import 'dart:typed_data';

import 'package:asn1lib/asn1lib.dart';
import 'package:basic_utils/basic_utils.dart';
import 'package:crypto/crypto.dart' show sha256;
import 'package:pointycastle/export.dart' show RSAPrivateKey, RSAPublicKey;

class ClientIdentity {
  ClientIdentity(this.certPem, this.keyPem);
  final String certPem;
  final String keyPem;
}

/// Generate the self-signed client certificate the TV will remember.
/// Mirrors python-androidtvremote2: RSA 2048, CN=atvremote, SAN atvremote,
/// serial 1000, 10 year validity.
ClientIdentity generateClientIdentity({String hostname = 'atvremote'}) {
  final pair = CryptoUtils.generateRSAKeyPair(keySize: 2048);
  final priv = pair.privateKey as RSAPrivateKey;
  final pub = pair.publicKey as RSAPublicKey;
  final csr = X509Utils.generateRsaCsrPem({'CN': hostname}, priv, pub);
  final certPem = X509Utils.generateSelfSignedCertificate(
    priv,
    csr,
    3650,
    sans: [hostname],
    serialNumber: '1000',
  );
  return ClientIdentity(certPem, CryptoUtils.encodeRSAPrivateKeyToPem(priv));
}

/// Pull the RSA public key out of an X.509 certificate in DER form.
/// Needed for the TV's cert, which only exists on the live TLS session.
RSAPublicKey publicKeyFromCertDer(Uint8List der) {
  final cert = ASN1Parser(der).nextObject() as ASN1Sequence;
  final tbs = cert.elements[0] as ASN1Sequence;
  // The [0] EXPLICIT version tag is optional; SPKI sits 5 after the serial.
  final versionPresent = tbs.elements[0].tag == 0xA0;
  final spki = tbs.elements[(versionPresent ? 1 : 0) + 5];
  return CryptoUtils.rsaPublicKeyFromDERBytes(spki.encodedBytes);
}

RSAPublicKey publicKeyFromCertPem(String pem) {
  final b64 = pem
      .replaceAll(RegExp(r'-----(BEGIN|END) CERTIFICATE-----'), '')
      .replaceAll(RegExp(r'\s'), '');
  return publicKeyFromCertDer(base64.decode(b64));
}

/// Big-endian, minimal-length, left-padded to a whole number of bytes.
/// This exact encoding is what the pairing hash is defined over.
Uint8List bigIntToBytes(BigInt value) {
  var hex = value.toRadixString(16).toUpperCase();
  if (hex.length.isOdd) hex = '0$hex';
  final out = Uint8List(hex.length ~/ 2);
  for (var i = 0; i < out.length; i++) {
    out[i] = int.parse(hex.substring(i * 2, i * 2 + 2), radix: 16);
  }
  return out;
}

class PairingHash {
  PairingHash(this.digest, this.checksumOk);
  final Uint8List digest;
  final bool checksumOk;
}

/// SHA-256 over client modulus/exponent, server modulus/exponent and the nonce
/// (the last 4 hex characters of the code). The first 2 characters of the code
/// are a checksum over that digest, so a mistyped code is caught locally.
PairingHash computePairingHash({
  required RSAPublicKey clientKey,
  required RSAPublicKey serverKey,
  required String code,
}) {
  final nonceHex = code.substring(2);
  final nonce = Uint8List(nonceHex.length ~/ 2);
  for (var i = 0; i < nonce.length; i++) {
    nonce[i] = int.parse(nonceHex.substring(i * 2, i * 2 + 2), radix: 16);
  }
  final buf = BytesBuilder()
    ..add(bigIntToBytes(clientKey.modulus!))
    ..add(bigIntToBytes(clientKey.exponent!))
    ..add(bigIntToBytes(serverKey.modulus!))
    ..add(bigIntToBytes(serverKey.exponent!))
    ..add(nonce);
  final digest = Uint8List.fromList(sha256.convert(buf.takeBytes()).bytes);
  final expected = int.parse(code.substring(0, 2), radix: 16);
  return PairingHash(digest, digest[0] == expected);
}
