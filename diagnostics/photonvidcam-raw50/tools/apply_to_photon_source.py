#!/usr/bin/env python3
"""Create modified copies of PhotonVidCam source files; never edit checkout in place.

Usage: python apply_to_photon_source.py --source /path/to/PhotonVidCam --out /path/to/pvc-audit-overlay
The output is an overlay containing three new/modified Java sources. Developer merges
into their own branch and builds/signs the compatible original package.
"""
import argparse, shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
DEST = Path('app/src/main/java/com/particlesdevs/photoncamera/processing')


def replace_exact_once(text, needle, replacement, label):
    matches=text.count(needle)
    if matches!=1:
        raise ValueError(f'{label}: expected exact anchor once, found {matches}. Source version drift; abort rather than corrupt source.')
    return text.replace(needle,replacement,1)


def build_overlay(source: Path, out: Path):
    src_dir=source/DEST
    if not (src_dir/'SaverImplementation.java').is_file():
        raise FileNotFoundError(f'Source not found: {src_dir}')
    # Never overwrite working source checkout; a separate overlay must be chosen.
    if out.resolve()==source.resolve() or source.resolve() in out.resolve().parents:
        raise ValueError('Output must be outside the original source checkout')
    out_dir=out/DEST
    if out.exists() and any(out.rglob('*.java')):
        raise FileExistsError('Output already contains Java files; choose fresh output to preserve prior work')
    orig=(src_dir/'SaverImplementation.java').read_text(encoding='utf-8')
    updated=replace_exact_once(orig,
        '        ImageFrame frame = new ImageFrame(image.getPlanes()[0].getBuffer());',
        '        PhotonRaw50AuditHooks.readerBeforeCopy(image);\n'
        '        ImageFrame frame = new ImageFrame(image.getPlanes()[0].getBuffer());\n'
        '        PhotonRaw50AuditHooks.copyAfter(image, frame.buffer);',
        'SaverImplementation.getFrame allocation')
    updated=replace_exact_once(updated,
        '        frame.width = width;',
        '        frame.width = width;\n'
        '        PhotonRaw50AuditHooks.geometryWarning(image, frame.width);',
        'SaverImplementation.getFrame geometry')
    saver=(src_dir/'ImageSaver.java').read_text(encoding='utf-8')
    revised=replace_exact_once(saver,
        '            return saveSingleRaw(dngFilePath, image.buffer, parameters);',
        '            PhotonRaw50AuditHooks.beforeEncoder(image.buffer, image.width, image.height, image.timestamp);\n'
        '            return saveSingleRaw(dngFilePath, image.buffer, parameters);',
        'ImageSaver.Util.preEncoder')
    out_dir.mkdir(parents=True,exist_ok=True)
    (out_dir/'SaverImplementation.java').write_text(updated,encoding='utf-8')
    (out_dir/'ImageSaver.java').write_text(revised,encoding='utf-8')
    for name in ('Raw16SampleAudit.java','PhotonRaw50AuditHooks.java'):
        shutil.copyfile(HERE/'source/com/particlesdevs/photoncamera/processing'/name,out_dir/name)
    print('OVERLAY_CREATED',out.resolve())
    print('SOURCE_ORIGINALS_UNCHANGED',source.resolve())
    print('OVERLAY_FILES',*[str(x.relative_to(out)) for x in sorted(out.rglob('*.java'))],sep='\n')


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--source',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True)
    args=p.parse_args()
    build_overlay(args.source,args.out)

if __name__=='__main__':main()
