/* -*- Mode: c++; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is Mozilla Android code.
 *
 * The Initial Developer of the Original Code is Mozilla Foundation.
 * Portions created by the Initial Developer are Copyright (C) 2011
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 *   Patrick Walton <pcwalton@mozilla.com>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.mozilla.gecko.gfx;

import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.opengl.GLES11;
import android.opengl.GLES11Ext;
import android.util.Log;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

public class StripLayer {
    // The largest texture we'll allocate, measured in bytes.
    private static final int MAX_TEXTURE_SIZE = 1024 * 512 * 2;

    private final ArrayList<Runnable> mActionQueue;
    private final ExecutorService mAsyncGLExecutor;
    private ArrayList<Future<?>> mFutures;
    private final CairoImage mImageBuffer;
    private boolean mInTransaction;
    private Point mOrigin;
    private float mResolution;
    private Strip[] mStrips;
    private final ReentrantLock mTransactionLock;

    public StripLayer(CairoImage imageBuffer, ExecutorService asyncGLExecutor) {
        mActionQueue = new ArrayList<Runnable>();
        mAsyncGLExecutor = asyncGLExecutor;
        mFutures = new ArrayList<Future<?>>();
        mImageBuffer = imageBuffer;
        mOrigin = new Point(0, 0);
        mResolution = 1.0f;
        mTransactionLock = new ReentrantLock();

        recreateStrips();
    }

    private void recreateStrips() {
        // Determine how many strips we need to create.
        IntSize imageSize = mImageBuffer.getSize();
        int imageHeight = imageSize.height, imageArea = imageSize.getArea();
        int imageFormat = mImageBuffer.getFormat();
        int imageBytes = imageArea * CairoUtils.bitsPerPixelForCairoFormat(imageFormat) / 8;
        int stripCount = imageBytes / MAX_TEXTURE_SIZE;
        if (imageBytes % MAX_TEXTURE_SIZE > 0)
            stripCount++;   // Pad out with an extra strip if necessary.

        // Delete the old strips, if necessary.
        if (mStrips != null) {
            for (Strip strip : mStrips)
                strip.dispose();
        }

        // Create the new strips.
        int stripHeight = imageHeight / stripCount;
        mStrips = new Strip[stripCount];
        for (int i = 0; i < stripCount; i++) {
            int stripOffset = stripHeight * i;
            int stripBottom = Math.min(stripOffset + stripHeight, imageHeight);
            mStrips[i] = new Strip(stripOffset, stripBottom - stripOffset);
        }
    }

    public void draw(RenderContext context) {
        if (mStrips == null)
            return;
        for (Strip strip : mStrips)
            strip.draw(context);
    }

    public void beginTransaction() {
        mTransactionLock.lock();
        mInTransaction = true;
    }

    public void endTransaction() {
        scheduleActions(mActionQueue);
        mActionQueue.clear();

        mInTransaction = false;
        mTransactionLock.unlock();
    }

    public void invalidate(Rect rect) {
        int stripHeight = mStrips[0].getHeight();
        int startStrip = rect.top / stripHeight, endStrip = (rect.bottom - 1) / stripHeight;
        for (int i = 0; i <= endStrip; i++)
            mStrips[i].invalidate();
    }

    public void invalidate() {
        for (Strip strip : mStrips)
            strip.invalidate();
    }

    protected void scheduleActions(Collection<Runnable> actionQueue) {
        // Schedule the texture uploads.
        for (Strip strip : mStrips)
            strip.scheduleUploadIfNecessary();

        // Schedule the atomic buffer swaps.
        final ArrayList<Runnable> actions = new ArrayList(actionQueue);
        mFutures.add(mAsyncGLExecutor.submit(new Runnable() {
            @Override
            public void run() {
                // Swap buffers for all the dirty strips.
                for (Strip strip : mStrips)
                    strip.swapBuffersIfNecessary();

                // Perform the queued actions (including updating the origin and resolution).
                for (Runnable action : actions)
                    mAsyncGLExecutor.execute(action);
            }
        }));
    }

    /** Returns the layer origin. */
    public Point getOrigin() {
        return mOrigin;
    }

    /** Sets the layer origin. Only valid inside a transaction. */
    public void setOrigin(final Point newOrigin) {
        mActionQueue.add(new Runnable() {
            @Override
            public void run() {
                mOrigin = newOrigin;
            }
        });
    }

    /** Returns the layer resolution. */
    public float getResolution() {
        return mResolution;
    }

    /** Sets the layer resolution. Only valid inside a transaction. */
    public void setResolution(final float newResolution) {
        mActionQueue.add(new Runnable() {
            @Override
            public void run() {
                mResolution = newResolution;
            }
        });
    }

    /** Given the intrinsic size of the layer, returns the pixel boundaries of the layer rect. */
    protected RectF getBounds(RenderContext context, RectF rect) {
        float scaleFactor = context.zoomFactor / mResolution;
        float x = (rect.left + mOrigin.x) * scaleFactor, y = (rect.top + mOrigin.y) * scaleFactor;
        float width = rect.width() * scaleFactor, height = rect.height() * scaleFactor;
        return new RectF(x, y, x + width, y + height);
    }

    private class Strip {
        private int mFrontTextureID, mBackTextureID;
        private final int mOffset, mHeight;

        // A future that represents the current upload state of this texture:
        //
        // * If this is null, the texture is dirty and the task to upload it has not been enqueued.
        //   (It will be enqueued at the next call to endTransaction().)
        // * If this is an unresolved future, the texture is dirty and the task to upload it has
        //   been enqueued.
        // * If this is a resolved future, the texture is valid.
        private Future<?> mUploadFuture;
        private boolean mSwapNeeded;

        public Strip(int offset, int height) {
            mOffset = offset;
            mHeight = height;

            int[] textures = new int[2];
            GLES11.glGenTextures(2, textures, 0);
            mFrontTextureID = textures[0];
            mBackTextureID = textures[1];

            setTextureParameters(mFrontTextureID);
            setTextureParameters(mBackTextureID);
        }

        private void setTextureParameters(int textureID) {
            GLES11.glBindTexture(GLES11.GL_TEXTURE_2D, textureID);
            GLES11.glTexParameterf(GLES11.GL_TEXTURE_2D, GLES11.GL_TEXTURE_MIN_FILTER,
                                   GLES11.GL_NEAREST);
            GLES11.glTexParameterf(GLES11.GL_TEXTURE_2D, GLES11.GL_TEXTURE_MAG_FILTER,
                                   GLES11.GL_LINEAR);
            GLES11.glTexParameterf(GLES11.GL_TEXTURE_2D, GLES11.GL_TEXTURE_WRAP_S,
                                   GLES11.GL_CLAMP_TO_EDGE);
            GLES11.glTexParameterf(GLES11.GL_TEXTURE_2D, GLES11.GL_TEXTURE_WRAP_T,
                                   GLES11.GL_CLAMP_TO_EDGE);
        }

        public void dispose() {
            if (mFrontTextureID == 0 && mBackTextureID == 0)
                return;

            int[] textures = { mFrontTextureID, mBackTextureID };
            GLES11.glDeleteTextures(textures.length, textures, 0);
            mFrontTextureID = mBackTextureID = 0;
        }

        @Override
        protected void finalize() throws Throwable {
            dispose();
        }

        public int getHeight() {
            return mHeight;
        }

        public synchronized void scheduleUploadIfNecessary() {
            if (mUploadFuture != null)
                return;

            mUploadFuture = mAsyncGLExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    updateTextureImage();
                }
            });
        }

        public synchronized void invalidate() {
            if (mUploadFuture == null)
                return;
            mUploadFuture.cancel(false);
            mUploadFuture = null;
        }

        public synchronized void draw(RenderContext context) {
            GLES11.glBindTexture(GLES11.GL_TEXTURE_2D, mFrontTextureID);

            IntSize imageSize = mImageBuffer.getSize();
            int imageWidth = imageSize.width, imageHeight = imageSize.height;
            RectF untransformedBounds = new RectF(0, mOffset, imageWidth, mOffset + mHeight);
            RectF bounds = getBounds(context, untransformedBounds);
            int[] cropRect = { 0, mHeight, imageWidth, -mHeight };
            GLES11.glTexParameteriv(GLES11.GL_TEXTURE_2D, GLES11Ext.GL_TEXTURE_CROP_RECT_OES,
                                    cropRect, 0);

            RectF viewport = context.viewport;
            float height = bounds.height();
            float left = bounds.left - viewport.left;
            float top = viewport.height() - (bounds.top + height - viewport.top);
            GLES11Ext.glDrawTexfOES(left, top, 0.0f, bounds.width(), height);
        }

        private void updateTextureImage() {
            GLES11.glBindTexture(GLES11.GL_TEXTURE_2D, mBackTextureID);

            int width = mImageBuffer.getSize().width;
            int imageFormat = mImageBuffer.getFormat();
            CairoGLInfo glInfo = new CairoGLInfo(imageFormat);
            Buffer viewBuffer = mImageBuffer.getBuffer().slice();
            int bytesPerPixel = CairoUtils.bitsPerPixelForCairoFormat(imageFormat) / 8;
            viewBuffer.position(width * mOffset * bytesPerPixel);
            GLES11.glTexImage2D(GLES11.GL_TEXTURE_2D, 0, glInfo.internalFormat, width, mHeight, 0,
                                glInfo.format, glInfo.type, viewBuffer);

            GLES11.glFinish();  // Flush the command queue before moving on.

            mSwapNeeded = true;
        }

        public synchronized void swapBuffersIfNecessary() {
            if (!mSwapNeeded)
                return;

            int tmp = mFrontTextureID;
            mFrontTextureID = mBackTextureID;
            mBackTextureID = tmp;

            mSwapNeeded = false;
        }

        @Override
        public String toString() {
            return "[Strip @ " + mOffset + " for " + mHeight + "]";
        }
    }

    public static class RenderContext {
        public final RectF viewport;
        public final FloatSize pageSize;
        public final float zoomFactor;

        public RenderContext(RectF aViewport, FloatSize aPageSize, float aZoomFactor) {
            viewport = aViewport;
            pageSize = aPageSize;
            zoomFactor = aZoomFactor;
        }
    }
}

