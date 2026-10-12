"""Shared and photonic imports remain independent of SPICE and native topology."""

from __future__ import annotations

import os
import subprocess
import sys
import tomllib
from pathlib import Path

PACKAGE_ROOT = Path(__file__).resolve().parents[1]


# @tags spice model libraries
def test_api_and_photonic_netlisting_without_spice(tmp_path: Path) -> None:
    program = """
import importlib.abc
import sys
# @tags spice model libraries
class NoSpice(importlib.abc.MetaPathFinder):
    # @tags spice model libraries
    def find_spec(self, fullname, path=None, target=None):
        if fullname == 'InSpice' or fullname.startswith('InSpice.'):
            raise ModuleNotFoundError('SPICE deliberately unavailable')
sys.meta_path.insert(0, NoSpice())
from nyancad.api import FileAPI
from nyancad.netlist import recursive_kfnetlist_from_nyancad
schem = {
    'top': {
        'top:in': {'type': 'port', 'name': 'in', 'nets': {'P': 'a'}},
        'top:out': {'type': 'port', 'name': 'out', 'nets': {'P': 'b'}},
        'top:guide': {'type': 'straight', 'model': 'guide', 'name': 'guide',
                      'nets': {'o1': 'a', 'o2': 'b'}},
    },
    'models': {'models:guide': {'name': 'straight'}},
}
netlist = recursive_kfnetlist_from_nyancad('top', schem)['top']
assert len(netlist.instances) == 1
assert {port.name for port in netlist.ports} == {'in', 'out'}
assert not any(name == 'InSpice' or name.startswith('InSpice.') for name in sys.modules)
"""
    result = subprocess.run(
        [sys.executable, "-c", program],
        env={**os.environ, "PYTHONPATH": str(PACKAGE_ROOT)},
        text=True,
        capture_output=True,
        timeout=30,
        check=False,
    )
    assert result.returncode == 0, result.stdout + result.stderr


# @tags spice model libraries
def test_spice_does_not_require_native_topology() -> None:
    program = """
import importlib.abc
import sys
# @tags spice model libraries
class NoNativeTopology(importlib.abc.MetaPathFinder):
    # @tags spice model libraries
    def find_spec(self, fullname, path=None, target=None):
        if fullname == 'kfnetlist' or fullname.startswith('kfnetlist.'):
            raise ModuleNotFoundError('Native topology deliberately unavailable')
sys.meta_path.insert(0, NoNativeTopology())
from nyancad.api import FileAPI
from nyancad.spice import NyanCircuit
circuit = NyanCircuit('top', {'top': {}, 'models': {}})
assert '.title schematic' in str(circuit)
assert 'kfnetlist' not in sys.modules
"""
    result = subprocess.run(
        [sys.executable, "-c", program],
        env={**os.environ, "PYTHONPATH": str(PACKAGE_ROOT)},
        text=True,
        capture_output=True,
        timeout=30,
        check=False,
    )
    assert result.returncode == 0, result.stdout + result.stderr


# @tags spice model libraries
def test_base_dependencies_exclude_spice() -> None:
    project = tomllib.loads((PACKAGE_ROOT / "pyproject.toml").read_text())["project"]
    assert not any(
        "inspice" in requirement.lower() or "vacask-bin" in requirement.lower()
        for requirement in project["dependencies"]
    )
