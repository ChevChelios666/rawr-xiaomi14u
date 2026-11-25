import tempfile
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from apply_to_photon_source import build_overlay

with tempfile.TemporaryDirectory() as d:
    source=Path(d)/'upstream'
    dest=source/'app/src/main/java/com/particlesdevs/photoncamera/processing'
    dest.mkdir(parents=True)
    s='''class SaverImplementation {
    Object getFrame(Image image) {
        ImageFrame frame = new ImageFrame(image.getPlanes()[0].getBuffer());
        frame.width = width;
        return frame;
    }
}
'''
    t='''class ImageSaver {
    boolean saveSingleRaw() {
            return saveSingleRaw(dngFilePath, image.buffer, parameters);
    }
}
'''
    (dest/'SaverImplementation.java').write_text(s)
    (dest/'ImageSaver.java').write_text(t)
    out=Path(d)/'overlay'
    build_overlay(source,out)
    assert (dest/'SaverImplementation.java').read_text()==s, 'original changed'
    assert (dest/'ImageSaver.java').read_text()==t, 'original changed'
    rel=Path('app/src/main/java/com/particlesdevs/photoncamera/processing')
    changed=(out/rel/'SaverImplementation.java').read_text()
    assert changed.index('readerBeforeCopy') < changed.index('new ImageFrame') < changed.index('copyAfter')
    assert 'geometryWarning' in changed
    assert 'beforeEncoder' in (out/rel/'ImageSaver.java').read_text()
    assert len(list((out/rel).glob('*.java')))==4
    try:
        build_overlay(source,out)
    except FileExistsError:pass
    else:raise AssertionError('Overlay overwrite should have been rejected')
    # Changed upstream fails fast and does not emit a broken overlay.
    (dest/'SaverImplementation.java').write_text('class Different { }')
    try:
        build_overlay(source,Path(d)/'drift')
    except ValueError as e:assert 'Source version drift' in str(e)
    else:raise AssertionError('Expected failure on source drift')
    print('OVERLAY_TESTS_PASS original_immutable=YES overwritten=NO version_drift=DETECTED')
