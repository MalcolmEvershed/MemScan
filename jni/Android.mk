LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := memscan
LOCAL_SRC_FILES := memscan.c
LOCAL_LDLIBS    := -llog

include $(BUILD_SHARED_LIBRARY)
