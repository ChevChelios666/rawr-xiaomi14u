package com.x14u.scamlab;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

/**
 * Experimental X14U Camera2 RAW50 instrument. The two variants differ ONLY in the
 * optional, device-listed Qualcomm overrideResourceCostValidation session key.
 * Both: direct Camera ID 4 or 5, vendor opmode 0x900D, YUV2560 + RAW8192.
 * ImageReader byte scan occurs BEFORE any DNG encoding/copying.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "X14U_SCAM_RAW50";
    private static final String VALIDATION_KEY = "org.codeaurora.qcamera3.sessionParameters.overrideResourceCostValidation";
    private static final int OPMODE = 0x900D;
    private static final int RAW_W = 8192, RAW_H = 6144, PREVIEW_W = 2560, PREVIEW_H = 1920;
    private static final long RAW_USAGE = 3L;

    private TextView terminal;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader preview, raw;
    private String cameraId = "4", variant = "A";
    private File sessionDir;
    private FileWriter eventWriter;
    private final AtomicBoolean rawObserved = new AtomicBoolean();
    private volatile boolean active = false;
    private long startedMs;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20,20,20,20);
        scroll.addView(layout);
        setContentView(scroll);
        terminal = new TextView(this);
        terminal.setText("SCAM RAW50 Lab / aurora. A=control, B=one verified session key. No old apps modified.\n");
        layout.addView(terminal);
        for (String id: new String[]{"4", "5"}) {
            for (String mode: new String[]{"A", "B"}) {
                Button button = new Button(this);
                button.setText("Camera ID " + id + " / " + (mode.equals("A") ? "control" : "resource key [1]"));
                button.setOnClickListener(v -> start(id,mode));
                layout.addView(button);
            }
        }
        String id = getIntent().getStringExtra("cameraId");
        String m = getIntent().getStringExtra("variant");
        if (("4".equals(id) || "5".equals(id)) && ("A".equals(m) || "B".equals(m)))
            terminal.postDelayed(() -> start(id,m), 250);
    }

    private void ui(String text) { runOnUiThread(() -> terminal.append(text + "\n")); }
    private synchronized void log(String text) {
        String msg = System.currentTimeMillis() + " " + text;
        Log.i(TAG,msg);
        ui(text);
        if (eventWriter != null) {
            try { eventWriter.write(msg+"\n"); eventWriter.flush(); }
            catch (IOException e) { Log.e(TAG,"log write",e); }
        }
    }

    private void start(String id,String mode) {
        if (active) { ui("Already running; finish current test first."); return; }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 42);
            ui("Grant camera permission and tap the selected test again.");
            return;
        }
        cameraId=id; variant=mode; active=true; rawObserved.set(false); startedMs=SystemClock.elapsedRealtime();
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        sessionDir = new File(base,"runs/ID"+id+"_"+mode+"_"+System.currentTimeMillis());
        if (!sessionDir.mkdirs()) { Log.w(TAG,"mkdir failed (may exist)"); }
        try { eventWriter = new FileWriter(new File(sessionDir,"events.txt")); }
        catch (IOException e) { Log.e(TAG,"unable to create log",e); }
        log("START device=aurora_requested cam="+id+" variant="+mode+" opmode="+OPMODE+
                " outputs=YUV_420_888:"+PREVIEW_W+"x"+PREVIEW_H+
                ",RAW_SENSOR:"+RAW_W+"x"+RAW_H+" readerUsage="+RAW_USAGE);
        cameraThread = new HandlerThread("ScamRaw50Camera"); cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraHandler.post(this::open);
        cameraHandler.postDelayed(() -> {
            if (active && !rawObserved.get()) { log("TIMEOUT_NO_RAW_FRAME waitMs=12000"); stop(); }
        }, 12000L);
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if (requestCode==42 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)
            ui("Camera permission OK. Start the intended test again.");
    }

    private void open() {
        try {
            CameraManager manager = getSystemService(CameraManager.class);
            List<String> ids=Arrays.asList(manager.getCameraIdList());
            log("CAMERA_IDS="+ids+" requested="+cameraId);
            CameraCharacteristics cc=manager.getCameraCharacteristics(cameraId);
            Integer maxRaw=cc.get(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW);
            log("MAX_RAW_OUTPUTS="+maxRaw);
            preview=ImageReader.newInstance(PREVIEW_W,PREVIEW_H,ImageFormat.YUV_420_888,3);
            preview.setOnImageAvailableListener(reader -> {
                Image im=null;
                try { im=reader.acquireLatestImage(); }
                catch(Exception e) { log("PREVIEW_ACQUIRE_ERROR "+e); }
                finally {if(im!=null)im.close();}
            },cameraHandler);
            // Android 14 supports explicit RAW buffer usage. Photon working session used reader usage 0x3.
            raw=new ImageReader.Builder(RAW_W,RAW_H)
                    .setImageFormat(ImageFormat.RAW_SENSOR).setUsage(RAW_USAGE).setMaxImages(2).build();
            raw.setOnImageAvailableListener(reader -> {
                Image image=null;
                try {
                    image=reader.acquireNextImage();
                    if(image != null) inspect(image);
                } catch(Exception e) {log("RAW_INSPECT_ERROR "+e);}
                finally {if(image!=null)image.close();}
            },cameraHandler);
            log("READERS_ALLOCATED only_not_a_Hal_session_confirmation");
            manager.openCamera(cameraId,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice d) {device=d;log("DEVICE_OPENED cam="+d.getId());configure(cc);}
                @Override public void onDisconnected(CameraDevice d) {log("DEVICE_DISCONNECTED");stop();}
                @Override public void onError(CameraDevice d,int error) {log("DEVICE_ERROR code="+error);stop();}
            },cameraHandler);
        } catch(Exception e) {log("OPEN_ERROR "+e);stop();}
    }

    @SuppressWarnings("unchecked")
    private void configure(CameraCharacteristics cc) {
        try {
            OutputConfiguration yuvOut=new OutputConfiguration(preview.getSurface());
            OutputConfiguration rawOut=new OutputConfiguration(raw.getSurface());
            Executor e = command -> cameraHandler.post(command);
            SessionConfiguration sc=new SessionConfiguration(OPMODE,
                Arrays.asList(yuvOut,rawOut),e,new CameraCaptureSession.StateCallback(){
                    @Override public void onConfigured(CameraCaptureSession s) {
                        session=s;log("SESSION_CONFIGURED opmodeRequested="+OPMODE);
                        repeatAndShoot();
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession s) {log("SESSION_CONFIGURE_FAILED");stop();}
                    @Override public void onClosed(CameraCaptureSession s) {log("SESSION_CLOSED");}
                });
            if("B".equals(variant)){
                CaptureRequest.Key<?> listed=null;
                List<CaptureRequest.Key<?>> keys=cc.getAvailableSessionKeys();
                if(keys!=null)for(CaptureRequest.Key<?> key:keys)
                    if(VALIDATION_KEY.equals(key.getName())) {listed=key;break;}
                if(listed==null) {log("KEY_NOT_LISTED variant_B_no_change_no_capture "+VALIDATION_KEY);stop();return;}
                // X14U SCAM TEST reported this key as byte[]. Android public Camera2
                // API does not expose CaptureRequest.Key#getType().
                log("KEY_FOUND name="+listed.getName()+" type=byte[]_from_X14U_SCAM_dump");
                CaptureRequest.Builder params=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                CaptureRequest.Key<byte[]> typedKey = new CaptureRequest.Key<>(VALIDATION_KEY,byte[].class);
                params.set(typedKey,new byte[]{1});
                sc.setSessionParameters(params.build());
                log("SESSION_KEY_REQUESTED "+VALIDATION_KEY+" type=byte[] value=01");
            } else log("CONTROL_NO_VENDOR_SESSION_KEY");
            // HAL can reject an unlisted RAW size. This probe is informational, not an admission oracle.
            try {log("STATIC_IS_SESSION_SUPPORTED="+device.isSessionConfigurationSupported(sc));}
            catch(Exception ex){log("STATIC_CHECK_UNAVAILABLE "+ex);}
            log("CREATE_SESSION_ATTEMPT opmodeRequested="+OPMODE);
            device.createCaptureSession(sc);
        }catch(Exception e){log("CREATE_SESSION_EXCEPTION "+e);stop();}
    }

    private void repeatAndShoot(){
        try{
            CaptureRequest.Builder rep=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            rep.addTarget(preview.getSurface());
            rep.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            session.setRepeatingRequest(rep.build(),new CameraCaptureSession.CaptureCallback(){
                @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult result){
                    Integer af=result.get(CaptureResult.CONTROL_AF_STATE);
                    Long timestamp=result.get(CaptureResult.SENSOR_TIMESTAMP);
                    if(af!=null && (SystemClock.elapsedRealtime()-startedMs)%2000<60)
                        log("PREVIEW_AF_STATE="+af+" ts="+timestamp);
                }
                @Override public void onCaptureFailed(CameraCaptureSession s,CaptureRequest r,CaptureFailure f){
                    log("PREVIEW_CAPTURE_FAILED frame="+f.getFrameNumber()+" reason="+f.getReason());
                }
            },cameraHandler);
            cameraHandler.postDelayed(this::captureOnce,1000);
        }catch(Exception e){log("PREVIEW_REPEAT_ERROR "+e);stop();}
    }

    private void captureOnce(){
        if(!active)return;
        try{
            CaptureRequest.Builder still=device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            still.addTarget(raw.getSurface()); // ONE RAW only; YUV remains in the repeating request
            still.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            log("CAPTURE_SUBMIT_RAW_ONLY 8192x6144");
            session.capture(still.build(),new CameraCaptureSession.CaptureCallback(){
                @Override public void onCaptureStarted(CameraCaptureSession s,CaptureRequest r,long ts,long frame){
                    log("RAW_CAPTURE_STARTED ts="+ts+" frame="+frame);
                }
                @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult res){
                    log("RAW_CAPTURE_RESULT ts="+res.get(CaptureResult.SENSOR_TIMESTAMP)+
                        " AF="+res.get(CaptureResult.CONTROL_AF_STATE)+
                        " ISO="+res.get(CaptureResult.SENSOR_SENSITIVITY)+
                        " expNs="+res.get(CaptureResult.SENSOR_EXPOSURE_TIME));
                }
                @Override public void onCaptureFailed(CameraCaptureSession s,CaptureRequest r,CaptureFailure fail){
                    log("RAW_CAPTURE_FAILED frame="+fail.getFrameNumber()+" reason="+fail.getReason());
                }
                @Override public void onCaptureBufferLost(CameraCaptureSession s,CaptureRequest r,android.view.Surface target,long frame){
                    log("RAW_CAPTURE_BUFFER_LOST frame="+frame+" target="+(target==raw.getSurface()?"RAW":"other"));
                }
            },cameraHandler);
        }catch(Exception e){log("RAW_CAPTURE_EXCEPTION "+e);stop();}
    }

    private void inspect(Image image){
        if(!rawObserved.compareAndSet(false,true))return;
        long t0=SystemClock.elapsedRealtime();
        try{
            Image.Plane[] p=image.getPlanes();
            if(p.length!=1){log("RAW_PLANE_COUNT_UNEXPECTED="+p.length);return;}
            ByteBuffer b=p[0].getBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int rows=p[0].getRowStride(),pix=p[0].getPixelStride();
            int w=image.getWidth(),h=image.getHeight(),limit=b.limit();
            log("RAW_IMAGE_ARRIVED width="+w+" height="+h+" format="+image.getFormat()+
                " rowStride="+rows+" pixelStride="+pix+" limit="+limit+" timestamp="+image.getTimestamp());
            if(w!=RAW_W || h!=RAW_H || pix!=2 || rows<w*2 || (long)rows*(h-1)+(long)pix*(w-1)+2>limit){
                log("RAW_LAYOUT_INVALID do_not_label_full_bayer");return;
            }
            long[] total=new long[4],nonzero=new long[4],high=new long[4];
            int[] min={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE};
            int[] max=new int[4];
            CRC32[] crc={new CRC32(),new CRC32(),new CRC32(),new CRC32()};
            // Spatial 4x4 subsampling, every quadrant independent. Read raw bytes BEFORE any encoder.
            for(int y=0;y<h;y+=4){
                long rowBase=(long)rows*y;
                for(int x=0;x<w;x+=4){
                    int q=(y<h/2?0:2)+(x<w/2?0:1),off=(int)(rowBase+(long)pix*x);
                    int value=b.getShort(off)&0xffff;
                    total[q]++;if(value!=0)nonzero[q]++;if(value>1023)high[q]++;
                    if(value<min[q])min[q]=value;if(value>max[q])max[q]=value;
                    crc[q].update(value&255);crc[q].update(value>>>8);
                }
            }
            boolean full=true;
            for(int q=0;q<4;q++){
                double ratio=total[q]==0?0:(double)nonzero[q]/total[q];
                log("RAW_Q"+(q+1)+" total="+total[q]+" nonzero="+nonzero[q]+" ratio="+ratio+
                    " min="+min[q]+" max="+max[q]+" gt1023="+high[q]+" crc32="+Long.toHexString(crc[q].getValue()));
                if(ratio<0.75) full=false;
            }
            log("RAW_BUFFER_SAMPLE_STATUS="+(full?"NO_LARGE_ZERO_REGION_DETECTED":"FAIL_INCOMPLETE_SAMPLES")+
                " warning=sample_test_not_proof_of_native_Bayer");
            for(int q=0;q<4;q++){
                int y=(q/2)*(h/2),x=(q%2)*(w/2);
                int start=(int)((long)y*rows+(long)x*pix);
                int length=Math.min(65536,limit-start);
                byte[] arr=new byte[length];ByteBuffer copy=b.duplicate();copy.position(start);copy.get(arr);
                try(FileOutputStream out=new FileOutputStream(new File(sessionDir,"raw_q"+(q+1)+"_first64k.bin"))){out.write(arr);}
            }
            log("SAVED_RAW_SAMPLES 4x64KiB for independent offline validation");
        }catch(Exception ex){log("RAW_AUDIT_EXCEPTION "+ex);}
        finally {log("RAW_AUDIT_DURATION_MS="+(SystemClock.elapsedRealtime()-t0));stop();}
    }

    private void stop(){
        if(!active)return;
        active=false;
        log("STOP elapsedMs="+(SystemClock.elapsedRealtime()-startedMs)+" rawCallback="+rawObserved.get());
        // Close on camera worker; listener callbacks cannot close before session drain.
        Runnable close=()->{
            try{if(session!=null){session.close();session=null;}}catch(Exception ignored){}
            try{if(device!=null){device.close();device=null;}}catch(Exception ignored){}
            try{if(raw!=null){raw.close();raw=null;}}catch(Exception ignored){}
            try{if(preview!=null){preview.close();preview=null;}}catch(Exception ignored){}
            synchronized(this){
                if(eventWriter!=null){try{eventWriter.close();}catch(IOException ignored){}eventWriter=null;}
            }
            log("DATA_DIR="+sessionDir.getAbsolutePath());
            if(cameraThread!=null){cameraThread.quitSafely();cameraThread=null;cameraHandler=null;}
        };
        if(cameraHandler!=null){cameraHandler.postDelayed(close,300);} else close.run();
    }

    @Override protected void onDestroy(){super.onDestroy();if(active)stop();}
}
