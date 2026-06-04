"""Tests for pure functions in nyancad.netlist.

Tests focus on the *intent* of each function — what it should do given its
contract — not on mirroring implementation details. Expected values are
hand-computed from first principles or derived from the ClojureScript
implementation (which is the source of truth for editor display).
"""

import pytest
from nyancad.netlist import (
    NyanCADMixin,
    NyanCircuit,
    SchemId,
    _eval_params,
    _select_corner,
    bare_id,
    default_port_order,
    kfnetlist_from_nyancad,
    model_key,
    recursive_kfnetlist_from_nyancad,
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
    specific ordering is arbitrary as long as it's deterministic.
    """

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
    """_default_port_list returns canonical SPICE port order for built-in
    device types.
    """

    def _make_ports(self, names):
        """Helper: create a ports dict and lookup function from port names."""
        ports = {name: f"net_{name}" for name in names}
        return ports, lambda name: ports[name]

    def test_passive_devices(self):
        """Resistors, capacitors, etc. connect P (positive) then N (negative)."""
        for dtype in [
            "resistor",
            "capacitor",
            "inductor",
            "vsource",
            "isource",
            "diode",
        ]:
            ports, p = self._make_ports(["P", "N"])
            result = NyanCADMixin._default_port_list(dtype, ports, p)
            assert result == ["net_P", "net_N"], f"Failed for {dtype}"

    def test_mosfet_with_bulk(self):
        """MOSFET with bulk: Drain, Gate, Source, Bulk."""
        ports, p = self._make_ports(["D", "G", "S", "B"])
        result = NyanCADMixin._default_port_list("nmos", ports, p)
        assert result == ["net_D", "net_G", "net_S", "net_B"]

    def test_mosfet_without_bulk(self):
        """MOSFET without bulk port: just D, G, S."""
        ports, p = self._make_ports(["D", "G", "S"])
        result = NyanCADMixin._default_port_list("pmos", ports, p)
        assert result == ["net_D", "net_G", "net_S"]

    def test_bjt(self):
        """BJT: Collector, Base, Emitter."""
        ports, p = self._make_ports(["C", "B", "E"])
        result = NyanCADMixin._default_port_list("npn", ports, p)
        assert result == ["net_C", "net_B", "net_E"]

    def test_unknown_type_returns_empty(self):
        """Unknown device types return empty list (handled as subcircuit elsewhere)."""
        ports, p = self._make_ports(["X"])
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
    the original prop type.
    """

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
        since SPICE would try to evaluate them as parameter expressions.
        """
        assert _eval_params({"model": "name"}, {"name": "nmos_3p3"}) == {
            "model": "nmos_3p3"
        }

    def test_rename_preserves_numeric_type(self):
        """Numeric prop value passes through with its original type."""
        assert _eval_params({"w": "width"}, {"width": 10}) == {"w": 10}
        assert _eval_params({"w": "width"}, {"width": 3.5}) == {"w": 3.5}

    def test_rename_missing_prop_skipped(self):
        """Missing rename target → skip so SPICE model's own default is used."""
        result = _eval_params(
            {"w": "width", "vth": "threshold"},
            {"width": "10"},  # no 'threshold'
        )
        assert result == {"w": "10"}
        assert "vth" not in result

    def test_unicode_identifier_not_treated_as_spice_param(self):
        """SPICE params use the same ASCII identifier rule as generated names."""
        result = _eval_params(
            {"x": "λ + width", "raw": "λ"},
            {"λ": "wrong", "width": "10u"},
        )
        assert result == {"x": "{λ + 10u}", "raw": "{λ}"}

    # --- Arithmetic path: substitute + wrap ---

    def test_arithmetic_substitutes_and_wraps(self):
        """Arithmetic is substituted and wrapped for SPICE to evaluate."""
        assert _eval_params({"w": "width * 1e-6"}, {"width": "10"}) == {
            "w": "{10 * 1e-6}"
        }

    def test_arithmetic_with_spice_suffix_notation(self):
        """SPICE suffix notation reaches SPICE intact — the main reason to let
        SPICE evaluate instead of Python. 'width * 1e-6' with width='10u'
        becomes '{10u * 1e-6}' which ngspice evaluates to 1e-11.
        """
        result = _eval_params({"w": "width * 1e-6"}, {"width": "10u"})
        assert result["w"] == "{10u * 1e-6}"

    def test_multiple_params(self):
        result = _eval_params(
            {"w": "width * 1e-6", "l": "length * 1e-6"},
            {"width": "5u", "length": "0.5u"},
        )
        assert result == {"w": "{5u * 1e-6}", "l": "{0.5u * 1e-6}"}

    def test_unknown_identifier_preserved(self):
        """Identifiers not in device_props stay as-is so SPICE can resolve them
        (e.g. SPICE math functions, model-level parameters).
        """
        assert _eval_params({"x": "sqrt(width)"}, {"width": "10u"}) == {
            "x": "{sqrt(10u)}"
        }

    def test_identifier_regex_skips_numeric_literals(self):
        """Identifier match uses word boundary, so 'e' in '2e-3' is not
        mistaken for a variable — even if 'e' exists in device_props.
        """
        assert _eval_params({"x": "2e-3"}, {"e": "wrong"}) == {"x": "{2e-3}"}

    def test_constant_expression_wrapped(self):
        """Expressions with no variables still go through the arithmetic path."""
        assert _eval_params({"x": "1 + 2"}, {}) == {"x": "{1 + 2}"}

    # --- Spectre / VACASK: no braces ---

    def test_spectre_omits_braces(self):
        """Spectre-family simulators use bare expressions, no braces."""
        assert _eval_params({"w": "width * 1e-6"}, {"width": "10u"}, sim="Spectre") == {
            "w": "10u * 1e-6"
        }

    def test_vacask_omits_braces(self):
        assert _eval_params({"w": "width * 1e-6"}, {"width": "10u"}, sim="VACASK") == {
            "w": "10u * 1e-6"
        }

    # --- Mixed ---

    def test_mixed_rename_and_arithmetic(self):
        """A params mapping can mix rename and arithmetic entries."""
        result = _eval_params(
            {"model": "name", "w": "width * 1e-6"}, {"name": "nmos_3p3", "width": "10u"}
        )
        assert result == {"model": "nmos_3p3", "w": "{10u * 1e-6}"}


# ---------------------------------------------------------------------------
# model prop defaults fallback — no SPICE entry but has default props
# ---------------------------------------------------------------------------


class TestModelPropDefaultsFallback:
    """When a model exists but has no SPICE model entries, model default props
    (like the SPICE model name) are applied as a fallback.
    """

    def test_nmos_gets_model_name_from_defaults(self):
        """NMOS with a model that has no SPICE entries but has a 'model'
        default prop should use that prop value as the SPICE model name.
        """
        schem = {
            "top": {
                "top:M1": {
                    "_id": "top:M1",
                    "type": "nmos",
                    "name": "M1",
                    "model": "my.pdk.nmos",
                    "nets": {"D": "vdd", "G": "inp", "S": "gnd", "B": "gnd"},
                }
            },
            "models": {
                "models:my.pdk.nmos": {
                    "name": "nmos",
                    "type": "nmos",
                    "ports": [
                        {"name": "D", "side": "top"},
                        {"name": "G", "side": "left"},
                        {"name": "S", "side": "bottom"},
                        {"name": "B", "side": "right"},
                    ],
                    "props": [
                        {"name": "width", "default": "0.15"},
                        {"name": "length", "default": "0.13"},
                        {"name": "model", "default": "sg13_lv_nmos"},
                    ],
                }
            },
        }
        spice = str(NyanCircuit("top", schem))
        assert "sg13_lv_nmos" in spice

    def test_device_props_override_model_defaults(self):
        """Device instance props take precedence over model defaults."""
        schem = {
            "top": {
                "top:M1": {
                    "_id": "top:M1",
                    "type": "nmos",
                    "name": "M1",
                    "model": "my.pdk.nmos",
                    "nets": {"D": "vdd", "G": "inp", "S": "gnd", "B": "gnd"},
                    "props": {"model": "sg13_hv_nmos"},
                }
            },
            "models": {
                "models:my.pdk.nmos": {
                    "name": "nmos",
                    "type": "nmos",
                    "ports": [
                        {"name": "D", "side": "top"},
                        {"name": "G", "side": "left"},
                        {"name": "S", "side": "bottom"},
                        {"name": "B", "side": "right"},
                    ],
                    "props": [
                        {"name": "model", "default": "sg13_lv_nmos"},
                    ],
                }
            },
        }
        spice = str(NyanCircuit("top", schem))
        assert "sg13_hv_nmos" in spice
        assert "sg13_lv_nmos" not in spice


# ---------------------------------------------------------------------------
# Structured library + sections model entries (Cadnip :lib/:include migration)
# ---------------------------------------------------------------------------


class TestLibrarySectionEntry:
    """A PDK model migrated to the new schema carries a structured ``library``
    (+ optional ``sections``) model entry instead of inline ``.lib … {corner}``
    code. The circuit must emit a ``.lib library section`` / ``.include library``
    line — with the corner chosen by :func:`_select_corner`, not a baked-in
    ``{corner}`` token — and order the X-call pins by ``port-order``.
    """

    def _schem(self, sections=("tt",), drop_sections=False):
        entry = {
            "language": "spice",
            "name": "sky130_fd_pr__nfet_01v8",
            "spice-type": "SUBCKT",
            "library": "https://example.com/pdk.zip#sky130.lib.spice",
            "port-order": ["D", "G", "S", "B"],
        }
        if not drop_sections:
            entry["sections"] = list(sections)
        return {
            "top": {
                "top:X1": {
                    "_id": "top:X1",
                    "type": "nmos",
                    "name": "X1",
                    "model": "sky.nfet",
                    "nets": {"D": "d", "G": "g", "S": "s", "B": "b"},
                }
            },
            "models": {
                "models:sky.nfet": {
                    "name": "sky130_fd_pr__nfet_01v8",
                    "type": "nmos",
                    "ports": [
                        {"name": "D", "side": "top"},
                        {"name": "G", "side": "left"},
                        {"name": "S", "side": "bottom"},
                        {"name": "B", "side": "right"},
                    ],
                    "models": [entry],
                }
            },
        }

    def test_emits_lib_with_default_corner(self):
        """No corner preference → first section, emitted as a real `.lib` line
        pointing at the resolved local cache path, with no leftover `{corner}`
        template token.
        """
        spice = str(NyanCircuit("top", self._schem()))
        assert "nyancad_archive_cache" in spice
        assert "sky130.lib.spice tt" in spice
        assert "{corner}" not in spice

    def test_corner_preference_selects_section(self):
        spice = str(
            NyanCircuit("top", self._schem(sections=("ss", "tt", "ff")), corners=["ff"])
        )
        assert "nyancad_archive_cache" in spice
        assert "sky130.lib.spice ff" in spice

    def test_include_when_no_sections(self):
        """A model entry without sections falls back to `.include` (no corner)."""
        spice = str(NyanCircuit("top", self._schem(drop_sections=True)))
        assert ".include" in spice
        assert "nyancad_archive_cache" in spice
        assert ".lib " not in spice
        assert "{corner}" not in spice

    def test_xcall_orders_pins_by_port_order(self):
        """The subcircuit X-call lists nets in the entry's port-order."""
        spice = str(NyanCircuit("top", self._schem()))
        assert "d g s b sky130_fd_pr__nfet_01v8" in spice

    def test_registers_pending_download(self):
        """The structured `library` URL is registered for download: archive URL
        (without fragment) + entrypoint extracted from the URL fragment.
        """
        circuit = NyanCircuit("top", self._schem())
        urls = [url for url, _dest, _entry in circuit._pending_downloads]
        assert "https://example.com/pdk.zip" in urls
        entrypoints = {
            url: entry for url, _dest, entry in circuit._pending_downloads
        }
        assert entrypoints["https://example.com/pdk.zip"] == "sky130.lib.spice"

    def test_bare_local_library_passthrough(self):
        """A non-URL `library` is emitted unchanged and registers no download."""
        schem = self._schem()
        schem["models"]["models:sky.nfet"]["models"][0]["library"] = "models/foo.lib"
        circuit = NyanCircuit("top", schem)
        spice = str(circuit)
        assert "models/foo.lib tt" in spice
        assert "nyancad_archive_cache" not in spice
        assert circuit._pending_downloads == []


# ---------------------------------------------------------------------------
# Case-insensitive port matching — built-in symbols vs. migrated model ports
# ---------------------------------------------------------------------------


class TestCaseInsensitivePorts:
    """Built-in nmos/pmos symbols label pins with uppercase chars (D/G/S/B),
    but PDK models migrated by SpiceArmyKnife.jl declare ports/port-order in
    lowercase (sky130 subckts use ``d g s b``). SPICE is case-insensitive, so
    the X-call must still resolve each model port to the right device net
    regardless of case.
    """

    def _schem(self, *, port_order, ports):
        """nmos device with uppercase nets against a model whose port names
        and port-order are given by the caller (typically lowercase).
        """
        return {
            "top": {
                "top:M1": {
                    "_id": "top:M1",
                    "type": "nmos",
                    "name": "M1",
                    "model": "sky.nfet",
                    "nets": {"D": "nd", "G": "ng", "S": "ns", "B": "nb"},
                }
            },
            "models": {
                "models:sky.nfet": {
                    "name": "sky130_fd_pr__nfet_01v8_lvt",
                    "type": "nmos",
                    "ports": [{"name": n, "side": "left"} for n in ports],
                    "models": [
                        {
                            "language": "spice",
                            "name": "sky130_fd_pr__nfet_01v8_lvt",
                            "spice-type": "SUBCKT",
                            "library": "models/sky130.lib.spice",
                            "sections": ["tt"],
                            "port-order": port_order,
                        }
                    ],
                }
            },
        }

    def test_lowercase_port_order_resolves_uppercase_nets(self):
        """Lowercase ``port-order`` maps onto uppercase device nets in order."""
        schem = self._schem(
            port_order=["d", "g", "s", "b"], ports=["d", "g", "s", "b"]
        )
        spice = str(NyanCircuit("top", schem))
        assert "nd ng ns nb sky130_fd_pr__nfet_01v8_lvt" in spice

    def test_lowercase_default_port_order_resolves_uppercase_nets(self):
        """With no explicit port-order, the alphabetical default of the
        lowercase model ports still resolves against uppercase nets.
        """
        schem = self._schem(port_order=None, ports=["d", "g", "s", "b"])
        # default_port_order sorts by name: b, d, g, s
        spice = str(NyanCircuit("top", schem))
        assert "nb nd ng ns sky130_fd_pr__nfet_01v8_lvt" in spice


# ---------------------------------------------------------------------------
# Empty prop values are dropped — no invalid `param=` tokens
# ---------------------------------------------------------------------------


class TestEmptyPropsDropped:
    """Devices placed before commit 086820d (and any still in CouchDB) carry a
    dense `:props` map where every model param was pre-filled with "". An empty
    override is meaningless to SPICE: emitting ``w=`` is invalid and clobbers the
    subckt's own default. The netlister must drop empty values before expanding
    them as ``**props`` kwargs.
    """

    def _schem(self, props):
        """pmos device against a SUBCKT model (IHP sg13_lv_pmos shape), with the
        caller-supplied device props.
        """
        return {
            "top": {
                "top:M2": {
                    "_id": "top:M2",
                    "type": "pmos",
                    "name": "M2",
                    "model": "pdk.pmos",
                    "nets": {"D": "nd", "G": "ng", "S": "ns", "B": "nb"},
                    "props": props,
                }
            },
            "models": {
                "models:pdk.pmos": {
                    "name": "sg13_lv_pmos",
                    "type": "pmos",
                    "ports": [
                        {"name": n, "side": "left"} for n in ("d", "g", "s", "b")
                    ],
                    # Params with no defaults (the SpiceArmyKnife migration shape)
                    "props": [{"name": k} for k in ("w", "l", "ad", "as")],
                    "models": [
                        {
                            "language": "spice",
                            "name": "sg13_lv_pmos",
                            "spice-type": "SUBCKT",
                            "library": "models/sg13g2.lib.spice",
                            "port-order": ["d", "g", "s", "b"],
                        }
                    ],
                }
            },
        }

    def test_all_empty_props_emit_no_param_tokens(self):
        """A device whose props are all "" produces an X-line with no
        ``param=`` fragments — just the instance, nets and subckt name.
        """
        spice = str(
            NyanCircuit("top", self._schem({"w": "", "l": "", "ad": "", "as": ""}))
        )
        assert "sg13_lv_pmos" in spice
        # No empty `param=` fragments for any of the model params
        assert " w=" not in spice
        assert " l=" not in spice
        assert " ad=" not in spice
        assert " as=" not in spice
        # And no bare empty-value token anywhere (e.g. "w= " or a trailing "=")
        assert "= " not in spice
        assert not any(line.rstrip().endswith("=") for line in spice.splitlines())

    def test_real_value_still_emitted(self):
        """A param with a real value survives the empty-drop and is emitted."""
        spice = str(
            NyanCircuit("top", self._schem({"w": "0.35u", "l": "", "ad": "", "as": ""}))
        )
        assert "w=0.35u" in spice
        assert " l=" not in spice
        assert " ad=" not in spice


# ---------------------------------------------------------------------------
# populate_from_nyancad — reads :nets off each device
# ---------------------------------------------------------------------------


class TestPopulateFromNyancad:
    """populate_from_nyancad reads pre-annotated `:nets` from each device doc
    and emits SPICE with those net names — no flood-fill, no geometry.
    """

    def _schem(self, devices):
        """Minimal full schem dict with a single top-level schematic."""
        return {"top": devices, "models": {}}

    def test_emits_nets_directly(self):
        """Resistor with :nets {'P': 'vdd', 'N': 'gnd'} produces
        a SPICE line referencing 'vdd' and 'gnd'.
        """
        schem = self._schem(
            {
                "top:R1": {
                    "_id": "top:R1",
                    "type": "resistor",
                    "x": 1,
                    "y": 1,
                    "transform": [1, 0, 0, 1, 0, 0],
                    "name": "R1",
                    "nets": {"P": "vdd", "N": "gnd"},
                    "props": {"resistance": "1k"},
                }
            }
        )
        spice = str(NyanCircuit("top", schem))
        assert "vdd" in spice
        assert "gnd" in spice
        assert "R1" in spice.replace(":", "_")

    def test_skips_devices_without_nets(self):
        """A device without :nets is treated as disconnected — no SPICE element
        is emitted for it. Legacy data degrades gracefully.
        """
        schem = self._schem(
            {
                "top:R_disconnected": {
                    "_id": "top:R_disconnected",
                    "type": "resistor",
                    "x": 1,
                    "y": 1,
                    "transform": [1, 0, 0, 1, 0, 0],
                    "name": "Rd",
                    "props": {"resistance": "1k"},
                    # no "nets" key
                }
            }
        )
        spice = str(NyanCircuit("top", schem))
        assert "Rd" not in spice

    def test_skips_structural_types(self):
        """Structural docs never produce SPICE elements even if
        they accidentally carry a :nets field.
        """
        schem = self._schem(
            {
                "top:W1": {
                    "_id": "top:W1",
                    "type": "wire",
                    "x": 0,
                    "y": 0,
                    "rx": 1,
                    "ry": 0,
                    "name": "W1",
                },
                "top:T1": {
                    "_id": "top:T1",
                    "type": "text",
                    "x": 0,
                    "y": 0,
                    "transform": [1, 0, 0, 1, 0, 0],
                    "name": "T1",
                },
                "top:P1": {
                    "_id": "top:P1",
                    "type": "port",
                    "x": 0,
                    "y": 0,
                    "transform": [1, 0, 0, 1, 0, 0],
                    "name": "inp",
                },
                "top:V1": {
                    "_id": "top:V1",
                    "type": "via",
                    "name": "V1",
                    "nets": {"P": "n"},
                },
                "top:TP1": {
                    "_id": "top:TP1",
                    "type": "taper",
                    "name": "TP1",
                    "nets": {"P": "n"},
                },
                "top:N1": {
                    "_id": "top:N1",
                    "type": "net",
                    "name": "N1",
                    "nets": {"P": "n"},
                },
                "top:PL1": {
                    "_id": "top:PL1",
                    "type": "polyline",
                    "name": "PL1",
                    "nets": {"P": "n"},
                },
            }
        )
        # Should complete without error and emit no element lines.
        spice = str(NyanCircuit("top", schem))
        assert "W1" not in spice
        assert "T1" not in spice
        assert "V1" not in spice
        assert "TP1" not in spice
        assert "N1" not in spice
        assert "PL1" not in spice

    def test_skips_structural_types_before_element_emit(self):
        """Shared component classification filters newer photonic doc types."""
        seen = []

        class Recorder(NyanCADMixin):
            gnd = "0"

            def _add_nyancad_element(self, dev_id, dev, ports, models, corners, sim):
                seen.append(dev["type"])

        docs = {
            f"top:{kind}": {
                "_id": f"top:{kind}",
                "type": kind,
                "name": kind,
                "nets": {"P": "n"},
            }
            for kind in ("wire", "text", "port", "polyline", "via", "taper", "net")
        }

        Recorder().populate_from_nyancad(docs, {})

        assert seen == []

    def test_distinct_nets_for_distinct_devices(self):
        """Two resistors with different :nets produce two independent
        SPICE elements referencing the declared nets.
        """
        schem = self._schem(
            {
                "top:R1": {
                    "_id": "top:R1",
                    "type": "resistor",
                    "x": 1,
                    "y": 1,
                    "transform": [1, 0, 0, 1, 0, 0],
                    "name": "R1",
                    "nets": {"P": "a", "N": "b"},
                    "props": {"resistance": "1k"},
                },
                "top:R2": {
                    "_id": "top:R2",
                    "type": "resistor",
                    "x": 5,
                    "y": 5,
                    "transform": [1, 0, 0, 1, 0, 0],
                    "name": "R2",
                    "nets": {"P": "b", "N": "c"},
                    "props": {"resistance": "2k"},
                },
            }
        )
        spice = str(NyanCircuit("top", schem))
        # Both devices present, all three nets referenced.
        assert "R1" in spice.replace(":", "_")
        assert "R2" in spice.replace(":", "_")
        for net in ("a", "b", "c"):
            assert net in spice


# ---------------------------------------------------------------------------
# kfnetlist_from_nyancad — direct Mosaic nets to kfnetlist wire format
# ---------------------------------------------------------------------------


def _kf_data(netlist):
    """Assert the SAX path returns a kfnetlist object, then dump it."""
    kfnetlist = pytest.importorskip("kfnetlist")
    assert isinstance(netlist, kfnetlist.Netlist)
    return netlist.to_dict()


class TestKfNetlistFromNyancad:
    """The SAX notebook path should use Mosaic `nets` directly and avoid
    requiring layout fields or DSchematic conversion.
    """

    def test_builds_kfnetlist_from_inline_nets(self):
        schem = {
            "top": {
                "top:IN": {
                    "_id": "top:IN",
                    "type": "port",
                    "name": "in",
                    "nets": {"P": "n_in"},
                },
                "top:OUT": {
                    "_id": "top:OUT",
                    "type": "port",
                    "name": "out",
                    "nets": {"P": "n_out"},
                },
                "top_S1": {
                    "_id": "ignored-id",
                    "type": "straight",
                    "name": "S1",
                    "model": "straight",
                    "nets": {"o1": "n_in", "o2": "n_mid"},
                },
                "top_S2": {
                    "_id": "top_S2",
                    "type": "straight",
                    "name": "S2",
                    "model": "straight",
                    "nets": {"o1": "n_mid", "o2": "n_out"},
                },
            },
            "models": {
                "models:straight": {"name": "straight", "ports": []},
            },
        }

        netlist = kfnetlist_from_nyancad("top", schem)
        data = _kf_data(netlist)

        assert data["instances"] == {
            "top_S1": {
                "kcl": "PDK",
                "component": "straight",
                "settings": {},
                "array": {"na": 1, "nb": 1},
            },
            "top_S2": {
                "kcl": "PDK",
                "component": "straight",
                "settings": {},
                "array": {"na": 1, "nb": 1},
            },
        }
        assert {"name": "in"} in data["nets"][0]
        assert {"instance": "top_S1", "port": "o1"} in data["nets"][0]
        assert [
            {"instance": "top_S1", "port": "o2"},
            {"instance": "top_S2", "port": "o1"},
        ] in data["nets"]
        assert [
            {"name": "out"},
            {"instance": "top_S2", "port": "o2"},
        ] in data["nets"]

    def test_builds_kfnetlist_ports_from_port_named_nets(self):
        schem = {
            "top": {
                "top_S1": {
                    "type": "straight",
                    "model": "straight",
                    "nets": {"o1": "P1", "o2": "P2"},
                },
                "top:P1": {
                    "type": "port",
                    "name": "P1",
                },
                "top:P2": {
                    "type": "port",
                    "name": "P2",
                },
            },
            "models": {
                "models:straight": {"name": "straight", "ports": []},
            },
        }

        netlist = kfnetlist_from_nyancad("top", schem)
        data = _kf_data(netlist)

        assert [{"name": "P1"}, {"instance": "top_S1", "port": "o1"}] in data["nets"]
        assert [{"name": "P2"}, {"instance": "top_S1", "port": "o2"}] in data["nets"]

    def test_layout_only_port_resolves_via_attached_port(self):
        """A Livewire layout-only port (``attached_port`` present, ``nets``
        absent) must join its attached instance port on a synthesized net,
        rather than being mis-bucketed under a net named after itself.
        """
        schem = {
            "top": {
                "top_S1": {
                    "type": "straight",
                    "model": "straight",
                    "nets": {"o2": "n_mid"},
                },
                "in": {
                    "type": "port",
                    "name": "in",
                    "attached_port": {"component_id": "top_S1", "port_name": "o1"},
                },
            },
            "models": {
                "models:straight": {"name": "straight", "ports": []},
            },
        }

        netlist = kfnetlist_from_nyancad("top", schem)
        data = _kf_data(netlist)

        assert [
            {"name": "in"},
            {"instance": "top_S1", "port": "o1"},
        ] in data["nets"]

    def test_uses_model_name_without_needing_layout(self):
        schem = {
            "top": {
                "uuid_a": {
                    "type": "component",
                    "model": "gf.components.straight",
                    "nets": {"o1": "a"},
                    "props": {"length": 10.0},
                }
            },
            "models": {
                "models:gf.components.straight": {
                    "name": "straight",
                    "ports": [{"name": "o1"}],
                }
            },
        }

        netlist = kfnetlist_from_nyancad("top", schem)
        data = _kf_data(netlist)

        assert data["instances"]["uuid_a"] == {
            "kcl": "PDK",
            "component": "straight",
            "settings": {"length": 10.0},
            "array": {"na": 1, "nb": 1},
        }

    def test_missing_model_metadata_is_an_error(self):
        schem = {
            "top": {
                "uuid_a": {
                    "type": "component",
                    "model": "gf.components.straight",
                    "nets": {"o1": "a"},
                },
            },
            "models": {},
        }

        with pytest.raises(ValueError, match="Model metadata missing"):
            kfnetlist_from_nyancad("top", schem)

    def test_missing_model_name_is_an_error(self):
        schem = {
            "top": {
                "uuid_a": {
                    "type": "component",
                    "model": "gf.components.straight",
                    "nets": {"o1": "a"},
                },
            },
            "models": {
                "models:gf.components.straight": {"ports": []},
            },
        }

        with pytest.raises(ValueError, match="has no name"):
            kfnetlist_from_nyancad("top", schem)

    def test_recursive_netlist_uses_subcircuit_component_name(self):
        schem = {
            "top": {
                "top_U1": {
                    "_id": "top_U1",
                    "type": "ckt",
                    "name": "U1",
                    "model": "sub",
                    "nets": {"in": "n_in", "out": "n_out"},
                }
            },
            "sub": {
                "sub_S1": {
                    "_id": "sub_S1",
                    "type": "straight",
                    "name": "S1",
                    "model": "straight",
                    "nets": {"o1": "inner_in", "o2": "inner_out"},
                },
                "sub:IN": {
                    "_id": "sub:IN",
                    "type": "port",
                    "name": "in",
                    "nets": {"P": "inner_in"},
                },
                "sub:OUT": {
                    "_id": "sub:OUT",
                    "type": "port",
                    "name": "out",
                    "nets": {"P": "inner_out"},
                },
            },
            "models": {
                "models:sub": {"name": "sub_model", "ports": []},
                "models:straight": {"name": "straight", "ports": []},
            },
        }

        recnet = recursive_kfnetlist_from_nyancad("top", schem)

        assert set(recnet) == {"top", "sub_model"}
        assert (
            _kf_data(recnet["top"])["instances"]["top_U1"]["component"] == "sub_model"
        )
        assert (
            _kf_data(recnet["sub_model"])["instances"]["sub_S1"]["component"]
            == "straight"
        )

    def test_recursive_netlist_returns_kfnetlist_objects(self):
        schem = {
            "top": {
                "top:IN": {
                    "_id": "top:IN",
                    "type": "port",
                    "name": "in",
                    "nets": {"P": "n"},
                },
                "top_S1": {
                    "_id": "top_S1",
                    "type": "straight",
                    "name": "S1",
                    "model": "straight",
                    "nets": {"o1": "n"},
                },
            },
            "models": {
                "models:straight": {"name": "straight", "ports": []},
            },
        }

        recnet = recursive_kfnetlist_from_nyancad("top", schem)

        assert list(recnet) == ["top"]
        assert _kf_data(recnet["top"])["instances"]["top_S1"]["component"] == "straight"
