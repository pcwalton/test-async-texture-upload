LOCAL_PATH := $(call my-dir)
  
include $(CLEAR_VARS)

LOCAL_LDLIBS := -llog -lGLESv2 -lEGL
  
LOCAL_MODULE    := ndk1
LOCAL_SRC_FILES := AndroidGLExtensions.cpp
  
include $(BUILD_SHARED_LIBRARY)  

