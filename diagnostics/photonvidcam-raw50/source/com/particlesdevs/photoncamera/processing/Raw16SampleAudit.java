package com.particlesdevs.photoncamera.processing;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Pure-Java, read-only RAW16 memory sample audit.
 * This is a sampling diagnostic, NOT a claim of complete Bayer validation.
 * Samples actual plane bytes using rowStride/pixelStride, without mutating ByteBuffer position.
 */
public final class Raw16SampleAudit {
    private Raw16SampleAudit() { }
    public static final int DEFAULT_STEP = 8;

    public static final class Result {
        public final int width, height, rowStride, pixelStride, step;
        public final int position, limit, capacity;
        public final long minimumRequiredBytes, inspected, nonzero, zero, inaccessible;
        public final long[] bandScanned, bandNonzero, quadScanned, quadNonzero;
        public final long sampleCrc32;
        private Result(int w, int h, int rs, int ps, int step, int pos, int lim, int cap,
                       long min, long count, long nz, long zeros, long bad,
                       long[] bands, long[] bNz, long[] quads, long[] qNz, long crc) {
            this.width=w; this.height=h; this.rowStride=rs; this.pixelStride=ps;
            this.step=step; this.position=pos; this.limit=lim; this.capacity=cap;
            this.minimumRequiredBytes=min; this.inspected=count; this.nonzero=nz;
            this.zero=zeros; this.inaccessible=bad;
            this.bandScanned=bands; this.bandNonzero=bNz;
            this.quadScanned=quads; this.quadNonzero=qNz; this.sampleCrc32=crc;
        }
        private static String arr(long[] a) {
            StringBuilder b = new StringBuilder("[");
            for (int i=0;i<a.length;i++) { if(i>0)b.append(','); b.append(a[i]); }
            return b.append(']').toString();
        }
        public String summary(String stage, long sensorTimestampNs) {
            // No user data, locations or image bytes included in logcat.
            return String.format(Locale.ROOT,
                "stage=%s timestampNs=%d width=%d height=%d rowStride=%d pixelStride=%d " +
                "position=%d limit=%d capacity=%d minimumRequired=%d step=%d scanned=%d " +
                "nonzero=%d zero=%d inaccessible=%d sampleCRC32=%08X bandScanned=%s " +
                "bandNonzero=%s quadrantScanned=%s quadrantNonzero=%s",
                stage, sensorTimestampNs, width,height,rowStride,pixelStride,position,limit,capacity,
                minimumRequiredBytes,step,inspected,nonzero,zero,inaccessible,sampleCrc32,
                arr(bandScanned),arr(bandNonzero),arr(quadScanned),arr(quadNonzero));
        }
    }

    public static Result inspect(ByteBuffer original, int width, int height,
                                 int rowStride, int pixelStride, int requestedStep) {
        if (original == null) throw new IllegalArgumentException("Null ByteBuffer");
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride < 2)
            throw new IllegalArgumentException("Invalid RAW16 geometry or pixelStride");
        if (requestedStep < 1) throw new IllegalArgumentException("step must be >=1");
        ByteBuffer b = original.duplicate(); // preserved position/limit of original
        int pos=b.position(),lim=b.limit(),cap=b.capacity();
        long needed=((long)height-1)*rowStride+((long)width-1)*pixelStride+2;
        long[] bandCounts=new long[4], bandNz=new long[4];
        long[] quadCounts=new long[4], quadNz=new long[4];
        long inspected=0,nonzero=0,zero=0,inaccessible=0;
        CRC32 crc=new CRC32();
        for(int y=0;y<height;y+=requestedStep) {
            int band=(int)(((long)y*4)/height);
            for(int x=0;x<width;x+=requestedStep) {
                long off=(long)y*rowStride+(long)x*pixelStride;
                int quadrant=(y < height/2 ? 0 : 2)+(x < width/2 ? 0 : 1);
                if(off < 0 || off+1 >= lim) {inaccessible++;continue;}
                int a=b.get((int)off)&255;
                int c=b.get((int)(off+1))&255;
                // Sampled-value zero detection is independent of byte endianness.
                boolean present=(a|c)!=0;
                inspected++;bandCounts[band]++;quadCounts[quadrant]++;
                if(present) { nonzero++;bandNz[band]++;quadNz[quadrant]++; }
                else zero++;
                crc.update(a);crc.update(c);
            }
        }
        return new Result(width,height,rowStride,pixelStride,requestedStep,pos,lim,cap,
            needed,inspected,nonzero,zero,inaccessible,bandCounts,bandNz,
            quadCounts,quadNz,crc.getValue());
    }
}
