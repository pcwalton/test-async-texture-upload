package org.mozilla.testasynctextureupload;

import org.mozilla.gecko.gfx.AndroidGLExtensions;
import org.mozilla.gecko.gfx.AsyncTextureUploader;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES11;
import android.opengl.GLES11Ext;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

public class TestAsyncTextureUpload extends Activity {
    private static final int TEXTURE_WIDTH = 1024, TEXTURE_HEIGHT = 2048;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new TestView(this));
    }

    static {
        System.loadLibrary("ndk1");
    }

    private class TestView extends GLSurfaceView {
        public TestView(Context context) {
            super(context);
            setEGLContextClientVersion(2);
            setRenderer(new TestRenderer());
        }
    }

    private class TestRenderer implements GLSurfaceView.Renderer {
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
            /* Process any worklist items. */
            ArrayList<Runnable> runnables = new ArrayList();
            mQueue.drainTo(runnables);
            for (Runnable r : runnables)
                r.run();

            /* Now draw the frame. */
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
            loadTextureImages();
            createTexture();

            mUploader = new AsyncTextureUploader(config, 2);

            Runnable applyImage = new Runnable() {
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
    }
}
