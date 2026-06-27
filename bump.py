#!/usr/bin/env python3
"""Version bumping script for Mosaic packages.

Usage:
    ./bump.py <nyancad_bump> <server_bump> <tag_bump>

Each argument can be: skip, patch, minor, or major

Package dependencies:
    nyancad-server bundles the frontend JS and depends on nyancad.
    - If nyancad or tag is bumped, server is auto-bumped (at least patch).
    - If nyancad is bumped, the server's nyancad>= pin is updated
      automatically via bump-my-version config.

Examples:
    ./bump.py patch patch patch   # Bump all by patch
    ./bump.py skip minor skip     # Only bump server by minor
    ./bump.py skip skip major     # Only create a major tag
    ./bump.py minor skip minor    # Bump nyancad+tag; server auto-patched
"""

import argparse
import subprocess
import sys
from pathlib import Path

VALID_BUMPS = ("skip", "patch", "minor", "major")
ROOT_DIR = Path(__file__).parent.resolve()


def run_bump(directory: Path, bump_type: str, name: str) -> bool:
    if bump_type == "skip":
        print(f"Skipping {name}")
        return True

    print(f"Bumping {name} by {bump_type}...")
    result = subprocess.run(
        ["bump-my-version", "bump", bump_type],
        cwd=directory,
        capture_output=True,
        text=True,
        check=False,
    )

    if result.returncode != 0:
        print(f"Error bumping {name}:", file=sys.stderr)
        print(result.stderr, file=sys.stderr)
        return False

    print(result.stdout.strip() or f"  {name} bumped successfully")
    return True


def main():
    parser = argparse.ArgumentParser(
        description="Bump versions for Mosaic packages",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "nyancad",
        choices=VALID_BUMPS,
        help="Bump level for nyancad package",
    )
    parser.add_argument(
        "server",
        choices=VALID_BUMPS,
        help="Bump level for nyancad-server package",
    )
    parser.add_argument(
        "tag",
        choices=VALID_BUMPS,
        help="Bump level for repository git tag",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Show what would be done without making changes",
    )

    args = parser.parse_args()

    # nyancad-server bundles the frontend JS and depends on nyancad,
    # so any frontend or nyancad change requires at least a server patch bump.
    if args.server == "skip" and (args.nyancad != "skip" or args.tag != "skip"):
        args.server = "patch"
        print("Auto-bumping server by patch (bundles frontend)")
    # tag is always required to trigger a CI release
    if args.tag == "skip":
        args.tag = "patch"
        print("Auto-bumping tag by patch (required for CI release)")

    if args.dry_run:
        print("Dry run mode - no changes will be made")
        print(f"  nyancad: {args.nyancad}")
        print(f"  server: {args.server}")
        print(f"  tag: {args.tag}")
        return 0

    success = True

    # Bump nyancad package (also updates server's nyancad>= pin via bumpversion config)
    if not run_bump(ROOT_DIR / "python" / "nyancad", args.nyancad, "nyancad"):
        success = False

    # Bump nyancad-server package
    if not run_bump(
        ROOT_DIR / "python" / "nyancad-server", args.server, "nyancad-server"
    ):
        success = False

    # Bump tag (top-level)
    if not run_bump(ROOT_DIR, args.tag, "git tag"):
        success = False

    if success:
        print("\nAll bumps completed successfully!")
    else:
        print("\nSome bumps failed!", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
