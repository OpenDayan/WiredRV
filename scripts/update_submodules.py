#!/usr/bin/env python3

from pathlib import Path
import subprocess


SUBMODULES_MAP = {
    ".": [
        "difftest",
        "third_party/riscv-tests",
        "third_party/LaomaSubsystem",
    ],
    "third_party/LaomaSubsystem": [
        "rocket-chip",
        "xs-utils",
    ],
    "third_party/LaomaSubsystem/rocket-chip": [
        "dependencies/cde",
        "dependencies/diplomacy",
        "dependencies/hardfloat",
    ],
}

PROJECT_ROOT = Path(__file__).resolve().parent.parent


def run(cmd: list[str], cwd: Path) -> None:
    subprocess.run(cmd, cwd=cwd, check=True)


def update_submodules() -> None:
    for module_name, submodules in SUBMODULES_MAP.items():
        module_path = PROJECT_ROOT / module_name
        run(["git", "submodule", "sync"], module_path)
        for submodule in submodules:
            run(
                [
                    "git",
                    "submodule",
                    "update",
                    "--init",
                    "--progress",
                    submodule,
                ],
                module_path,
            )


if __name__ == "__main__":
    update_submodules()
