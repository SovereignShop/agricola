"""Build the pinned Discord SDK locally; install dependencies with npm ci first."""
from pathlib import Path
import platform
import subprocess

ROOT = Path(__file__).resolve().parents[1]
# esbuild is a native executable; npm/Node are needed to install, not to rebuild.
system = {"Linux": "linux", "Darwin": "darwin", "Windows": "win32"}.get(platform.system())
arch = {"x86_64": "x64", "AMD64": "x64", "aarch64": "arm64", "arm64": "arm64"}.get(platform.machine())
binary = ROOT / "node_modules/@esbuild" / f"{system}-{arch}" / ("esbuild.exe" if system == "win32" else "bin/esbuild")
if not binary.exists():
    raise SystemExit("Install Node.js 20+ and run npm ci before make build.")
subprocess.run([str(binary), "src/js/discord.js", "--alias:zod=zod/v3", "--bundle",
                "--format=iife", "--target=es2020", "--outfile=resources/public/js/discord.js"], cwd=ROOT, check=True)
