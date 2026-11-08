"""Bundle the original tabletop and multiplayer adapter using the pinned esbuild."""
from pathlib import Path
import platform,subprocess
root=Path(__file__).resolve().parents[1]
system={'Linux':'linux','Darwin':'darwin','Windows':'win32'}[platform.system()]
arch={'x86_64':'x64','AMD64':'x64','aarch64':'arm64','arm64':'arm64'}[platform.machine()]
binary=root/'node_modules/@esbuild'/f'{system}-{arch}'/('esbuild.exe' if system=='win32' else 'bin/esbuild')
source=root/'resources/public/ascension'
code='\n;\n'.join((source/name).read_text() for name in ['data.js','engine.js','app.js','game.js','multiplayer.js'])
result=subprocess.run([str(binary),'--minify','--target=es2020'],input=code,text=True,capture_output=True,check=True,cwd=root)
(source/'play.js').write_text(result.stdout)
