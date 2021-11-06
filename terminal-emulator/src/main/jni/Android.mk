LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtentel
LOCAL_SRC_FILES:= tentel.c
include $(BUILD_SHARED_LIBRARY)
