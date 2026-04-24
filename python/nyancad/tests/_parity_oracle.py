"""Parity oracle: read JSON docs from stdin, validate against Pydantic,
write verdicts to stdout.

Used by the CLJS parity spike in src/test/nyancad/mosaic/parity_test.cljs.
Each stdin line is a JSON doc + a "kind" tag, formatted as:

    {"kind": "device", "doc": {...}}

One output line per input line:

    {"ok": true}         (validation passed)
    {"ok": false, "err": "..."}   (validation failed, error in err)

Runs until stdin closes. Safe to kill at any point.
"""
from __future__ import annotations

import json
import sys

from pydantic import TypeAdapter, ValidationError

from nyancad.schemas import Device, Component, Wire, ModelMetadata


ADAPTERS = {
    "device": TypeAdapter(Device),
    "component": TypeAdapter(Component),
    "wire": TypeAdapter(Wire),
    "model": TypeAdapter(ModelMetadata),
}


def validate(kind: str, doc: dict) -> dict:
    adapter = ADAPTERS.get(kind)
    if adapter is None:
        return {"ok": False, "err": f"unknown kind: {kind}"}
    try:
        adapter.validate_python(doc)
        return {"ok": True}
    except ValidationError as exc:
        # errors() is a list; stringify the first for a short message
        errs = exc.errors()
        first = errs[0] if errs else {}
        return {"ok": False, "err": f"{first.get('loc')}: {first.get('msg')}"}


def main() -> int:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError as exc:
            print(json.dumps({"ok": False, "err": f"malformed JSON: {exc}"}))
            sys.stdout.flush()
            continue
        verdict = validate(req.get("kind", ""), req.get("doc", {}))
        print(json.dumps(verdict))
        sys.stdout.flush()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
