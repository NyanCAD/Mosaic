"""Tests for pure functions in nyancad.netlist.

Tests focus on the *intent* of each function — what it should do given its
contract — not on mirroring implementation details. Expected values are
hand-computed from first principles or derived from the ClojureScript
implementation (which is the source of truth for editor display).
"""

import pytest
from nyancad.netlist import (
    model_key,
    bare_id,
    SchemId,
    default_port_order,
    _select_corner,
    _eval_params,
    NyanCADMixin,
    NyanCircuit,
)


# ---------------------------------------------------------------------------
# model_key / bare_id — ID prefix conversion
# ---------------------------------------------------------------------------

class TestModelKey:
    """model_key adds 'models:' prefix to bare IDs."""

    def test_none_returns_none(self):
        assert model_key(None) is None

    def test_adds_prefix(self):
        assert model_key("abc-123") == "models:abc-123"

    def test_empty_string(self):
        assert model_key("") == "models:"

    def test_rejects_already_prefixed(self):
        with pytest.raises(AssertionError):
            model_key("models:abc")


class TestBareId:
    """bare_id strips 'models:' prefix from database keys."""

    def test_none_returns_none(self):
        assert bare_id(None) is None

    def test_strips_prefix(self):
        assert bare_id("models:abc-123") == "abc-123"

    def test_empty_bare_id(self):
        assert bare_id("models:") == ""

    def test_rejects_unprefixed(self):
        with pytest.raises(AssertionError):
            bare_id("abc-123")


class TestModelKeyBareIdRoundtrip:
    """model_key and bare_id are inverses."""

    @pytest.mark.parametrize("value", ["abc", "1ef9-uuid", "", "has:colon"])
    def test_roundtrip(self, value):
        assert bare_id(model_key(value)) == value


# ---------------------------------------------------------------------------
# SchemId — schematic:device ID parsing
# ---------------------------------------------------------------------------

class TestSchemId:
    """SchemId.from_string splits 'schematic:device' IDs."""

    def test_normal_id(self):
        sid = SchemId.from_string("myschematic:R1")
        assert sid.schem == "myschematic"
        assert sid.device == "R1"

    def test_no_device_part(self):
        sid = SchemId.from_string("myschematic")
        assert sid.schem == "myschematic"
        assert sid.device is None

    def test_extra_colons_ignored(self):
        sid = SchemId.from_string("a:b:c:d")
        assert sid.schem == "a"
        assert sid.device == "b"


# ---------------------------------------------------------------------------
# default_port_order — canonical SPICE arg order for subcircuit calls
# ---------------------------------------------------------------------------

class TestDefaultPortOrder:
    """default_port_order sorts model port names alphabetically.

    This is the fallback when a model entry doesn't declare ``port-order``.
    The SUBCKT definition and every X call use the same function, so the
    specific ordering is arbitrary as long as it's deterministic."""

    def test_sorts_by_name(self):
        ports = [
            {"name": "out", "side": "right"},
            {"name": "in+", "side": "left"},
            {"name": "in-", "side": "left"},
        ]
        assert default_port_order(ports) == ["in+", "in-", "out"]

    def test_input_order_irrelevant(self):
        """Same ports in different input order → same result."""
        ports_a = [{"name": "B"}, {"name": "A"}, {"name": "C"}]
        ports_b = [{"name": "A"}, {"name": "B"}, {"name": "C"}]
        assert default_port_order(ports_a) == default_port_order(ports_b)

    def test_empty(self):
        assert default_port_order([]) == []


# ---------------------------------------------------------------------------
# _default_port_list — SPICE port ordering for built-in types
# ---------------------------------------------------------------------------

class TestDefaultPortList:
    """_default_port_list returns canonical SPICE port order for built-in device types."""

    def _make_ports(self, names):
        """Helper: create a ports dict and lookup function from port names."""
        ports = {name: f"net_{name}" for name in names}
        return ports, lambda name: ports[name]

    def test_passive_devices(self):
        """Resistors, capacitors, etc. connect P (positive) then N (negative)."""
        for dtype in ['resistor', 'capacitor', 'inductor', 'vsource', 'isource', 'diode']:
            ports, p = self._make_ports(['P', 'N'])
            result = NyanCADMixin._default_port_list(dtype, ports, p)
            assert result == ["net_P", "net_N"], f"Failed for {dtype}"

    def test_mosfet_with_bulk(self):
        """MOSFET with bulk: Drain, Gate, Source, Bulk."""
        ports, p = self._make_ports(['D', 'G', 'S', 'B'])
        result = NyanCADMixin._default_port_list("nmos", ports, p)
        assert result == ["net_D", "net_G", "net_S", "net_B"]

    def test_mosfet_without_bulk(self):
        """MOSFET without bulk port: just D, G, S."""
        ports, p = self._make_ports(['D', 'G', 'S'])
        result = NyanCADMixin._default_port_list("pmos", ports, p)
        assert result == ["net_D", "net_G", "net_S"]

    def test_bjt(self):
        """BJT: Collector, Base, Emitter."""
        ports, p = self._make_ports(['C', 'B', 'E'])
        result = NyanCADMixin._default_port_list("npn", ports, p)
        assert result == ["net_C", "net_B", "net_E"]

    def test_unknown_type_returns_empty(self):
        """Unknown device types return empty list (handled as subcircuit elsewhere)."""
        ports, p = self._make_ports(['X'])
        result = NyanCADMixin._default_port_list("widget", ports, p)
        assert result == []


# ---------------------------------------------------------------------------
# _select_model_entry — choose SPICE model variant for simulator
# ---------------------------------------------------------------------------

class TestSelectModelEntry:
    """_select_model_entry picks the right SPICE model entry for a simulator."""

    def _select(self, model_def, sim):
        return NyanCADMixin()._select_model_entry(model_def, sim)

    def test_no_models_key(self):
        assert self._select({}, "NgSpice") is None

    def test_empty_models_list(self):
        assert self._select({"models": []}, "NgSpice") is None

    def test_no_spice_entries(self):
        model_def = {"models": [{"language": "verilog"}]}
        assert self._select(model_def, "NgSpice") is None

    def test_exact_sim_match(self, spice_model_def):
        entry = self._select(spice_model_def, "NgSpice")
        assert entry["name"] == "nmos_3p3"

    def test_case_insensitive_match(self, spice_model_def):
        entry = self._select(spice_model_def, "ngspice")
        assert entry["name"] == "nmos_3p3"

    def test_different_sim(self, spice_model_def):
        entry = self._select(spice_model_def, "Xyce")
        assert entry["name"] == "nmos_xyce"

    def test_fallback_to_first_spice(self, spice_model_def):
        """Unknown simulator falls back to first SPICE entry."""
        entry = self._select(spice_model_def, "UnknownSim")
        assert entry["name"] == "nmos_3p3"

    def test_single_spice_entry_always_returned(self):
        model_def = {"models": [{"language": "spice", "implementation": "Xyce"}]}
        entry = self._select(model_def, "NgSpice")
        # Even though sim doesn't match, it's the only SPICE entry — use it
        assert entry["implementation"] == "Xyce"


# ---------------------------------------------------------------------------
# _select_corner — library section selection
# ---------------------------------------------------------------------------

class TestSelectCorner:
    """_select_corner picks a library corner/section from available options."""

    def test_no_sections(self):
        assert _select_corner(None, ["tt"]) is None
        assert _select_corner([], ["tt"]) is None

    def test_no_corners_preference(self):
        """Without corner preference, use first available section."""
        assert _select_corner(["tt", "ff", "ss"], None) == "tt"

    def test_matching_corner(self):
        result = _select_corner(["tt", "ff", "ss"], ["ff"])
        assert result == "ff"

    def test_no_match_falls_back(self):
        """No matching corner → fall back to first section."""
        result = _select_corner(["tt", "ff"], ["ss"])
        assert result == "tt"

    def test_multiple_corners_picks_intersection(self):
        result = _select_corner(["tt", "ff", "ss"], ["ss", "ff"])
        assert result in ("ff", "ss")  # any match is valid


# ---------------------------------------------------------------------------
# _eval_params — expression evaluation for model parameters
# ---------------------------------------------------------------------------

class TestEvalParams:
    """_eval_params translates SPICE param expressions using device props.

    Two paths: bare-identifier expressions ("renames") pass the raw prop value
    through; arithmetic expressions are substituted and wrapped in braces so
    SPICE evaluates them. The rename passthrough preserves non-numeric values
    (model names) which would break if SPICE tried to evaluate them, and keeps
    the original prop type."""

    def test_empty_returns_device_props(self):
        props = {"resistance": "1k"}
        assert _eval_params(None, props) == props
        assert _eval_params({}, props) == props

    # --- Rename path: bare identifier, passthrough ---

    def test_rename_preserves_string_value(self):
        """Rename of SPICE-notation string: raw value through, no wrapping."""
        assert _eval_params({"w": "width"}, {"width": "10u"}) == {"w": "10u"}

    def test_rename_preserves_model_name(self):
        """Non-numeric values (like model names) must NOT be wrapped in braces,
        since SPICE would try to evaluate them as parameter expressions."""
        assert _eval_params({"model": "name"}, {"name": "nmos_3p3"}) == {"model": "nmos_3p3"}

    def test_rename_preserves_numeric_type(self):
        """Numeric prop value passes through with its original type."""
        assert _eval_params({"w": "width"}, {"width": 10}) == {"w": 10}
        assert _eval_params({"w": "width"}, {"width": 3.5}) == {"w": 3.5}

    def test_rename_missing_prop_skipped(self):
        """Missing rename target → skip so SPICE model's own default is used."""
        result = _eval_params(
            {"w": "width", "vth": "threshold"},
            {"width": "10"}  # no 'threshold'
        )
        assert result == {"w": "10"}
        assert "vth" not in result

    # --- Arithmetic path: substitute + wrap ---

    def test_arithmetic_substitutes_and_wraps(self):
        """Arithmetic is substituted and wrapped for SPICE to evaluate."""
        assert _eval_params({"w": "width * 1e-6"}, {"width": "10"}) == {"w": "{10 * 1e-6}"}

    def test_arithmetic_with_spice_suffix_notation(self):
        """SPICE suffix notation reaches SPICE intact — the main reason to let
        SPICE evaluate instead of Python. 'width * 1e-6' with width='10u'
        becomes '{10u * 1e-6}' which ngspice evaluates to 1e-11."""
        result = _eval_params({"w": "width * 1e-6"}, {"width": "10u"})
        assert result["w"] == "{10u * 1e-6}"

    def test_multiple_params(self):
        result = _eval_params(
            {"w": "width * 1e-6", "l": "length * 1e-6"},
            {"width": "5u", "length": "0.5u"}
        )
        assert result == {"w": "{5u * 1e-6}", "l": "{0.5u * 1e-6}"}

    def test_unknown_identifier_preserved(self):
        """Identifiers not in device_props stay as-is so SPICE can resolve them
        (e.g. SPICE math functions, model-level parameters)."""
        assert _eval_params({"x": "sqrt(width)"}, {"width": "10u"}) == {"x": "{sqrt(10u)}"}

    def test_identifier_regex_skips_numeric_literals(self):
        """Identifier match uses word boundary, so 'e' in '2e-3' is not
        mistaken for a variable — even if 'e' exists in device_props."""
        assert _eval_params({"x": "2e-3"}, {"e": "wrong"}) == {"x": "{2e-3}"}

    def test_constant_expression_wrapped(self):
        """Expressions with no variables still go through the arithmetic path."""
        assert _eval_params({"x": "1 + 2"}, {}) == {"x": "{1 + 2}"}

    # --- Mixed ---

    def test_mixed_rename_and_arithmetic(self):
        """A params mapping can mix rename and arithmetic entries."""
        result = _eval_params(
            {"model": "name", "w": "width * 1e-6"},
            {"name": "nmos_3p3", "width": "10u"}
        )
        assert result == {"model": "nmos_3p3", "w": "{10u * 1e-6}"}


# ---------------------------------------------------------------------------
# populate_from_nyancad — reads :nets off each device
# ---------------------------------------------------------------------------

class TestPopulateFromNyancad:
    """populate_from_nyancad reads pre-annotated `:nets` from each device doc
    and emits SPICE with those net names — no flood-fill, no geometry."""

    def _schem(self, devices):
        """Minimal full schem dict with a single top-level schematic."""
        return {"top": devices, "models": {}}

    def test_emits_nets_directly(self):
        """Resistor with :nets {'P': 'vdd', 'N': 'gnd'} produces
        a SPICE line referencing 'vdd' and 'gnd'."""
        schem = self._schem({
            "top:R1": {
                "_id": "top:R1",
                "type": "resistor",
                "x": 1, "y": 1,
                "transform": [1, 0, 0, 1, 0, 0],
                "name": "R1",
                "nets": {"P": "vdd", "N": "gnd"},
                "props": {"resistance": "1k"},
            }
        })
        spice = str(NyanCircuit("top", schem))
        assert "vdd" in spice
        assert "gnd" in spice
        assert "R1" in spice.replace(":", "_")

    def test_skips_devices_without_nets(self):
        """A device without :nets is treated as disconnected — no SPICE element
        is emitted for it. Legacy data degrades gracefully."""
        schem = self._schem({
            "top:R_disconnected": {
                "_id": "top:R_disconnected",
                "type": "resistor",
                "x": 1, "y": 1,
                "transform": [1, 0, 0, 1, 0, 0],
                "name": "Rd",
                "props": {"resistance": "1k"},
                # no "nets" key
            }
        })
        spice = str(NyanCircuit("top", schem))
        assert "Rd" not in spice

    def test_skips_structural_types(self):
        """Wires, text, and port docs never produce SPICE elements even if
        they accidentally carry a :nets field."""
        schem = self._schem({
            "top:W1": {
                "_id": "top:W1", "type": "wire",
                "x": 0, "y": 0, "rx": 1, "ry": 0, "name": "W1",
            },
            "top:T1": {
                "_id": "top:T1", "type": "text",
                "x": 0, "y": 0, "transform": [1, 0, 0, 1, 0, 0], "name": "T1",
            },
            "top:P1": {
                "_id": "top:P1", "type": "port",
                "x": 0, "y": 0, "transform": [1, 0, 0, 1, 0, 0], "name": "inp",
            },
        })
        # Should complete without error and emit no element lines.
        spice = str(NyanCircuit("top", schem))
        assert "W1" not in spice
        assert "T1" not in spice

    def test_distinct_nets_for_distinct_devices(self):
        """Two resistors with different :nets produce two independent
        SPICE elements referencing the declared nets."""
        schem = self._schem({
            "top:R1": {
                "_id": "top:R1", "type": "resistor",
                "x": 1, "y": 1, "transform": [1, 0, 0, 1, 0, 0], "name": "R1",
                "nets": {"P": "a", "N": "b"},
                "props": {"resistance": "1k"},
            },
            "top:R2": {
                "_id": "top:R2", "type": "resistor",
                "x": 5, "y": 5, "transform": [1, 0, 0, 1, 0, 0], "name": "R2",
                "nets": {"P": "b", "N": "c"},
                "props": {"resistance": "2k"},
            },
        })
        spice = str(NyanCircuit("top", schem))
        # Both devices present, all three nets referenced.
        assert "R1" in spice.replace(":", "_")
        assert "R2" in spice.replace(":", "_")
        for net in ("a", "b", "c"):
            assert net in spice
