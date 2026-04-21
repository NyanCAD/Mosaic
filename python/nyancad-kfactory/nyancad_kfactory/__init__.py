# SPDX-FileCopyrightText: 2026 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0
"""Convert NyanCAD (Mosaic) schematics into kfactory DSchematics.

The converter entry points (:class:`MosaicToDSchematic`, :func:`convert_schematic`,
:func:`convert_from_api`) require ``kfactory`` to be installed. The pure-logic
helpers in :mod:`nyancad_kfactory.geometry` and :mod:`nyancad_kfactory.routing`
do not, so they remain importable on a minimal install.
"""

try:
    import kfactory as _kf  # noqa: F401
except ImportError:
    __all__: list[str] = []
else:
    from .convert import MosaicToDSchematic, convert_from_api, convert_schematic

    __all__ = ["MosaicToDSchematic", "convert_from_api", "convert_schematic"]
