"""Tests for pure helper functions in nyancad_server.mcp_server."""

import pytest
from nyancad_server.mcp_server import str_to_hex, normalize_to_bare_id, normalize_to_model_key


class TestStrToHex:
    """str_to_hex encodes a string as UTF-8 bytes in hex.

    Used to build CouchDB userdb names: userdb-{str_to_hex(username)}.
    CouchDB's couch_peruser uses hex(utf8(username)) with zero-padded
    per-byte encoding. The ClojureScript str-to-hex in common.cljc uses
    the same encoding — both must stay consistent."""

    def test_lowercase_ascii(self):
        assert str_to_hex("alice") == "616c696365"

    def test_uppercase_ascii(self):
        assert str_to_hex("AB") == "4142"

    def test_empty_string(self):
        assert str_to_hex("") == ""

    def test_space(self):
        assert str_to_hex(" ") == "20"

    def test_digits(self):
        assert str_to_hex("09") == "3039"

    def test_couchdb_userdb_name(self):
        """The real use case: userdb-{hex(username)}."""
        assert str_to_hex("admin") == "61646d696e"
        assert f"userdb-{str_to_hex('admin')}" == "userdb-61646d696e"

    def test_matches_python_encode_utf8_hex(self):
        """str_to_hex equals s.encode('utf-8').hex() — the canonical
        Python expression for UTF-8 byte hex encoding."""
        for s in ["alice", "test@example.com", "", " ", "é", "日本語"]:
            assert str_to_hex(s) == s.encode("utf-8").hex(), f"Failed for {s!r}"

    def test_non_ascii_utf8_bytes(self):
        """Non-ASCII chars encode as UTF-8 bytes (not Unicode code points).
        'é' = U+00E9 → UTF-8 bytes 0xc3 0xa9 → 'c3a9'."""
        assert str_to_hex("é") == "c3a9"

    def test_multi_byte_unicode(self):
        """Each UTF-8 byte becomes 2 hex digits, preserving decodability."""
        # '日' = U+65E5 → UTF-8: 0xe6 0x97 0xa5
        assert str_to_hex("日") == "e697a5"

    def test_zero_padded_low_bytes(self):
        """Bytes < 16 produce 2 hex digits with leading zero so concatenation
        is unambiguously decodable."""
        assert str_to_hex("\t") == "09"  # tab = 0x09
        assert str_to_hex("\n") == "0a"  # newline = 0x0a

    def test_roundtrip_via_bytes(self):
        """Encoding roundtrips through UTF-8 decode."""
        for s in ["alice", "é", "日本語", "admin-123"]:
            assert bytes.fromhex(str_to_hex(s)).decode("utf-8") == s


class TestNormalizeToBareId:
    """normalize_to_bare_id strips 'models:' prefix if present, passes through bare IDs."""

    def test_bare_id_unchanged(self):
        assert normalize_to_bare_id("abc-123") == "abc-123"

    def test_strips_prefix(self):
        assert normalize_to_bare_id("models:abc-123") == "abc-123"

    def test_idempotent(self):
        once = normalize_to_bare_id("models:xyz")
        twice = normalize_to_bare_id(once)
        assert once == twice == "xyz"

    def test_empty_bare_id(self):
        # "models:" with empty bare part
        assert normalize_to_bare_id("models:") == ""

    def test_does_not_strip_other_prefixes(self):
        assert normalize_to_bare_id("other:abc") == "other:abc"


class TestNormalizeToModelKey:
    """normalize_to_model_key adds 'models:' prefix if missing, passes through prefixed IDs."""

    def test_adds_prefix(self):
        assert normalize_to_model_key("abc-123") == "models:abc-123"

    def test_already_prefixed_unchanged(self):
        assert normalize_to_model_key("models:abc-123") == "models:abc-123"

    def test_idempotent(self):
        once = normalize_to_model_key("xyz")
        twice = normalize_to_model_key(once)
        assert once == twice == "models:xyz"

    def test_empty_id(self):
        assert normalize_to_model_key("") == "models:"


class TestNormalizeRoundtrip:
    """The two normalize functions are inverses for valid inputs."""

    def test_bare_to_key_to_bare(self):
        bare = "1ef9c9d7-abcd-1234"
        assert normalize_to_bare_id(normalize_to_model_key(bare)) == bare

    def test_key_to_bare_to_key(self):
        key = "models:1ef9c9d7-abcd-1234"
        assert normalize_to_model_key(normalize_to_bare_id(key)) == key
