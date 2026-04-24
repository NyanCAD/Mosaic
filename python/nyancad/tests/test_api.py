"""Tests for the FileAPI local-files backend and the pure
ServerAPI._build_selector helper.

FileAPI reads/writes JSON files; rather than isolate a "pure" core, we
drive it against a fresh tmp_path copy of fixtures/ each test. This keeps
production code unchanged and gives real end-to-end coverage for the
local (no-server) deployment path.

ServerAPI methods that touch httpx are out of scope — those belong to the
Phase 5 CouchDB integration round. The selector builder is pure and
tested here for convenience.
"""

import asyncio
import json
import shutil
from pathlib import Path

import pytest

from nyancad.api import FileAPI, ServerAPI


FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture
def project_dir(tmp_path):
    """A fresh copy of the fixtures dir for each test. Safe to mutate."""
    dest = tmp_path / "project"
    shutil.copytree(FIXTURES, dest)
    return dest


def run_async(coro):
    """Drive an async callable synchronously without pytest-asyncio."""
    return asyncio.run(coro)


# ---------------------------------------------------------------------------
# FileAPI._read_file — static helper
# ---------------------------------------------------------------------------
# Contract: read a .nyancir/.nyanlib JSON file, return the {id: doc} dict
# with _id injected into each dict value. Missing or empty files return
# an empty dict (no exception). Non-dict values in the JSON (unlikely but
# possible) pass through without _id injection — the caller is expected
# to treat them as opaque.


class TestReadFile:
    def test_missing_file_returns_empty(self, tmp_path):
        assert FileAPI._read_file(tmp_path / "nope.nyancir") == {}

    def test_empty_file_returns_empty(self, tmp_path):
        p = tmp_path / "empty.nyancir"
        p.write_text("")
        assert FileAPI._read_file(p) == {}

    def test_whitespace_only_returns_empty(self, tmp_path):
        p = tmp_path / "ws.nyancir"
        p.write_text("   \n\t  \n")
        assert FileAPI._read_file(p) == {}

    def test_valid_json_injects_id(self, project_dir):
        docs = FileAPI._read_file(project_dir / "top.nyancir")
        # every dict value gets its key injected as _id
        for doc_id, doc in docs.items():
            assert doc["_id"] == doc_id, f"{doc_id} missing or wrong _id"

    def test_non_dict_values_pass_through(self, tmp_path):
        # Edge case: a model-file entry that somehow isn't a dict should
        # not crash the loader. FileAPI leaves it as-is.
        p = tmp_path / "odd.nyanlib"
        p.write_text(json.dumps({"models:a": {"name": "ok"},
                                 "models:b": "not a dict"}))
        docs = FileAPI._read_file(p)
        assert docs["models:a"]["_id"] == "models:a"
        assert docs["models:b"] == "not a dict"  # unchanged, no _id


# ---------------------------------------------------------------------------
# FileAPI.get_docs — group dispatch + prefix filtering
# ---------------------------------------------------------------------------
# Contract:
#   - group "models" reads models.nyanlib wholesale
#   - any other group "foo" reads foo.nyancir and filters to keys starting
#     with "foo:", so stray docs from other groups can't leak in
#   - missing file returns (None, {})


class TestGetDocs:
    def test_models_group_reads_nyanlib(self, project_dir):
        api = FileAPI(project_dir)
        seq, docs = run_async(api.get_docs("models"))
        assert seq is None  # FileAPI has no update sequence
        assert set(docs.keys()) == {"models:opamp_1",
                                    "models:nmos_ihp",
                                    "models:divider_ckt"}

    def test_circuit_group_reads_nyancir(self, project_dir):
        api = FileAPI(project_dir)
        _, docs = run_async(api.get_docs("top"))
        assert set(docs.keys()) == {"top:R1", "top:R2", "top:C1",
                                    "top:W1", "top:W2"}

    def test_circuit_group_filters_out_other_prefixes(self, project_dir):
        # top.nyancir contains one stray doc with id "other:stray" — the
        # prefix filter must drop it so the caller sees only top:* docs.
        api = FileAPI(project_dir)
        _, docs = run_async(api.get_docs("top"))
        assert "other:stray" not in docs

    def test_missing_group_returns_empty(self, project_dir):
        api = FileAPI(project_dir)
        _, docs = run_async(api.get_docs("nonexistent"))
        assert docs == {}


# ---------------------------------------------------------------------------
# FileAPI.get_library — filtering over models.nyanlib
# ---------------------------------------------------------------------------
# Contract: return models filtered by (name regex, case-insensitive) AND
# (tags subset, order-independent). Either filter can be omitted.


class TestGetLibrary:
    def test_no_filters_returns_all(self, project_dir):
        api = FileAPI(project_dir)
        out = run_async(api.get_library())
        assert len(out) == 3

    def test_name_filter_case_insensitive(self, project_dir):
        api = FileAPI(project_dir)
        # fixtures have "NMOS_3p3" — lowercased pattern must match
        out = run_async(api.get_library(filter="nmos"))
        assert set(out.keys()) == {"models:nmos_ihp"}

    def test_name_filter_matches_substring(self, project_dir):
        api = FileAPI(project_dir)
        out = run_async(api.get_library(filter="Divider"))
        assert set(out.keys()) == {"models:divider_ckt"}

    def test_tag_subset_matches(self, project_dir):
        api = FileAPI(project_dir)
        # "analog" alone appears on 2 of the 3 models
        out = run_async(api.get_library(tags=["analog"]))
        assert set(out.keys()) == {"models:opamp_1", "models:divider_ckt"}

    def test_tag_subset_order_independent(self, project_dir):
        api = FileAPI(project_dir)
        # Order of tags in the query must not matter
        a = run_async(api.get_library(tags=["IHP", "nmos"]))
        b = run_async(api.get_library(tags=["nmos", "IHP"]))
        assert set(a.keys()) == set(b.keys()) == {"models:nmos_ihp"}

    def test_tag_not_in_any_model_returns_empty(self, project_dir):
        api = FileAPI(project_dir)
        out = run_async(api.get_library(tags=["nonexistent"]))
        assert out == {}

    def test_filter_and_tags_combined_with_and(self, project_dir):
        api = FileAPI(project_dir)
        # name=opamp AND tags=[amplifier] → only the opamp
        out = run_async(api.get_library(filter="op", tags=["amplifier"]))
        assert set(out.keys()) == {"models:opamp_1"}

    def test_filter_excludes_name_even_with_matching_tag(self, project_dir):
        api = FileAPI(project_dir)
        # the divider has tag=analog but name doesn't contain "opamp"
        out = run_async(api.get_library(filter="opamp", tags=["analog"]))
        assert set(out.keys()) == {"models:opamp_1"}


# ---------------------------------------------------------------------------
# ServerAPI._build_selector — pure Mango selector builder
# ---------------------------------------------------------------------------
# Contract: produce a CouchDB Mango selector dict combining positional tag
# matches (flat selector keyed `tags.0`, `tags.1`, …) with a
# case-insensitive regex on name. Each input is optional. The method is
# pure — no httpx or CouchDB interaction is needed.


def _selector(instance_fn):
    """Helper: build a ServerAPI just enough to call _build_selector.

    ServerAPI.__init__ creates an httpx.AsyncClient; that's fine and cheap
    for synchronous tests since we never issue a request.
    """
    api = ServerAPI("http://localhost:5984/test")
    try:
        return instance_fn(api)
    finally:
        # no network was used; close quietly
        asyncio.run(api.close())


class TestBuildSelector:
    def test_empty_inputs(self):
        s = _selector(lambda api: api._build_selector(None, None))
        assert s == {}

    def test_tags_only(self):
        s = _selector(lambda api: api._build_selector(None, ["IHP", "nmos"]))
        assert s == {"tags.0": "IHP", "tags.1": "nmos"}

    def test_filter_only(self):
        s = _selector(lambda api: api._build_selector("opamp", None))
        assert s == {"name": {"$regex": "(?i)opamp"}}

    def test_tags_and_filter(self):
        s = _selector(lambda api: api._build_selector("op", ["analog"]))
        assert s == {"tags.0": "analog",
                     "name": {"$regex": "(?i)op"}}

    def test_regex_chars_pass_through_unescaped(self):
        # The builder does not escape filter input — regex metachars are
        # user-supplied on purpose (libman users type raw patterns).
        # Lock this behavior down so a future refactor that adds escaping
        # has to update the test on purpose.
        s = _selector(lambda api: api._build_selector(".*+", None))
        assert s == {"name": {"$regex": "(?i).*+"}}
