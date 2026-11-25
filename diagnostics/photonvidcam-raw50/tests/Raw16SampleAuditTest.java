package com.particlesdevs.photoncamera.processing;

import java.nio.ByteBuffer;

public final class Raw16SampleAuditTest {
    private static void require(boolean val,String why) {if(!val)throw new AssertionError(why);}
    public static void main(String[] args) {
        // 16x16 raw with two-byte pixels: everything nonzero.
        ByteBuffer full=ByteBuffer.allocateDirect(16*16*2);
        for(int i=0;i<full.capacity();i+=2){full.put(i,(byte)64);full.put(i+1,(byte)0);}
        full.position(4);
        Raw16SampleAudit.Result all=Raw16SampleAudit.inspect(full,16,16,32,2,1);
        require(all.inspected==256 && all.nonzero==256 && all.zero==0,"full nonzero");
        require(full.position()==4,"source buffer position unchanged");
        // Simulate exactly 25% of raster in front and 75% unwritten.
        ByteBuffer partial=ByteBuffer.allocateDirect(16*16*2);
        for(int i=0;i<16*16*2/4;i+=2){partial.put(i,(byte)64);}
        Raw16SampleAudit.Result p=Raw16SampleAudit.inspect(partial,16,16,32,2,1);
        require(p.nonzero==64 && p.zero==192,"quarter-frame occupancy");
        require(p.bandNonzero[0]==64 && p.bandNonzero[1]==0 && p.bandNonzero[2]==0 && p.bandNonzero[3]==0,"vertical bands");
        // Scanner must follow rowStride, skipping padding correctly.
        ByteBuffer padded=ByteBuffer.allocateDirect(2*24);
        for(int y=0;y<2;y++)for(int x=0;x<8;x++){int at=y*24+x*2;padded.put(at,(byte)99);}
        Raw16SampleAudit.Result pad=Raw16SampleAudit.inspect(padded,8,2,24,2,1);
        require(pad.nonzero==16&&pad.zero==0&&pad.minimumRequiredBytes==40,"row stride padding");
        // If a plane advertises larger coordinates than readable buffer, record inaccessible samples.
        padded.limit(24);
        Raw16SampleAudit.Result shortPlane=Raw16SampleAudit.inspect(padded,8,2,24,2,1);
        require(shortPlane.inaccessible==8,"truncated plane caught");
        // Before/after hashes must agree for a correct direct-buffer copy.
        ByteBuffer duplicate=ByteBuffer.allocateDirect(16*16*2);
        for(int i=0;i<partial.capacity();i++)duplicate.put(i,partial.get(i));
        Raw16SampleAudit.Result copied=Raw16SampleAudit.inspect(duplicate,16,16,32,2,1);
        require(copied.sampleCrc32==p.sampleCrc32,"byte copy CRC");
        System.out.println("RAW16_DIAGNOSTIC_TESTS_PASS samples="+p.inspected+" nonzero="+p.nonzero+
            " zeros="+p.zero+" crc="+Long.toHexString(p.sampleCrc32));
    }
}
