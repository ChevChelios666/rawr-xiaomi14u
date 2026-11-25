package com.particlesdevs.photoncamera.processing;

import android.graphics.ImageFormat;
import android.media.Image;
import android.util.Log;
import java.nio.ByteBuffer;

/** Read-only hooks for the original signed PhotonVidCam release author to integrate. */
public final class PhotonRaw50AuditHooks {
    private PhotonRaw50AuditHooks() { }
    private static final String TAG="PVC_RAW50_AUDIT";
    private static final int STEP=Raw16SampleAudit.DEFAULT_STEP;

    public static void readerBeforeCopy(Image image) {
        if (image == null || image.getFormat()!=ImageFormat.RAW_SENSOR ||
            image.getWidth()!=8192 || image.getHeight()!=6144) return;
        try {
            Image.Plane[] planes=image.getPlanes();
            if(planes.length==0) { Log.e(TAG,"stage=BEFORE_COPY no_planes");return; }
            Image.Plane p=planes[0];
            emit("BEFORE_COPY", image.getTimestamp(),p.getBuffer(),
                image.getWidth(),image.getHeight(),p.getRowStride(),p.getPixelStride());
        } catch(Exception e) {Log.e(TAG,"stage=BEFORE_COPY error="+e.toString());}
    }

    public static void copyAfter(Image image, ByteBuffer copied) {
        if (image==null || copied==null || image.getFormat()!=ImageFormat.RAW_SENSOR ||
            image.getWidth()!=8192 || image.getHeight()!=6144) return;
        try {
            Image.Plane p=image.getPlanes()[0];
            emit("AFTER_COPY",image.getTimestamp(),copied,
                image.getWidth(),image.getHeight(),p.getRowStride(),p.getPixelStride());
        } catch(Exception e) {Log.e(TAG,"stage=AFTER_COPY error="+e.toString());}
    }

    public static void beforeEncoder(ByteBuffer copied, int width, int height, long timestampNs) {
        if(copied==null || width!=8192 || height!=6144)return;
        try {emit("BEFORE_ENCODER",timestampNs,copied,width,height,width*2,2);}
        catch(Exception e) {Log.e(TAG,"stage=BEFORE_ENCODER error="+e.toString());}
    }

    public static void geometryWarning(Image image, int outputWidth) {
        if(image==null || image.getFormat()!=ImageFormat.RAW_SENSOR ||
            image.getWidth()!=8192 || image.getHeight()!=6144) return;
        if(image.getWidth()!=outputWidth) Log.e(TAG,"DNG_GEOMETRY_MISMATCH " +
            "imageWidth="+image.getWidth()+" rowStrideDerivedWidth="+outputWidth);
    }

    private static void emit(String stage,long timestamp,ByteBuffer b,
                             int width,int height,int rowStride,int pixelStride) {
        Raw16SampleAudit.Result r=Raw16SampleAudit.inspect(b,width,height,rowStride,pixelStride,STEP);
        String msg=r.summary(stage,timestamp);
        // Logcat has line-size limits: split into multiple tagged lines if needed.
        if(msg.length()<3000) Log.i(TAG,msg);
        else {
            for(int pos=0;pos<msg.length();pos+=2500)
                Log.i(TAG,msg.substring(pos,Math.min(msg.length(),pos+2500)));
        }
        if(r.minimumRequiredBytes>r.limit || r.inaccessible>0)
            Log.e(TAG,"RAW_PLANE_TRUNCATED stage="+stage+" required="+
                r.minimumRequiredBytes+" limit="+r.limit+" inaccessibleSamples="+r.inaccessible);
    }
}
