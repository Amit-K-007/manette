#!/usr/bin/env python3
"""
Generates protocol/test-vectors.json for the Manette protocol (v1).

This script is the REFERENCE for the vectors. It uses only the Python standard
library. Re-run it whenever docs/protocol.md changes a layout, then commit both
the script and the regenerated JSON.

    python3 generate_test_vectors.py > test-vectors.json
    (or)  python3 generate_test_vectors.py --out test-vectors.json

The script asserts its own sanity before emitting anything:
  * HKDF is checked against RFC 5869 test case 1.
  * Every generated packet is decoded again by an independent decoder and the
    result is compared to the inputs.
  * Every tamper vector is confirmed to fail verification.
"""
import argparse
import base64
import hashlib
import hmac
import json
import struct

VERSION = 1
MAGIC = b"MN"

T_HELLO, T_WELCOME, T_REJECT = 0x01, 0x02, 0x03
T_INPUT, T_PING, T_PONG = 0x10, 0x12, 0x13
T_RUMBLE, T_BYE = 0x20, 0x21

SALT_PAIR = b"manette-v1-pair"
INFO_SESSION = b"manette-v1-session"

BUTTONS = {
    "DPAD_UP": 0x0001, "DPAD_DOWN": 0x0002, "DPAD_LEFT": 0x0004,
    "DPAD_RIGHT": 0x0008, "START": 0x0010, "BACK": 0x0020,
    "L3": 0x0040, "R3": 0x0080, "LB": 0x0100, "RB": 0x0200,
    "GUIDE": 0x0400, "A": 0x1000, "B": 0x2000, "X": 0x4000, "Y": 0x8000,
}


# --------------------------------------------------------------------------
# Crypto primitives
# --------------------------------------------------------------------------
def hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    """RFC 5869 HKDF with SHA-256."""
    prk = hmac.new(salt if salt else b"\x00" * 32, ikm, hashlib.sha256).digest()
    okm, t, counter = b"", b"", 1
    while len(okm) < length:
        t = hmac.new(prk, t + info + bytes([counter]), hashlib.sha256).digest()
        okm += t
        counter += 1
    return okm[:length]


def tag(key: bytes, data: bytes, n: int) -> bytes:
    return hmac.new(key, data, hashlib.sha256).digest()[:n]


def derive_device_key(pairing_secret: bytes, device_id: bytes) -> bytes:
    return hkdf_sha256(pairing_secret, SALT_PAIR, device_id, 32)


def derive_session_key(device_key: bytes, client_nonce: bytes, server_nonce: bytes) -> bytes:
    return hkdf_sha256(device_key, client_nonce + server_nonce, INFO_SESSION, 32)


# --------------------------------------------------------------------------
# Encoders
# --------------------------------------------------------------------------
def header(msg_type: int, session_id: int) -> bytes:
    return MAGIC + bytes([VERSION, msg_type]) + struct.pack("<I", session_id)


def enc_input_body(session_id, seq, buttons, lt, rt, lx, ly, rx, ry) -> bytes:
    return header(T_INPUT, session_id) + struct.pack("<HHBBhhhh", seq, buttons, lt, rt, lx, ly, rx, ry)


def enc_hello_body(flags, device_id, client_nonce, name: str) -> bytes:
    nb = name.encode("utf-8")
    assert len(nb) <= 32
    return header(T_HELLO, 0) + bytes([flags]) + device_id + client_nonce + bytes([len(nb)]) + nb


def enc_welcome_body(session_id, flags, slot, rumble_hz_max, server_nonce) -> bytes:
    return header(T_WELCOME, session_id) + bytes([flags, slot]) + struct.pack("<H", rumble_hz_max) + server_nonce


def enc_reject(reason, max_version) -> bytes:
    return header(T_REJECT, 0) + bytes([reason, max_version])


def enc_ping_body(msg_type, session_id, timestamp) -> bytes:
    return header(msg_type, session_id) + struct.pack("<I", timestamp)


def enc_rumble_body(session_id, seq, large, small) -> bytes:
    return header(T_RUMBLE, session_id) + struct.pack("<HBB", seq, large, small)


def enc_bye_body(session_id) -> bytes:
    return header(T_BYE, session_id)


# --------------------------------------------------------------------------
# Independent decoders (used only for self-checking the generator)
# --------------------------------------------------------------------------
def dec_header(pkt: bytes):
    magic, ver, typ, sid = struct.unpack_from("<2sBBI", pkt, 0)
    assert magic == MAGIC and ver == VERSION
    return typ, sid


def dec_input(pkt: bytes):
    assert len(pkt) == 30
    typ, sid = dec_header(pkt)
    assert typ == T_INPUT
    seq, buttons, lt, rt, lx, ly, rx, ry = struct.unpack_from("<HHBBhhhh", pkt, 8)
    return dict(session_id=sid, seq=seq, buttons=buttons, lt=lt, rt=rt, lx=lx, ly=ly, rx=rx, ry=ry)


def verify(pkt: bytes, key: bytes, tag_len: int, extra: bytes = b"") -> bool:
    body, got = pkt[:-tag_len], pkt[-tag_len:]
    return hmac.compare_digest(tag(key, body + extra, tag_len), got)


def seq_is_newer(last: int, new: int) -> bool:
    diff = (new - last) & 0xFFFF
    signed = diff - 0x10000 if diff >= 0x8000 else diff
    return signed > 0


def screen_y_to_wire(screen_y: int) -> int:
    """Screen Y grows downward; wire Y grows upward. Negate and clamp to i16."""
    return max(-32768, min(32767, -screen_y))


def h(b: bytes) -> str:
    return b.hex()


def flip_bit(pkt: bytes, byte_index: int, bit: int = 0) -> bytes:
    b = bytearray(pkt)
    b[byte_index] ^= 1 << bit
    return bytes(b)


# --------------------------------------------------------------------------
# Build
# --------------------------------------------------------------------------
def build() -> dict:
    # --- sanity: RFC 5869 test case 1 ------------------------------------
    rfc_ikm = bytes.fromhex("0b" * 22)
    rfc_salt = bytes.fromhex("000102030405060708090a0b0c")
    rfc_info = bytes.fromhex("f0f1f2f3f4f5f6f7f8f9")
    rfc_okm = hkdf_sha256(rfc_ikm, rfc_salt, rfc_info, 42)
    assert h(rfc_okm) == (
        "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
        "34007208d5b887185865"
    ), "HKDF implementation does not match RFC 5869 test case 1"

    # --- fixed inputs ----------------------------------------------------
    pairing_secret = bytes(range(0x00, 0x10))            # 00 01 ... 0f
    device_id = bytes(range(0xA0, 0xB0))                 # a0 a1 ... af
    client_nonce = bytes(range(0xC0, 0xC8))              # c0 ... c7
    server_nonce = bytes(range(0xD0, 0xD8))              # d0 ... d7
    other_client_nonce = bytes(range(0xE0, 0xE8))        # for the replay-binding vector

    device_key = derive_device_key(pairing_secret, device_id)
    session_key = derive_session_key(device_key, client_nonce, server_nonce)
    session_id = 42

    out = {
        "spec_version": VERSION,
        "generated_by": "protocol/generate_test_vectors.py (do not edit this file by hand)",
        "conventions": {
            "encoding": "all byte strings are lowercase hex; multi-byte integers on the wire are little-endian",
            "tag_lengths": {"handshake": 16, "session": 8},
            "tag_rule": "tag = first N bytes of HMAC-SHA256(key, all bytes before the tag); WELCOME additionally appends client_nonce to the HMAC input",
            "hkdf": "HKDF-SHA256 per RFC 5869, output length 32",
            "device_key": 'HKDF(ikm=pairing_secret, salt=ASCII "manette-v1-pair", info=device_id)',
            "session_key": 'HKDF(ikm=device_key, salt=client_nonce||server_nonce, info=ASCII "manette-v1-session")',
        },
    }

    # --- HKDF sanity vector ---------------------------------------------
    out["hkdf_rfc5869_case1"] = {
        "note": "Implementations must reproduce this before anything else; it proves the HKDF itself is right.",
        "ikm": h(rfc_ikm), "salt": h(rfc_salt), "info": h(rfc_info),
        "length": 42, "okm": h(rfc_okm),
    }

    # --- fixed inputs and derived keys ----------------------------------
    out["fixed_inputs"] = {
        "pairing_secret": h(pairing_secret),
        "device_id": h(device_id),
        "client_nonce": h(client_nonce),
        "server_nonce": h(server_nonce),
        "other_client_nonce": h(other_client_nonce),
        "session_id": session_id,
    }
    out["derived_keys"] = {
        "device_key": h(device_key),
        "session_key": h(session_key),
    }

    # --- pairing code formats -------------------------------------------
    b32 = base64.b32encode(pairing_secret).decode().rstrip("=")
    assert len(b32) == 26
    typed = "-".join(b32[i:i + 4] for i in range(0, 26, 4))
    out["pairing_code"] = {
        "secret": h(pairing_secret),
        "base32_unpadded": b32,
        "typed_code": typed,
        "qr_uri_example": f"manette://pair?v=1&h=192.168.1.20,192.168.137.1&p=26760&s={b32}&n=DESKTOP-GAMING",
        "note": "Typed codes must be accepted case-insensitively and with or without the dashes.",
    }

    # --- INPUT packets ---------------------------------------------------
    def input_vec(name, desc, seq, buttons=0, lt=0, rt=0, lx=0, ly=0, rx=0, ry=0):
        body = enc_input_body(session_id, seq, buttons, lt, rt, lx, ly, rx, ry)
        t = tag(session_key, body, 8)
        pkt = body + t
        assert len(pkt) == 30
        dec = dec_input(pkt)
        assert dec == dict(session_id=session_id, seq=seq, buttons=buttons, lt=lt, rt=rt, lx=lx, ly=ly, rx=rx, ry=ry)
        assert verify(pkt, session_key, 8)
        return {
            "name": name, "description": desc,
            "session_id": session_id, "seq": seq,
            "state": {"buttons": buttons, "lt": lt, "rt": rt, "lx": lx, "ly": ly, "rx": rx, "ry": ry},
            "body_hex": h(body), "tag_hex": h(t), "packet_hex": h(pkt),
        }

    inputs = [
        input_vec("all_released", "Neutral state", 0),
        input_vec("spec_worked_example",
                  "The example in protocol.md section 12: A pressed, rt full, lx half right, rx full left",
                  1, buttons=BUTTONS["A"], rt=255, lx=16384, rx=-32768),
    ]
    for i, (bname, mask) in enumerate(BUTTONS.items()):
        inputs.append(input_vec(f"button_{bname}", f"Only {bname} pressed", 10 + i, buttons=mask))
    inputs += [
        input_vec("all_buttons", "Every defined button pressed at once", 100,
                  buttons=sum(BUTTONS.values())),
        input_vec("triggers_full", "Both triggers fully pressed", 101, lt=255, rt=255),
        input_vec("triggers_mid", "Distinct trigger values, catches lt/rt swap", 102, lt=64, rt=192),
        input_vec("sticks_min", "All four axes at minimum", 103, lx=-32768, ly=-32768, rx=-32768, ry=-32768),
        input_vec("sticks_max", "All four axes at maximum", 104, lx=32767, ly=32767, rx=32767, ry=32767),
        input_vec("sticks_distinct", "Four distinct axis values, catches any axis swap or sign error",
                  105, lx=1000, ly=-2000, rx=3000, ry=-4000),
        input_vec("seq_wrap_high", "seq at 65535", 65535, buttons=BUTTONS["B"]),
        input_vec("seq_wrap_low", "seq wrapped to 0", 0, buttons=BUTTONS["B"]),
    ]
    out["input_packets"] = inputs

    # --- Y-axis inversion -------------------------------------------------
    out["screen_to_wire_y"] = {
        "rule": "wire_y = clamp(-screen_y, -32768, 32767). Screen Y grows downward, wire Y grows upward.",
        "cases": [
            {"screen_y": sy, "wire_y": screen_y_to_wire(sy)}
            for sy in (-32768, -16384, -1, 0, 1, 16384, 32767)
        ],
    }

    # --- sequence comparison ---------------------------------------------
    out["seq_is_newer"] = {
        "rule": "newer iff (int16)(new - last) > 0, using wraparound u16 subtraction",
        "cases": [
            {"last": a, "new": b, "newer": seq_is_newer(a, b)}
            for a, b in [(0, 1), (5, 5), (5, 6), (6, 5), (65535, 0), (0, 65535),
                         (65534, 1), (0, 32767), (0, 32768), (0, 32769), (100, 40000)]
        ],
    }

    # --- handshake -------------------------------------------------------
    hello_body = enc_hello_body(0x01, device_id, client_nonce, "Pixel 7")
    hello_tag = tag(device_key, hello_body, 16)
    hello_pkt = hello_body + hello_tag
    assert verify(hello_pkt, device_key, 16)

    hello_empty_body = enc_hello_body(0x00, device_id, client_nonce, "")
    hello_empty_pkt = hello_empty_body + tag(device_key, hello_empty_body, 16)
    assert verify(hello_empty_pkt, device_key, 16)

    welcome_body = enc_welcome_body(session_id, 0x02, 0, 30, server_nonce)
    welcome_tag = tag(device_key, welcome_body + client_nonce, 16)
    welcome_pkt = welcome_body + welcome_tag
    assert verify(welcome_pkt, device_key, 16, extra=client_nonce)
    assert not verify(welcome_pkt, device_key, 16, extra=other_client_nonce)

    welcome_res_body = enc_welcome_body(session_id, 0x01, 2, 0, server_nonce)
    welcome_res_pkt = welcome_res_body + tag(device_key, welcome_res_body + client_nonce, 16)

    reject_pkt = enc_reject(3, 1)

    out["handshake"] = {
        "hello_pairing": {
            "description": "HELLO with PAIRING flag, name 'Pixel 7'",
            "fields": {"flags": 1, "device_id": h(device_id), "client_nonce": h(client_nonce), "name": "Pixel 7"},
            "body_hex": h(hello_body), "tag_hex": h(hello_tag), "packet_hex": h(hello_pkt),
            "length": len(hello_pkt),
        },
        "hello_trusted_empty_name": {
            "description": "HELLO from an already-trusted device, empty name (name_len = 0)",
            "fields": {"flags": 0, "device_id": h(device_id), "client_nonce": h(client_nonce), "name": ""},
            "packet_hex": h(hello_empty_pkt), "length": len(hello_empty_pkt),
        },
        "welcome_paired_now": {
            "description": "WELCOME completing a pairing: PAIRED_NOW flag, slot 0, rumble 30 Hz",
            "fields": {"session_id": session_id, "flags": 2, "slot": 0, "rumble_hz_max": 30,
                       "server_nonce": h(server_nonce)},
            "verify_with_key": "device_key",
            "extra_hmac_input_hex": h(client_nonce),
            "body_hex": h(welcome_body), "tag_hex": h(welcome_tag), "packet_hex": h(welcome_pkt),
            "length": len(welcome_pkt),
        },
        "welcome_resumed": {
            "description": "WELCOME for a resumed session: RESUMED flag, slot 2, rumble disabled",
            "fields": {"session_id": session_id, "flags": 1, "slot": 2, "rumble_hz_max": 0,
                       "server_nonce": h(server_nonce)},
            "verify_with_key": "device_key",
            "extra_hmac_input_hex": h(client_nonce),
            "packet_hex": h(welcome_res_pkt), "length": len(welcome_res_pkt),
        },
        "welcome_wrong_binding": {
            "description": "The first WELCOME verified against a DIFFERENT client_nonce must fail (replay binding)",
            "packet_hex": h(welcome_pkt),
            "verify_with_key": "device_key",
            "extra_hmac_input_hex": h(other_client_nonce),
            "expected_valid": False,
        },
        "reject_server_full": {
            "description": "REJECT reason 3 (server full), max_version 1. Unauthenticated, so there is no tag.",
            "fields": {"reason": 3, "max_version": 1},
            "packet_hex": h(reject_pkt), "length": len(reject_pkt),
        },
    }

    # --- session messages ---------------------------------------------------
    def session_msg(body: bytes):
        t = tag(session_key, body, 8)
        pkt = body + t
        assert verify(pkt, session_key, 8)
        return {"body_hex": h(body), "tag_hex": h(t), "packet_hex": h(pkt), "length": len(pkt)}

    out["session_messages"] = {
        "ping": {"description": "PING, timestamp 123456 ms",
                 "fields": {"session_id": session_id, "timestamp": 123456},
                 **session_msg(enc_ping_body(T_PING, session_id, 123456))},
        "pong": {"description": "PONG echoing the same timestamp",
                 "fields": {"session_id": session_id, "timestamp": 123456},
                 **session_msg(enc_ping_body(T_PONG, session_id, 123456))},
        "rumble": {"description": "RUMBLE seq 7, large 200, small 50",
                   "fields": {"session_id": session_id, "seq": 7, "large": 200, "small": 50},
                   **session_msg(enc_rumble_body(session_id, 7, 200, 50))},
        "bye": {"description": "BYE",
                "fields": {"session_id": session_id},
                **session_msg(enc_bye_body(session_id))},
    }

    # --- tamper vectors (must all FAIL verification) -----------------------
    base_input = bytes.fromhex(inputs[1]["packet_hex"])
    tamper = [
        {"name": "input_flipped_button_bit",
         "description": "Spec worked-example INPUT with one buttons bit flipped",
         "packet_hex": h(flip_bit(base_input, 11)), "key": "session_key", "tag_len": 8},
        {"name": "input_flipped_session_id",
         "description": "INPUT with the session_id altered",
         "packet_hex": h(flip_bit(base_input, 4)), "key": "session_key", "tag_len": 8},
        {"name": "input_flipped_tag_bit",
         "description": "INPUT with one tag bit flipped",
         "packet_hex": h(flip_bit(base_input, 29)), "key": "session_key", "tag_len": 8},
        {"name": "input_wrong_key",
         "description": "Valid INPUT verified with device_key instead of session_key",
         "packet_hex": h(base_input), "key": "device_key", "tag_len": 8},
        {"name": "hello_flipped_name_bit",
         "description": "HELLO with one bit of the name flipped",
         "packet_hex": h(flip_bit(hello_pkt, 35)), "key": "device_key", "tag_len": 16},
        {"name": "hello_flipped_pairing_flag",
         "description": "HELLO with the PAIRING flag cleared",
         "packet_hex": h(flip_bit(hello_pkt, 8)), "key": "device_key", "tag_len": 16},
        {"name": "welcome_flipped_slot",
         "description": "WELCOME with the slot byte changed",
         "packet_hex": h(flip_bit(welcome_pkt, 9)), "key": "device_key", "tag_len": 16,
         "extra_hmac_input_hex": h(client_nonce)},
        {"name": "truncated_input",
         "description": "INPUT truncated to 29 bytes; must be dropped on length before any HMAC work",
         "packet_hex": h(base_input[:-1]), "key": "session_key", "tag_len": 8,
         "note": "Reject for wrong length, not just wrong tag"},
    ]
    keys = {"session_key": session_key, "device_key": device_key}
    for t_ in tamper:
        pkt = bytes.fromhex(t_["packet_hex"])
        extra = bytes.fromhex(t_.get("extra_hmac_input_hex", ""))
        if t_["name"] == "truncated_input":
            assert len(pkt) != 30
        else:
            assert not verify(pkt, keys[t_["key"]], t_["tag_len"], extra), t_["name"]
        t_["expected_valid"] = False
    out["tamper_must_fail"] = tamper

    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", help="write to this file instead of stdout")
    args = ap.parse_args()
    text = json.dumps(build(), indent=2) + "\n"
    if args.out:
        with open(args.out, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
    else:
        print(text, end="")


if __name__ == "__main__":
    main()
