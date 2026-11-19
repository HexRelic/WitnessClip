#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 HexRelic
"""Checks RFC 3161 tokens against a national trusted list (ETSI TS 119 612 XML).

For every certificate inside each token it reports whether that certificate, or its public key
(X509SKI / subject name), is the digital identity of a service on the list, and prints that
service's type and current status.

Usage: tsl_check.py TSL.xml token1.tsr [token2.tsr ...]
Needs: openssl on PATH. Reads only; writes nothing.
"""
import sys, re, base64, subprocess, hashlib

def certs_in_token(path):
    pem = subprocess.run(["openssl", "pkcs7", "-inform", "DER", "-in", path, "-print_certs"], capture_output=True, text=True).stdout
    if "BEGIN CERTIFICATE" not in pem:  # a .tsr holds a TimeStampToken, which is itself a PKCS#7
        pem = subprocess.run(["openssl", "ts", "-reply", "-in", path, "-token_in", "-token_out"], capture_output=True).stdout
        pem = subprocess.run(["openssl", "pkcs7", "-inform", "DER", "-print_certs"], input=pem, capture_output=True).stdout.decode()
    out = []
    for m in re.finditer(r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", pem, re.S):
        der = base64.b64decode(m.group(1))
        one = "-----BEGIN CERTIFICATE-----" + m.group(1) + "-----END CERTIFICATE-----\n"
        def q(*a): return subprocess.run(["openssl", "x509", "-noout", *a], input=one, capture_output=True, text=True).stdout.strip()
        spki = subprocess.run(["openssl", "pkey", "-pubin", "-outform", "DER"], input=subprocess.run(["openssl", "x509", "-noout", "-pubkey"], input=one.encode(), capture_output=True).stdout, capture_output=True).stdout
        ski = q("-ext", "subjectKeyIdentifier").split("\n")[-1].strip().replace(":", "")
        out.append(dict(der=der, subject=q("-subject"), ski=ski, cert_sha256=hashlib.sha256(der).hexdigest(), spki_sha256=hashlib.sha256(spki).hexdigest()))
    return out

tsl = open(sys.argv[1], encoding="utf-8", errors="replace").read()
services = re.findall(r"<(?:\w+:)?TSPService>(.*?)</(?:\w+:)?TSPService>", tsl, re.S)
print(f"trusted list: {len(services)} services, issued", (re.search(r"<(?:\w+:)?ListIssueDateTime>(.*?)<", tsl) or [None, "?"])[1])
def field(s, tag):
    m = re.search(rf"<(?:\w+:)?{tag}>(.*?)</(?:\w+:)?{tag}>", s, re.S)
    return re.sub(r"<[^>]+>", "", m.group(1)).strip() if m else ""
for path in sys.argv[2:]:
    print(f"\n== {path}")
    for c in certs_in_token(path):
        b64 = base64.b64encode(c["der"]).decode()
        ski_b64 = base64.b64encode(bytes.fromhex(c["ski"])).decode() if re.fullmatch(r"[0-9A-Fa-f]+", c["ski"] or "") else None
        hits = []
        for s in services:
            info = s.split("ServiceHistory")[0]
            flat = re.sub(r"\s+", "", info)
            how = "certificate" if b64[:200] in flat and b64[-100:] in flat else ("public key id" if ski_b64 and f">{ski_b64}<" in flat else None)
            if how: hits.append((how, field(info, "ServiceTypeIdentifier").rsplit("/", 1)[-1], field(info, "ServiceStatus").rsplit("/", 1)[-1], field(info, "ServiceName")))
        print(f"  {c['subject']}\n    cert sha256 {c['cert_sha256']}\n    spki sha256 {c['spki_sha256']}")
        for h in hits: print(f"    LISTED by {h[0]}: type={h[1]} status={h[2]} name={h[3]}")
        if not hits: print("    not itself listed")
