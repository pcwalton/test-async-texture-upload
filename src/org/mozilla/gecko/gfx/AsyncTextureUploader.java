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

import android.opengl.GLES11;
import android.util.Log;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.HashMap;

public class AsyncTextureUploader {
    private final ExecutorService mExecutor;

    private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;

    /*
     * A mapping from each EGL image ID to the corresponding texture ID. This must only be accessed
     * on the worker thread.
     */
    private final HashMap<Integer,Integer> mTextureIDs;

    public AsyncTextureUploader(EGLConfig config, int glVersion) {
        mExecutor = Executors.newSingleThreadExecutor();
        mTextureIDs = new HashMap<Integer,Integer>();

        initEGL(config, glVersion);
    }

    private void initEGL(final EGLConfig config, final int glVersion) {
        mExecutor.submit(new Runnable() {
            @Override
            public void run() {
                EGL10 egl = (EGL10)EGLContext.getEGL();
                EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);

                int[] version = new int[2];
                egl.eglInitialize(display, version);

                int[] contextAttrs = {
                    EGL_CONTEXT_CLIENT_VERSION, glVersion,
                    EGL10.EGL_NONE
                };
                EGLContext context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT,
                                                          contextAttrs);
                Log.e("TATU", "### eglCreateContext error: " + egl.eglGetError());
                EGLSurface surface = egl.eglCreatePbufferSurface(display, config, null);
                Log.e("TATU", "### eglCreatePbufferSurface error: " + egl.eglGetError());
                egl.eglMakeCurrent(display, surface, surface, context);
                Log.e("TATU", "### eglMakeCurrent error: " + egl.eglGetError());
            }
        });
    }

    /**
     * Builds an EGL image on the remote thread and returns a handle to it. The provided Runnable
     * is expected to initialize the texture using, at minimum, glTexImage2D(). (It can assume the
     * texture is already bound prior to its execution.)
     */
    public Future<Integer> createEGLImage(final Runnable initTexture) {
        return mExecutor.submit(new Callable<Integer>() {
            @Override
            public Integer call() {
                int[] textureIDs = new int[1];
                GLES11.glGenTextures(1, textureIDs, 0);
                Log.e("TATU", "### createEGLImage -> glGenTextures: " + GLES11.glGetError());
                int textureID = textureIDs[0];
                GLES11.glBindTexture(GLES11.GL_TEXTURE_2D, textureID);
                Log.e("TATU", "### createEGLImage -> glBindTexture: " + GLES11.glGetError());
                initTexture.run();

                int eglImageID = AndroidGLExtensions.createEGLImageFromTexture(textureID);
                mTextureIDs.put(eglImageID, textureID);
                return eglImageID;
            }
        });
    }

    /** Updates the given EGL image. */
    public Future<?> updateEGLImage(final int eglImageID, final Runnable updateTexture) {
        return mExecutor.submit(new Runnable() {
            @Override
            public void run() {
                GLES11.glBindTexture(GLES11.GL_TEXTURE_2D, mTextureIDs.get(eglImageID));
                updateTexture.run();
            }
        });
    }

    /** Applies the given EGL image to the texture currently bound in the calling thread. */
    public void applyEGLImage(final int eglImageID) {
        AndroidGLExtensions.setImageTargetTexture(eglImageID);
    }
}

