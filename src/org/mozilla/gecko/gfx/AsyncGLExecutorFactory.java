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
import android.os.Process;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL10;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AsyncGLExecutorFactory {
    private AsyncGLExecutorFactory() {
        // Don't call this. This class contains only static methods.
    }

    public static ExecutorService createAsyncGLExecutor(EGLConfig config) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        EGLContext parentContext = ((EGL10)EGLContext.getEGL()).eglGetCurrentContext();
        performEGLInitialization(executor, parentContext, config);
        return executor;
    }

    private static void performEGLInitialization(ExecutorService executor,
                                                 final EGLContext parentContext,
                                                 final EGLConfig config) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

                EGL10 egl = (EGL10)EGLContext.getEGL();
                EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
                EGLContext context = egl.eglCreateContext(display, config, parentContext, null);
                if (context == EGL10.EGL_NO_CONTEXT)
                    throw new RuntimeException("eglCreateContext() failed");

                // Create an unused pbuffer surface.
                int[] surfaceAttributes = new int[] {   
                    EGL10.EGL_WIDTH, 16,
                    EGL10.EGL_HEIGHT, 16,
                    EGL10.EGL_NONE
                };

                EGLSurface surface = egl.eglCreatePbufferSurface(display, config,
                                                                 surfaceAttributes);
                if (surface == EGL10.EGL_NO_SURFACE)
                    throw new RuntimeException("eglCreatePbufferSurface() failed");

                egl.eglMakeCurrent(display, surface, surface, context);
            }
        });
    }
}

