LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libtentel-bootstrap
LOCAL_SRC_FILES := tentel-bootstrap-zip.S tentel-bootstrap.c
include $(BUILD_SHARED_LIBRARY)
