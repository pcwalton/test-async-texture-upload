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

#define EGL_EGLEXT_PROTOTYPES

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES/gl.h>
#include <GLES/glext.h>
#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <map>

namespace {

/* A list of EGL images. */
std::map<int,EGLImageKHR> sImageRefs;
int sNextImageRefID = 0;
pthread_mutex_t sImageRefsLock = PTHREAD_MUTEX_INITIALIZER;

const EGLint IMAGE_ATTRIBUTES[] = {
    EGL_GL_TEXTURE_LEVEL_KHR, 0,            /* mip-map level */
    //EGL_IMAGE_PRESERVED_KHR, EGL_FALSE,
    EGL_NONE
};

/*
 * Creates an EGL image bound to the given texture ID and returns a reference to it.
 * The EGL image must be later destroyed using destroyEGLImage(). It may be safely
 * shared among multiple threads.
 *
 * This function is thread-safe.
 */
extern "C" jint
Java_org_mozilla_gecko_gfx_AndroidGLExtensions_createEGLImageFromTexture(JNIEnv *env,
                                                                         jint textureID)
{
    EGLDisplay eglDisplay = eglGetCurrentDisplay();
    __android_log_print(ANDROID_LOG_ERROR, "TATU", "### eglGetCurrentDisplay error: %x",
                        (unsigned)eglGetError());
    EGLContext eglContext = eglGetCurrentContext();
    __android_log_print(ANDROID_LOG_ERROR, "TATU", "### eglGetCurrentContext error: %x",
                        (unsigned)eglGetError());
    __android_log_print(ANDROID_LOG_ERROR, "TATU", "### eglGetCurrentContext is broken? %d",
                        (int)(eglContext == EGL_NO_CONTEXT));

    EGLClientBuffer clientBuffer = reinterpret_cast<EGLClientBuffer>(textureID);
    EGLImageKHR image = eglCreateImageKHR(eglDisplay, eglContext,
                                          EGL_GL_TEXTURE_2D_KHR, clientBuffer,
                                          IMAGE_ATTRIBUTES);
    __android_log_print(ANDROID_LOG_ERROR, "TATU", "### eglCreateImageKHR error: %x",
                        (unsigned)eglGetError());

    pthread_mutex_lock(&sImageRefsLock);
    int id = sNextImageRefID++;
    sImageRefs[id] = image;
    pthread_mutex_unlock(&sImageRefsLock);

    return id;
}

/* TODO: destroyEGLImage() */

/*
 * Binds the given EGL image to the current texture.
 *
 * This function is thread-safe.
 */
extern "C" void
Java_org_mozilla_gecko_gfx_AndroidGLExtensions_setImageTargetTexture(JNIEnv *env,
                                                                     jint eglImageID)
{
    pthread_mutex_lock(&sImageRefsLock);
    EGLImageKHR image = sImageRefs[eglImageID];
    pthread_mutex_unlock(&sImageRefsLock);

    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, image);
}

};

