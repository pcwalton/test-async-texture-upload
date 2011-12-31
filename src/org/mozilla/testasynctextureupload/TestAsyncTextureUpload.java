package org.mozilla.testasynctextureupload;

import org.mozilla.gecko.gfx.AndroidGLExtensions;
import org.mozilla.gecko.gfx.AsyncGLExecutorFactory;
import org.mozilla.gecko.gfx.CairoImage;
import org.mozilla.gecko.gfx.FloatSize;
import org.mozilla.gecko.gfx.IntSize;
import org.mozilla.gecko.gfx.StripLayer;
import org.mozilla.gecko.gfx.StripLayer.RenderContext;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.opengl.GLES11Ext;
import android.opengl.GLES11;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.RelativeLayout;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGL11;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL10;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

public class TestAsyncTextureUpload extends Activity {
    private static final int TEXTURE_WIDTH = 1024, TEXTURE_HEIGHT = 2048;
    private static final int EGL_BACK_BUFFER = 0x3084;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new TestView(TestAsyncTextureUpload.this));
    }

    static {
        System.loadLibrary("ndk1");
    }

    private class TestView extends GLSurfaceView {
        public TestView(Context context) {
            super(context);
            setRenderer(new TestRenderer4());
        }
    }

    private class TestRenderer4 implements GLSurfaceView.Renderer {
        private ByteBuffer[] mTextureImages;
        private StripLayer mLayer;
        private IntSize mScreenSize;
        private long mFrameStartTime;
        private int mFrameCount;
       
        public TestRenderer4() {
            loadTextureImages();
        }

        private void loadTextureImages() {
            Log.e("TATU", "### Loading texture images");
            mTextureImages = new ByteBuffer[2];
            loadTextureImage(0, R.drawable.tex0);
            loadTextureImage(1, R.drawable.tex1);
        }

        private void loadTextureImage(int imageIndex, int resourceID) {
            Bitmap bitmap = BitmapFactory.decodeResource(getResources(), resourceID);
            ByteBuffer buffer = ByteBuffer.allocateDirect(TEXTURE_WIDTH * TEXTURE_HEIGHT * 4);
            bitmap.copyPixelsToBuffer(buffer.asIntBuffer());
            mTextureImages[imageIndex] = buffer;
        }

        private float mScale = 1.0f;
        private boolean mScaleIncreasing = false;

        @Override
        public void onDrawFrame(GL10 gl) {
            long timestamp = SystemClock.uptimeMillis();
            if (timestamp >= mFrameStartTime + 1000L) {
                Log.e("TATU", "### " + mFrameCount + " FPS");
                mFrameCount = 0;
                mFrameStartTime = timestamp;
            } else {
                mFrameCount++;
            }

            RectF viewport = new RectF(0.0f, 0.0f, mScreenSize.width, mScreenSize.height);
            FloatSize pageSize = new FloatSize(mScreenSize.width, mScreenSize.height);

            if (mScaleIncreasing) {
                mScale += 0.01f;
                if (mScale > 1.0f) {
                    mScale = 1.0f;
                    mScaleIncreasing = false;
                }
            } else {
                mScale -= 0.01f;
                if (mScale < 0.1f) {
                    mScale = 0.1f;
                    mScaleIncreasing = true;
                }
            }

            RenderContext renderContext = new RenderContext(viewport, pageSize, mScale);

            GLES11.glClear(GLES11.GL_COLOR_BUFFER_BIT);

            GLES11.glEnable(GLES11.GL_TEXTURE_2D);

            mLayer.beginTransaction();
            try {
                mLayer.draw(renderContext);
            } finally {
                mLayer.endTransaction();
            }

            long endTime = SystemClock.uptimeMillis();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            mScreenSize = new IntSize(width, height);
            GLES11.glViewport(0, 0, width, height);
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            ExecutorService asyncGLExecutor = AsyncGLExecutorFactory.createAsyncGLExecutor(config);

            final int[] bufferIndex = new int[1];
            final CairoImage imageBuffer = new CairoImage() {
                @Override
                public ByteBuffer getBuffer() {
                    synchronized (TestRenderer4.this) {
                        return mTextureImages[bufferIndex[0]];
                    }
                }

                @Override
                public IntSize getSize() { return new IntSize(TEXTURE_WIDTH, TEXTURE_HEIGHT); }
                @Override
                public int getFormat() { return CairoImage.FORMAT_ARGB32; }
            };

            mLayer = new StripLayer(imageBuffer, asyncGLExecutor);

            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    mLayer.beginTransaction();
                    try {
                        synchronized (imageBuffer) {
                            int prevBuffer = bufferIndex[0];
                            bufferIndex[0] = (prevBuffer == 0) ? 1 : 0;
                        }
                        mLayer.invalidate();
                    } finally {
                        mLayer.endTransaction();
                    }
                }
            }, 0L, 2000L);
        }
    }

    /*private class TestRenderer3 implements GLSurfaceView.Renderer {
        private int mFrontTextureID, mBackTextureID;
        private ByteBuffer[] mTextureImages;
        private long mFrameStartTime = 0;
        private int mFrameCount = 0;

        private void loadTextureImages() {
            Log.e("TATU", "### Loading texture images");
            mTextureImages = new ByteBuffer[2];
            loadTextureImage(0, R.drawable.tex0);
            loadTextureImage(1, R.drawable.tex1);
        }

        private void loadTextureImage(int imageIndex, int resourceID) {
            Bitmap bitmap = BitmapFactory.decodeResource(getResources(), resourceID);
            ByteBuffer buffer = ByteBuffer.allocateDirect(TEXTURE_WIDTH * TEXTURE_HEIGHT * 4);
            bitmap.copyPixelsToBuffer(buffer.asIntBuffer());
            mTextureImages[imageIndex] = buffer;
        }

        private void applyTextureImage(int imageIndex) {
            GLES11.glTexImage2D(GLES11.GL_TEXTURE_2D, 0, GLES11.GL_RGBA, TEXTURE_WIDTH,
                                512, 0, GLES11.GL_RGBA, GLES11.GL_UNSIGNED_BYTE,
                                mTextureImages[imageIndex]);
        }

        private int createTexture() {
            int[] textureIDs = new int[1];
            GLES11.glGenTextures(1, textureIDs, 0);
            int textureID = textureIDs[0];

            GLES11.glBindTexture(GLES11.GL_TEXTURE_2D, textureID);
            GLES11.glTexParameterf(GLES11.GL_TEXTURE_2D, GLES11.GL_TEXTURE_MIN_FILTER,
                                   GLES11.GL_NEAREST);
            GLES11.glTexParameterf(GLES11.GL_TEXTURE_2D, GLES11.GL_TEXTURE_MAG_FILTER,
                                   GLES11.GL_LINEAR);
            GLES11.glTexParameterf(GLES11.GL_TEXTURE_2D, GLES11.GL_TEXTURE_WRAP_S,
                                   GLES11.GL_CLAMP_TO_EDGE);
            GLES11.glTexParameterf(GLES11.GL_TEXTURE_2D, GLES11.GL_TEXTURE_WRAP_T,
                                   GLES11.GL_CLAMP_TO_EDGE);

            return textureID;
        }

        private void drawTexture(float t) {
            GLES11.glEnable(GLES11.GL_TEXTURE_2D);
            int[] cropRect = { 0, 0, TEXTURE_WIDTH, TEXTURE_HEIGHT };
            GLES11.glTexParameteriv(GLES11.GL_TEXTURE_2D, GLES11Ext.GL_TEXTURE_CROP_RECT_OES,
                                    cropRect, 0);
            GLES11Ext.glDrawTexfOES(0.0f, 0.0f, 0.0f,
                                    (float)((Math.sin(t) + 1.0f) * (TEXTURE_WIDTH / 2)),
                                    (float)((Math.sin(t) + 1.0f) * (TEXTURE_HEIGHT / 2)));
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            synchronized (this) {
                long start = SystemClock.uptimeMillis();
                if (start >= mFrameStartTime + 1000L) {
                    Log.e("TATU", "### " + mFrameCount + " FPS");
                    mFrameStartTime = start;
                    mFrameCount = 0;
                } else {
                    mFrameCount++;
                }

                GLES11.glClear(GLES11.GL_COLOR_BUFFER_BIT);
                GLES11.glEnable(GLES11.GL_TEXTURE_2D);
                GLES11.glBindTexture(GLES11.GL_TEXTURE_2D, mFrontTextureID);

                float t = (float)SystemClock.uptimeMillis() / 3000.0f;
                drawTexture(t);

                long end = SystemClock.uptimeMillis();
            }
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES11.glViewport(0, 0, width, height);
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            {
                ByteBuffer a = ByteBuffer.allocateDirect(TEXTURE_WIDTH * TEXTURE_HEIGHT * 2);
                ByteBuffer b = ByteBuffer.allocateDirect(TEXTURE_WIDTH * TEXTURE_HEIGHT * 2);
                for (int i = 0; i < 5; i++) {
                    long start = SystemClock.uptimeMillis();
                    a.put(b);
                    a.position(0);
                    b.position(0);
                    long end = SystemClock.uptimeMillis();
                    Log.e("TATU", "### Copy time: " + (end - start));
                }
            }

            //loadTextureImages();
            mFrontTextureID = createTexture();
            mBackTextureID = createTexture();

            EGL10 egl = (EGL10)EGLContext.getEGL();
            final EGLContext parentContext = egl.eglGetCurrentContext();
            final EGLConfig parentConfig = config;

            Thread thread = new Thread() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

                    EGL10 egl = (EGL10)EGLContext.getEGL();
                    EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
                    EGLContext childContext = egl.eglCreateContext(display, parentConfig,
                                                                   parentContext, null);
                    if (childContext == EGL10.EGL_NO_CONTEXT)
                        throw new RuntimeException("createContext");

                    int[] attribList = new int[] {
                        EGL10.EGL_WIDTH, 256,
                        EGL10.EGL_HEIGHT, 256,
                        EGL10.EGL_NONE
                    };
                    EGLSurface surface = egl.eglCreatePbufferSurface(display, parentConfig,
                                                                     attribList);
                    if (surface == EGL10.EGL_NO_SURFACE)
                        throw new RuntimeException("eglCreatePbufferSurface");

                    egl.eglMakeCurrent(display, surface, surface, childContext);

                    int currentImage = 0;
                    while (true) {
                        GLES11.glBindTexture(GLES11.GL_TEXTURE_2D, mBackTextureID);

                        applyTextureImage(currentImage);

                        GLES11.glFinish();

                        synchronized (TestRenderer3.this) {
                            int tmp = mFrontTextureID;
                            mFrontTextureID = mBackTextureID;
                            mBackTextureID = tmp;
                        }

                        currentImage = (currentImage == 0) ? 1 : 0;
                    }
                }
            };
            thread.start();

            GLES11.glBindTexture(GLES11.GL_TEXTURE_2D, mFrontTextureID);
            applyTextureImage(0);

            //try {
            //    thread.join();
            //} catch (InterruptedException e) {
            //    throw new RuntimeException(e);
            //}
        }
    }*/

    /*private class TestRenderer2 implements GLSurfaceView.Renderer {
        private int mBackTextureID, mFrontTextureID;
        private EGLSurface mPbufferSurface, mWindowSurface;
        private ByteBuffer[] mTextureImages;

        private void loadTextureImages() {
            Log.e("TATU", "### Loading texture images");
            mTextureImages = new ByteBuffer[2];
            loadTextureImage(0, R.drawable.tex0);
            loadTextureImage(1, R.drawable.tex1);
        }

        private void loadTextureImage(int imageIndex, int resourceID) {
            Bitmap bitmap = BitmapFactory.decodeResource(getResources(), resourceID);
            ByteBuffer buffer = ByteBuffer.allocateDirect(TEXTURE_WIDTH * TEXTURE_HEIGHT * 4);
            bitmap.copyPixelsToBuffer(buffer.asIntBuffer());
            mTextureImages[imageIndex] = buffer;
        }

        private int createTexture() {
            int[] textureIDs = new int[1];
            GLES11.glGenTextures(1, textureIDs, 0);
            int textureID = textureIDs[0];

            GLES11.glBindTexture(GLES11.GL_TEXTURE_2D, textureID);
            GLES11.glTexParameterf(GLES11.GL_TEXTURE_2D, GLES11.GL_TEXTURE_MIN_FILTER,
                                   GLES11.GL_NEAREST);
            GLES11.glTexParameterf(GLES11.GL_TEXTURE_2D, GLES11.GL_TEXTURE_MAG_FILTER,
                                   GLES11.GL_LINEAR);
            GLES11.glTexParameterf(GLES11.GL_TEXTURE_2D, GLES11.GL_TEXTURE_WRAP_S,
                                   GLES11.GL_CLAMP_TO_EDGE);
            GLES11.glTexParameterf(GLES11.GL_TEXTURE_2D, GLES11.GL_TEXTURE_WRAP_T,
                                   GLES11.GL_CLAMP_TO_EDGE);

            applyTextureImage(0);
            return textureID;
        }

        private void applyTextureImage(int imageIndex) {
            GLES11.glTexImage2D(GLES11.GL_TEXTURE_2D, 0, GLES11.GL_RGBA, TEXTURE_WIDTH,
                                TEXTURE_HEIGHT, 0, GLES11.GL_RGBA, GLES11.GL_UNSIGNED_BYTE,
                                mTextureImages[imageIndex]);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            long start = SystemClock.uptimeMillis();

            // Copy from the pbuffer to the screen.
            EGL10 egl = (EGL10)EGLContext.getEGL();
            EGLContext context = egl.eglGetCurrentContext();
            EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);

            //egl.eglBindTexImage(display, mPbufferSurface, EGL_BACK_BUFFER);

            GLES11.glBindTexture(GLES11.GL_TEXTURE_2D, mFrontTextureID);
            drawTexture(1);

            GLES11.glBindTexture(GLES11.GL_TEXTURE_2D, mBackTextureID);
            GLES11.glCopyTexImage2D(GLES11.GL_TEXTURE_2D, 0, GLES11.GL_RGB, 0, 0, TEXTURE_WIDTH,
                                    TEXTURE_HEIGHT, 0);
            drawTexture(2);

            long end = SystemClock.uptimeMillis();
            Log.e("TATU", "### Frame time: " + (end - start));
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES11.glViewport(0, 0, width, height);
        }

        private void drawTexture(int loc) {
            GLES11.glEnable(GLES11.GL_TEXTURE_2D);
            int[] cropRect = { 0, 0, TEXTURE_WIDTH, TEXTURE_HEIGHT };
            GLES11.glTexParameteriv(GLES11.GL_TEXTURE_2D, GLES11Ext.GL_TEXTURE_CROP_RECT_OES,
                                    cropRect, 0);
            GLES11Ext.glDrawTexfOES(256.0f, 0.0f, 0.0f, TEXTURE_WIDTH / 2,
                                    TEXTURE_HEIGHT / 2);
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            loadTextureImages();
            mFrontTextureID = createTexture();
            applyTextureImage(0);

            EGL10 egl = (EGL10)EGLContext.getEGL();
            EGLContext context = egl.eglGetCurrentContext();
            EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);

            int[] attribList = new int[] {
                EGL10.EGL_WIDTH, TEXTURE_WIDTH,
                EGL10.EGL_HEIGHT, TEXTURE_HEIGHT,
                EGL10.EGL_NONE
            };

            mPbufferSurface = egl.eglCreatePbufferSurface(display, config, attribList);
            egl.eglMakeCurrent(display, mPbufferSurface, mPbufferSurface, context);

            mBackTextureID = createTexture();
            //GLES11.glBindTexture(GLES11.GL_TEXTURE_2D, mBackTextureID);
            //applyTextureImage(1);
            //drawTexture(1);
        }
    }*/

    /*private class TestRenderer implements GLSurfaceView.Renderer {
        private int mCurrentImageID;
        private int mFrontEGLImage, mBackEGLImage;
        private LinkedBlockingQueue<Runnable> mQueue;
        private int mTextureID;
        private ByteBuffer[] mTextureImages;
        private Timer mTimer;
        private AsyncTextureUploader mUploader;

        public TestRenderer() {
            mQueue = new LinkedBlockingQueue<Runnable>();
        }

        private void loadTextureImages() {
            Log.e("TATU", "### Loading texture images");
            mTextureImages = new ByteBuffer[2];
            loadTextureImage(0, R.drawable.tex0);
            loadTextureImage(1, R.drawable.tex1);
        }

        private void loadTextureImage(int imageIndex, int resourceID) {
            Bitmap bitmap = BitmapFactory.decodeResource(getResources(), resourceID);
            ByteBuffer buffer = ByteBuffer.allocateDirect(TEXTURE_WIDTH * TEXTURE_HEIGHT * 4);
            bitmap.copyPixelsToBuffer(buffer.asIntBuffer());
            mTextureImages[imageIndex] = buffer;
        }

        private void createTexture() {
            int[] textureIDs = new int[1];
            GLES11.glGenTextures(1, textureIDs, 0);
            mTextureID = textureIDs[0];
            Log.e("TATU", "### TestRenderer texture ID = " + mTextureID);

            GLES11.glBindTexture(GLES11.GL_TEXTURE_2D, mTextureID);
            GLES11.glTexParameterf(GLES11.GL_TEXTURE_2D, GLES11.GL_TEXTURE_MIN_FILTER,
                                   GLES11.GL_NEAREST);
            GLES11.glTexParameterf(GLES11.GL_TEXTURE_2D, GLES11.GL_TEXTURE_MAG_FILTER,
                                   GLES11.GL_LINEAR);
            GLES11.glTexParameterf(GLES11.GL_TEXTURE_2D, GLES11.GL_TEXTURE_WRAP_S,
                                   GLES11.GL_CLAMP_TO_EDGE);
            GLES11.glTexParameterf(GLES11.GL_TEXTURE_2D, GLES11.GL_TEXTURE_WRAP_T,
                                   GLES11.GL_CLAMP_TO_EDGE);

            applyTextureImage(0);
        }

        private void applyTextureImage(int imageIndex) {
            GLES11.glTexImage2D(GLES11.GL_TEXTURE_2D, 0, GLES11.GL_RGBA, TEXTURE_WIDTH,
                                TEXTURE_HEIGHT, 0, GLES11.GL_RGBA, GLES11.GL_UNSIGNED_BYTE,
                                mTextureImages[imageIndex]);
            Log.e("TATU", "### glTexImage2D error: " + GLES11.glGetError());
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            // Process any worklist items.
            ArrayList<Runnable> runnables = new ArrayList();
            mQueue.drainTo(runnables);
            for (Runnable r : runnables)
                r.run();

            // Now draw the frame.
            synchronized (this) {
                //Log.e("TATU", "### Drawing frame");
                GLES11.glBindTexture(GLES11.GL_TEXTURE_2D, mTextureID);
                GLES11.glEnable(GLES11.GL_TEXTURE_2D);

                int[] cropRect = { 0, 0, TEXTURE_WIDTH, TEXTURE_HEIGHT };
                GLES11.glTexParameteriv(GLES11.GL_TEXTURE_2D, GLES11Ext.GL_TEXTURE_CROP_RECT_OES,
                                        cropRect, 0);

                GLES11Ext.glDrawTexfOES(0.0f, 0.0f, 0.0f, TEXTURE_WIDTH, TEXTURE_HEIGHT);
            }
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            Log.e("TATU", "### Setting viewport " + width + " " + height);
            GLES11.glViewport(0, 0, width, height);
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            int[] textureIDs = new int[1];
            for (int i = 0; i < 5; i++) {
                GLES11.glGenTextures(1, textureIDs, 0);
                Log.e("TATU", "### GLSURFACEVIEW: glGenTextures texture ID = " + textureIDs[0]);
            }

            int textureID = textureIDs[0];
            int eglImageID = AndroidGLExtensions.createEGLImageFromTexture(textureID);

            long start = SystemClock.uptimeMillis();
            GLES11.glCopyTexImage2D(GLES11.GL_TEXTURE_2D, 0, GLES11.GL_RGB, 0, 0, TEXTURE_WIDTH,
                                    TEXTURE_HEIGHT, 0);
            long end = SystemClock.uptimeMillis();
            Log.e("TATU", "### glCopyTexImage2D time: " + (end - start));

            //loadTextureImages();
            //createTexture();

            //mUploader = new AsyncTextureUploader(config, 1, mSurfaceView);

            /*Runnable applyImage = new Runnable() {
                @Override
                public void run() {
                    applyTextureImage(0);
                }
            };

            try {
                mFrontEGLImage = mUploader.createEGLImage(applyImage).get();
                mBackEGLImage = mUploader.createEGLImage(applyImage).get();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }

            if (mTimer == null) {
                mTimer = new Timer("Texture Switcher");
                mTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        Log.e("TATU", "### Switching texture");
                        final int newImageID = (mCurrentImageID == 0) ? 1 : 0;

                        try {
                            mUploader.updateEGLImage(mBackEGLImage, new Runnable() {
                                @Override
                                public void run() {
                                    applyTextureImage(newImageID);
                                }
                            }).get();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        } catch (ExecutionException e) {
                            throw new RuntimeException(e);
                        }

                        mQueue.add(new Runnable() {
                            @Override
                            public void run() {
                                int tmp = mFrontEGLImage;
                                mFrontEGLImage = mBackEGLImage;
                                mBackEGLImage = tmp;
                                Log.e("TATU", "### Front EGL image is now " + mFrontEGLImage);

                                GLES11.glBindTexture(GLES11.GL_TEXTURE_2D, mTextureID);
                                mUploader.applyEGLImage(mFrontEGLImage);
                                Log.e("TATU", "### glEGLImageTargetTexture2DOES error: " +
                                      GLES11.glGetError());
                            }
                        });
                    }
                }, 0, 1000);
            }
        }
    }*/
}
