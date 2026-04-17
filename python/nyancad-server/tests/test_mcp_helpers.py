"""Tests for pure helper functions in nyancad_server.mcp_server."""

import pytest
from nyancad_server.mcp_server import str_to_hex, normalize_to_bare_id, normalize_to_model_key


class TestStrToHex:
    """str_to_hex encodes each character as its hex Unicode code point.

    Used to build CouchDB userdb names: userdb-{str_to_hex(username)}.
    The ClojureScript version (common.cljc str-to-hex) uses the same
    encoding, so both languages produce matching database names.

    NOTE: This encodes Unicode CODE POINTS, not UTF-8 bytes. CouchDB's
    couch_peruser uses UTF-8 byte hex encoding. For ASCII usernames
    (the common case) the results are identical. For non-ASCII usernames
    they would differ — see test_non_ascii_differs_from_utf8_bytes."""

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
        # The real use case: userdb-{hex(username)}
        assert str_to_hex("admin") == "61646d696e"
        assert f"userdb-{str_to_hex('admin')}" == "userdb-61646d696e"

    def test_matches_cljs_str_to_hex(self):
        """Both Python and CLJS use charCode/ord encoding, so they must agree.
        The CLJS version is: (.toString (.charCodeAt % 0) 16)"""
        # These are the values the CLJS would produce for the same inputs
        assert str_to_hex("alice") == "616c696365"
        assert str_to_hex("test@example.com") == "74657374406578616d706c652e636f6d"

    def test_non_ascii_differs_from_utf8_bytes(self):
        """For non-ASCII chars, code point encoding differs from UTF-8 bytes.
        Both Python and CLJS produce the code-point version. CouchDB's
        couch_peruser would produce the UTF-8 version. They only match for
        ASCII usernames."""
        # 'é' = U+00E9 (code point 0xe9)
        # UTF-8 encoding: 0xc3 0xa9 (two bytes)
        codepoint_hex = str_to_hex("é")
        utf8_hex = "é".encode("utf-8").hex()
        assert codepoint_hex == "e9"      # what our function produces
        assert utf8_hex == "c3a9"          # what CouchDB would produce
        assert codepoint_hex != utf8_hex   # they disagree for non-ASCII

    def test_no_zero_padding(self):
        """Code points < 16 produce single hex digits (no zero padding).
        Not a practical issue since usernames don't contain control chars,
        but documents the behavior."""
        assert str_to_hex("\t") == "9"  # tab = 0x09, but no padding


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
