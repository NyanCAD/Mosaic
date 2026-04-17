"""Tests for pure functions in nyancad.netlist.

Tests focus on the *intent* of each function — what it should do given its
contract — not on mirroring implementation details. Expected values are
hand-computed from first principles or derived from the ClojureScript
implementation (which is the source of truth for editor display).
"""

import math
import pytest
from nyancad.netlist import (
    model_key,
    bare_id,
    SchemId,
    shape_ports,
    spread_ports,
    port_perimeter,
    port_locations,
    rotate,
    mosfet_shape,
    bjt_shape,
    twoport_shape,
    _select_corner,
    _eval_params,
    NyanCADMixin,
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
# shape_ports — ASCII art → port definitions
# ---------------------------------------------------------------------------

class TestShapePorts:
    """shape_ports parses 2D character grids into port position dicts."""

    def test_mosfet_layout(self):
        """The standard MOSFET shape: D on top, G/B in middle, S on bottom."""
        ports = list(shape_ports([" D ", "GB ", " S "]))
        by_name = {p['name']: (p['x'], p['y']) for p in ports}
        assert by_name == {'D': (1, 0), 'G': (0, 1), 'B': (1, 1), 'S': (1, 2)}

    def test_spaces_are_skipped(self):
        ports = list(shape_ports(["   ", " A ", "   "]))
        assert len(ports) == 1
        assert ports[0]['name'] == 'A'

    def test_all_ports_have_correct_type(self):
        ports = list(shape_ports(["AB"], port_type="photonic"))
        assert all(p['type'] == "photonic" for p in ports)

    def test_default_type_is_electric(self):
        ports = list(shape_ports(["X"]))
        assert ports[0]['type'] == "electric"

    def test_empty_input(self):
        assert list(shape_ports([])) == []

    def test_single_row(self):
        ports = list(shape_ports(["ABC"]))
        assert [(p['name'], p['x'], p['y']) for p in ports] == [
            ('A', 0, 0), ('B', 1, 0), ('C', 2, 0)
        ]


# ---------------------------------------------------------------------------
# spread_ports — distribute ports along a side with center gap
# ---------------------------------------------------------------------------

class TestSpreadPorts:
    """spread_ports distributes n ports in size slots with ports hugging the
    edges and a gap in the center. This matches the ClojureScript spread-ports
    in common.cljc and is critical for cross-language port alignment."""

    def test_n_equals_size(self):
        """All slots filled — no gap needed."""
        assert spread_ports(3, 3) == [1, 2, 3]

    def test_empty(self):
        assert spread_ports(0, 5) == []

    def test_overflow(self):
        """More ports than slots — just count up (overflow)."""
        assert spread_ports(5, 3) == [1, 2, 3, 4, 5]

    def test_single_port_single_slot(self):
        assert spread_ports(1, 1) == [1]

    # --- Hand-computed cases from first principles ---
    # Ports hug edges with gap in center. For odd n, center slot is used.

    def test_1_in_3_center(self):
        """1 port in 3 slots: center position (2)."""
        assert spread_ports(1, 3) == [2]

    def test_1_in_5_center(self):
        """1 port in 5 slots: center position (3)."""
        assert spread_ports(1, 5) == [3]

    def test_1_in_4_center(self):
        """1 port in 4 slots: center = (4+1)//2 = 2. Slight bias toward top."""
        assert spread_ports(1, 4) == [2]

    def test_2_in_4_edges(self):
        """2 ports in 4 slots: one at each edge, gap of 2 in middle."""
        assert spread_ports(2, 4) == [1, 4]

    def test_2_in_5_edges(self):
        """2 ports in 5 slots: one at each edge, gap of 3 in middle."""
        assert spread_ports(2, 5) == [1, 5]

    def test_3_in_5_symmetric(self):
        """3 ports in 5 slots: edges + center."""
        assert spread_ports(3, 5) == [1, 3, 5]

    def test_4_in_6_two_per_end(self):
        """4 ports in 6 slots: 2 at top, 2 at bottom, gap in middle."""
        assert spread_ports(4, 6) == [1, 2, 5, 6]

    def test_3_in_7_even_spread(self):
        """3 ports in 7 slots: 1 top, 1 center (4), 1 bottom."""
        assert spread_ports(3, 7) == [1, 4, 7]

    def test_5_in_7(self):
        """5 ports in 7: 2 top, center, 2 bottom."""
        assert spread_ports(5, 7) == [1, 2, 4, 6, 7]

    # --- Invariants that should hold for ALL valid inputs ---

    def test_output_length_always_equals_n(self):
        for n in range(8):
            for size in range(1, 8):
                result = spread_ports(n, size)
                assert len(result) == n, f"spread_ports({n}, {size}) has {len(result)} elements"

    def test_output_is_sorted(self):
        for n in range(1, 6):
            for size in range(n, n + 4):
                result = spread_ports(n, size)
                assert result == sorted(result), f"spread_ports({n}, {size}) not sorted: {result}"

    def test_positions_within_bounds_when_n_leq_size(self):
        """When n <= size, all positions should be in [1, size]."""
        for n in range(1, 6):
            for size in range(n, n + 4):
                result = spread_ports(n, size)
                assert all(1 <= p <= size for p in result), \
                    f"spread_ports({n}, {size}) out of bounds: {result}"

    def test_symmetric_for_even_n_even_size(self):
        """When both n and size are even, layout should be symmetric around center."""
        for n in [2, 4]:
            for size in [n, n + 2, n + 4]:
                result = spread_ports(n, size)
                center = (size + 1) / 2
                # Each port should have a mirror partner
                for p in result:
                    mirror = size + 1 - p
                    assert mirror in result, \
                        f"spread_ports({n}, {size})={result}: {p} has no mirror {mirror}"


# ---------------------------------------------------------------------------
# port_perimeter — device bounding box from port counts
# ---------------------------------------------------------------------------

class TestPortPerimeter:
    """port_perimeter calculates (width, height) from port side assignments.

    The parity adjustment ensures that opposite sides with different port
    counts can have their ports centered symmetrically. Without it, a side
    with 1 port and a side with 2 ports would have the single port
    misaligned."""

    def test_empty_ports(self):
        """No ports → minimum 1x1 box."""
        assert port_perimeter([]) == (1, 1)

    def test_single_port_left(self):
        assert port_perimeter([{"side": "left"}]) == (1, 1)

    def test_height_matches_max_vertical_count(self):
        """Height = max(left_count, right_count), possibly adjusted for parity."""
        # 3 left, 3 right — same parity, no adjustment
        ports = [{"side": "left"}] * 3 + [{"side": "right"}] * 3
        w, h = port_perimeter(ports)
        assert h == 3

    def test_width_matches_max_horizontal_count(self):
        """Width = max(top_count, bottom_count)."""
        ports = [{"side": "top"}] * 4 + [{"side": "bottom"}] * 4
        w, h = port_perimeter(ports)
        assert w == 4

    def test_parity_adjustment_odd_vs_even(self):
        """1 left (odd) + 2 right (even): raw_height=2 (even) → bumped to 3.
        This ensures the single left port can sit centered between the two right ports."""
        ports = [{"side": "left"}] * 1 + [{"side": "right"}] * 2
        w, h = port_perimeter(ports)
        assert h == 3

    def test_no_adjustment_same_parity(self):
        """2 left + 2 right (both even) → height stays 2."""
        ports = [{"side": "left"}] * 2 + [{"side": "right"}] * 2
        w, h = port_perimeter(ports)
        assert h == 2

    def test_no_adjustment_when_raw_already_odd(self):
        """1 left + 2 right: raw_height = max(1,2) = 2 (even) → adjusted to 3.
        But 2 left + 3 right: raw_height = max(2,3) = 3 (already odd) → stays 3."""
        ports = [{"side": "left"}] * 2 + [{"side": "right"}] * 3
        w, h = port_perimeter(ports)
        assert h == 3  # already odd, different parity but no adjustment needed

    def test_amp_shape_minimum_width(self):
        """Amp (triangle) shape: width >= ceil(height/2).
        6 left ports → height=6, so width must be >= 3."""
        ports = [{"side": "left"}] * 6
        w, h = port_perimeter(ports, shape='amp')
        assert h == 6
        assert w == 3  # ceil(6/2) = 3

    def test_amp_doesnt_shrink_existing_width(self):
        """Amp constraint only increases width, never shrinks it."""
        ports = [{"side": "top"}] * 5 + [{"side": "left"}] * 2
        w, h = port_perimeter(ports, shape='amp')
        assert w >= 5  # original width from 5 top ports preserved

    def test_realistic_opamp(self):
        """Op-amp: 2 left inputs, 1 right output. Parity differs → height bumped."""
        ports = [
            {"side": "left"}, {"side": "left"},  # in+, in-
            {"side": "right"},  # out
        ]
        w, h = port_perimeter(ports)
        # left=2(even), right=1(odd), raw_height=2(even) → 3
        assert (w, h) == (1, 3)


# ---------------------------------------------------------------------------
# port_locations — full coordinate calculation
# ---------------------------------------------------------------------------

class TestPortLocations:
    """port_locations assigns (x, y) coordinates to each port.

    Coordinate system: top-left is (0,0), device body occupies
    [1..width] x [1..height], ports sit on the border at x=0, x=width+1,
    y=0, y=height+1."""

    def test_two_port_passive_exact(self):
        """A simple passive (1 left, 1 right) should produce exact coordinates.
        Height=1, width=1. Left at (0,1), right at (2,1)."""
        ports = [
            {"name": "P", "side": "left", "type": "electric"},
            {"name": "N", "side": "right", "type": "electric"},
        ]
        locs = {p['name']: (p['x'], p['y']) for p in port_locations(ports)}
        assert locs == {"P": (0, 1), "N": (2, 1)}

    def test_opamp_exact(self):
        """Op-amp: 2 left, 1 right. Perimeter (1,3).
        Left spread_ports(2,3)=[1,3] → (0,1) and (0,3).
        Right spread_ports(1,3)=[2] → (2,2)."""
        ports = [
            {"name": "in+", "side": "left", "type": "electric"},
            {"name": "in-", "side": "left", "type": "electric"},
            {"name": "out", "side": "right", "type": "electric"},
        ]
        locs = {p['name']: (p['x'], p['y']) for p in port_locations(ports)}
        assert locs == {"in+": (0, 1), "in-": (0, 3), "out": (2, 2)}

    def test_four_side_exact(self, four_side_ports):
        """1 port per side, perimeter (1,1).
        Top→(1,0), Bottom→(1,2), Left→(0,1), Right→(2,1)."""
        locs = {p['name']: (p['x'], p['y']) for p in port_locations(four_side_ports)}
        assert locs == {"T": (1, 0), "B": (1, 2), "L": (0, 1), "R": (2, 1)}

    def test_amp_top_left_aligned(self):
        """Amp shape: top/bottom ports use sequential x (1, 2, ...) not spread."""
        ports = [
            {"name": "A", "side": "top", "type": "electric"},
            {"name": "B", "side": "top", "type": "electric"},
            {"name": "L", "side": "left", "type": "electric"},
        ]
        locs = port_locations(ports, shape='amp')
        top = sorted([p for p in locs if p['side'] == 'top'], key=lambda p: p['x'])
        assert top[0]['x'] == 1
        assert top[1]['x'] == 2

    def test_preserves_all_port_data(self):
        """Output retains name, side, type from input and adds x, y."""
        ports = [{"name": "in+", "side": "left", "type": "photonic"}]
        locs = port_locations(ports)
        assert len(locs) == 1
        p = locs[0]
        assert p['name'] == "in+"
        assert p['type'] == "photonic"
        assert 'x' in p and 'y' in p


# ---------------------------------------------------------------------------
# rotate — affine transform on port shapes
# ---------------------------------------------------------------------------

class TestRotate:
    """rotate applies a 2D affine transform to port shapes.

    The rotation center is at mid = size/2 - 0.5 where size is the square
    bounding box side length.

    For built-in shapes (mosfet, bjt, twoport), auto-computed size from port
    coords matches the CLJS editor (both give 3, since bg=[1,1] → 2+max(1,1)=3
    and max port coord is 2 → 2+1=3).

    For subcircuits, the auto-computed size can be WRONG when port_locations
    doesn't fill all sides — the missing side's edge coordinate is absent,
    making the auto size too small. Subcircuits must pass size explicitly as
    2 + max(width, height) from port_perimeter."""

    def test_builtin_auto_size_matches_cljs(self):
        """Built-in shapes: auto size = 3, CLJS bg=[1,1] → 2+max(1,1) = 3. Match."""
        for shape in [mosfet_shape, bjt_shape, twoport_shape]:
            auto = max(max(p['x'], p['y']) for p in shape) + 1
            assert auto == 3  # matches CLJS 2 + max(1,1) = 3

    def test_identity_twoport_exact(self):
        """Identity transform: ports stay at original grid positions.
        twoport: P(1,0), N(1,2). size=3, mid=1.0."""
        result = rotate(twoport_shape, [1, 0, 0, 1, 0, 0], 0, 0)
        assert result == {(1, 0): 'P', (1, 2): 'N'}

    def test_identity_with_offset(self):
        """Identity + device offset: all coords shift by (devx, devy)."""
        result = rotate(twoport_shape, [1, 0, 0, 1, 0, 0], 10, 20)
        assert result == {(11, 20): 'P', (11, 22): 'N'}

    def test_90_degree_twoport_exact(self):
        """90° CW rotation of vertical twoport at origin → horizontal.
        size=3, mid=1.0.
        P(1,0): centered(0,-1)→rot90(1,0)→grid(2,1).
        N(1,2): centered(0,1)→rot90(-1,0)→grid(0,1)."""
        result = rotate(twoport_shape, [0, 1, -1, 0, 0, 0], 0, 0)
        assert result == {(2, 1): 'P', (0, 1): 'N'}

    def test_180_degree_swaps_vertical(self):
        """180° rotation: P and N swap vertical positions.
        P(1,0): centered(0,-1)→rot180(0,1)→grid(1,2).
        N(1,2): centered(0,1)→rot180(0,-1)→grid(1,0)."""
        result = rotate(twoport_shape, [-1, 0, 0, -1, 0, 0], 0, 0)
        assert result == {(1, 2): 'P', (1, 0): 'N'}

    def test_mosfet_identity_exact(self):
        """Mosfet identity at origin. size=3, mid=1.0."""
        result = rotate(mosfet_shape, [1, 0, 0, 1, 0, 0], 0, 0)
        assert result == {(1, 0): 'D', (0, 1): 'G', (1, 1): 'B', (1, 2): 'S'}

    def test_mosfet_90_degree_exact(self):
        """90° CW mosfet at (5,5). size=3, mid=1.0.
        D(1,0): centered(0,-1)→rot(1,0)→grid(7,6).
        G(0,1): centered(-1,0)→rot(0,-1)→grid(6,5).
        B(1,1): centered(0,0)→rot(0,0)→grid(6,6).
        S(1,2): centered(0,1)→rot(-1,0)→grid(5,6)."""
        result = rotate(mosfet_shape, [0, 1, -1, 0, 0, 0], 5, 5)
        assert result == {(7, 6): 'D', (6, 5): 'G', (6, 6): 'B', (5, 6): 'S'}

    def test_all_ports_preserved_under_rotation(self):
        """Transform never loses or duplicates ports."""
        for transform in [[1,0,0,1,0,0], [0,1,-1,0,0,0], [-1,0,0,-1,0,0], [0,-1,1,0,0,0]]:
            result = rotate(mosfet_shape, transform, 0, 0)
            assert set(result.values()) == {'D', 'G', 'S', 'B'}
            assert len(result) == 4  # no coordinate collisions

    def test_subcircuit_needs_explicit_size(self):
        """Subcircuit shapes from port_locations can have empty sides, causing
        the auto-computed size to be too small. The correct size is
        2 + max(width, height) from port_perimeter.

        Example: op-amp with 2 left, 1 right, no top/bottom.
        port_perimeter = (1, 3), correct size = 2 + 3 = 5.
        Auto size = max(coord 3) + 1 = 4 (no port at y=4 since no bottom ports)."""
        ports = [
            {"name": "in+", "side": "left", "type": "electric"},
            {"name": "in-", "side": "left", "type": "electric"},
            {"name": "out", "side": "right", "type": "electric"},
        ]
        locs = port_locations(ports)
        w, h = port_perimeter(ports)

        auto_size = max(max(p['x'], p['y']) for p in locs) + 1
        correct_size = 2 + max(w, h)
        assert auto_size == 4
        assert correct_size == 5
        assert auto_size != correct_size

    def test_subcircuit_90deg_with_correct_size(self):
        """90° rotation of op-amp subcircuit with correct explicit size.
        size=5, mid=2.0.
        in+(0,1): centered(-2,-1)→rot(1,-2)→grid(8,5).
        in-(0,3): centered(-2,1)→rot(-1,-2)→grid(6,5).
        out(2,2): centered(0,0)→rot(0,0)→grid(7,7)."""
        ports = [
            {"name": "in+", "side": "left", "type": "electric"},
            {"name": "in-", "side": "left", "type": "electric"},
            {"name": "out", "side": "right", "type": "electric"},
        ]
        locs = port_locations(ports)
        w, h = port_perimeter(ports)
        result = rotate(locs, [0, 1, -1, 0, 0, 0], 5, 5, size=2 + max(w, h))
        assert result == {(8, 5): 'in+', (6, 5): 'in-', (7, 7): 'out'}

    def test_subcircuit_auto_size_gives_wrong_rotation(self):
        """Without explicit size, rotated subcircuit ports are at wrong positions."""
        ports = [
            {"name": "in+", "side": "left", "type": "electric"},
            {"name": "in-", "side": "left", "type": "electric"},
            {"name": "out", "side": "right", "type": "electric"},
        ]
        locs = port_locations(ports)
        w, h = port_perimeter(ports)
        rot90 = [0, 1, -1, 0, 0, 0]

        r_auto = rotate(locs, rot90, 5, 5)
        r_correct = rotate(locs, rot90, 5, 5, size=2 + max(w, h))
        assert r_auto != r_correct  # auto gives wrong positions


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
    """_eval_params evaluates model parameter expressions against device properties."""

    def test_empty_returns_device_props(self):
        props = {"resistance": "1k"}
        assert _eval_params(None, props) == props
        assert _eval_params({}, props) == props

    def test_arithmetic_expression(self):
        result = _eval_params(
            {"w": "width * 1e-6"},
            {"width": "10"}
        )
        assert result["w"] == pytest.approx(10e-6)

    def test_multiple_params(self):
        result = _eval_params(
            {"w": "width * 1e-6", "l": "length * 1e-6"},
            {"width": "5", "length": "0.5"}
        )
        assert result["w"] == pytest.approx(5e-6)
        assert result["l"] == pytest.approx(0.5e-6)

    def test_undefined_variable_skipped(self):
        """If an expression references a variable not in device props, skip that param.
        This lets the SPICE model use its own default."""
        result = _eval_params(
            {"w": "width * 1e-6", "vth": "threshold"},
            {"width": "10"}  # no 'threshold'
        )
        assert "w" in result
        assert "vth" not in result  # skipped, not errored

    def test_invalid_expression_returns_string(self):
        """Syntax errors or other exceptions return the expression string as-is."""
        result = _eval_params(
            {"x": "1 +* 2"},
            {}
        )
        assert result["x"] == "1 +* 2"

    def test_numeric_props_used_directly(self):
        """Props that are already numbers don't need string→float conversion."""
        result = _eval_params(
            {"area": "w * h"},
            {"w": 3.0, "h": 4.0}
        )
        assert result["area"] == pytest.approx(12.0)

    def test_builtins_restricted(self):
        """eval runs with restricted builtins — no imports or dangerous operations."""
        result = _eval_params(
            {"x": "__import__('os')"},
            {}
        )
        # Should either skip (NameError) or return string (other exception)
        assert "x" not in result or result["x"] == "__import__('os')"

    def test_stdlib_functions_unavailable(self):
        """Standard math functions like abs, min, max are not available in expressions.
        This is a known limitation of the restricted eval sandbox."""
        result = _eval_params(
            {"x": "abs(-5)"},
            {}
        )
        # abs is a builtin that's been blocked — should fail
        assert "x" not in result or result["x"] == "abs(-5)"

    def test_spice_notation_not_parsed(self):
        """SPICE notation values like '10k' can't be parsed to float.
        When used in arithmetic expressions, the expression string is returned
        as a fallback (TypeError from str * float caught by generic except)."""
        result = _eval_params(
            {"w": "width * 1e-6"},
            {"width": "10k"}  # SPICE notation
        )
        # "10k" stays as string, "10k" * 1e-6 → TypeError → returns expr
        assert result["w"] == "width * 1e-6"

    def test_string_passthrough_via_identity_expression(self):
        """Non-numeric props can be passed through via simple variable reference.
        This is used for model name passthrough in params mappings."""
        result = _eval_params(
            {"model": "name"},
            {"name": "nmos_3p3"}
        )
        assert result["model"] == "nmos_3p3"

    def test_mixed_numeric_and_string_params(self):
        """Some params evaluate arithmetic, others pass strings through."""
        result = _eval_params(
            {"w": "width * 1e-6", "model": "name"},
            {"width": "10", "name": "nmos_3p3"}
        )
        assert result["w"] == pytest.approx(10e-6)
        assert result["model"] == "nmos_3p3"
