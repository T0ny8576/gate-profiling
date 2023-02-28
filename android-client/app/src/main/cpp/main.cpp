// %BANNER_BEGIN%
// ---------------------------------------------------------------------
// %COPYRIGHT_BEGIN%
// Copyright (c) 2022 Magic Leap, Inc. All Rights Reserved.
// Use of this file is governed by the Software License Agreement,
// located here: https://www.magicleap.com/software-license-agreement-ml2
// Terms and conditions applicable to third-party materials accompanying
// this distribution may also be found in the top-level NOTICE file
// appearing herein.
// %COPYRIGHT_END%
// ---------------------------------------------------------------------
// %BANNER_END%

//#define ALOG_TAG "com.magicleap.capi.camera-lib"

#include <jni.h>

#include <condition_variable>

#include <ml_camera_v2.h>
#include <ml_media_error.h>

#ifdef ML_LUMIN
#include <EGL/egl.h>
//#define EGL_EGLEXT_PROTOTYPES
#include <EGL/eglext.h>
#endif

//#define UNWRAP_RET_MEDIARESULT(res) UNWRAP_RET_MLRESULT_GENERIC(res, UNWRAP_MLMEDIA_RESULT);

namespace EnumHelpers {
const char *GetMLCameraErrorString(const MLCameraError &err) {
  switch (err) {
    case MLCameraError::MLCameraError_None: return "";
    case MLCameraError::MLCameraError_Invalid: return "Invalid/Unknown error";
    case MLCameraError::MLCameraError_Disabled: return "Camera disabled";
    case MLCameraError::MLCameraError_DeviceFailed: return "Camera device failed";
    case MLCameraError::MLCameraError_ServiceFailed: return "Camera service failed";
    case MLCameraError::MLCameraError_CaptureFailed: return "Capture failed";
    default: return "Invalid MLCameraError value!";
  }
}

const char *GetMLCameraDisconnectReasonString(const MLCameraDisconnectReason &reason) {
  switch (reason) {
    case MLCameraDisconnectReason::MLCameraDisconnect_DeviceLost: return "Device lost";
    case MLCameraDisconnectReason::MLCameraDisconnect_PriorityLost: return "Priority lost";
    default: return "Invalid MLCameraDisconnectReason value!";
  }
}
}

using namespace std::chrono_literals;

class ML2Camera {
public:
    ML2Camera(jint width, jint height)
      : camera_device_available_(false),
        has_capture_started_(false),
        capture_width_(width),   ///> 1920
        capture_height_(height),  ///> 1080
        is_frame_available_(false),
        camera_context_(ML_INVALID_HANDLE)
  {}

  void SetupRestrictedResources() {
    SetupCamera();
    StartCapture();
  }

  bool IsCameraInitialized() const {
    return MLHandleIsValid(camera_context_);
  }

  static void OnVideoAvailable(const MLCameraOutput *output, const MLHandle metadata_handle,
                               const MLCameraResultExtras *extra, void *data) {
    ML2Camera *this_app = reinterpret_cast<ML2Camera *>(data);
    if (!this_app->is_frame_available_) {
      memcpy(this_app->framebuffer_, output->planes[0].data, output->planes[0].size);
      this_app->is_frame_available_ = true;
    } else {
      // When running with ZI, as the video needs be transfered from device to host, lots of frame
      // dropping is expected. So don't flood with this logging.
    }
  }

  MLResult DestroyCamera() {
    if (IsCameraInitialized()) {
      MLCameraDisconnect(camera_context_);
      camera_context_ = ML_INVALID_HANDLE;
      camera_device_available_ = false;
    }
    MLCameraDeInit();
    return MLResult_Ok;
  }

  MLResult StopCapture() {
    if (IsCameraInitialized() && has_capture_started_) {
      MLCameraCaptureVideoStop(camera_context_);
      has_capture_started_ = false;
    }
    return MLResult_Ok;
  }

  MLResult SetupCamera() {
    if (IsCameraInitialized()) {
      return MLResult_Ok;
    }
    MLCameraDeviceAvailabilityStatusCallbacks device_availability_status_callbacks = {};
    MLCameraDeviceAvailabilityStatusCallbacksInit(&device_availability_status_callbacks);
    device_availability_status_callbacks.on_device_available = [](const MLCameraDeviceAvailabilityInfo *avail_info) {
      CheckDeviceAvailability(avail_info, true);
    };
    device_availability_status_callbacks.on_device_unavailable = [](const MLCameraDeviceAvailabilityInfo *avail_info) {
      CheckDeviceAvailability(avail_info, false);
    };

    MLCameraInit(&device_availability_status_callbacks, this);

    {  // wait for maximum 2 seconds until the main camera becomes available
      std::unique_lock<std::mutex> lock(camera_device_available_lock_);
      camera_device_available_condition_.wait_for(lock, 2000ms, [&]() { return camera_device_available_; });
    }

    if (!camera_device_available_) {
//      ALOGE("Timed out waiting for Main camera!");
      return MLResult_Timeout;
    } else {
//      ALOGI("Main camera is available!");
    }

    MLCameraConnectContext camera_connect_context = {};
    MLCameraConnectContextInit(&camera_connect_context);
    camera_connect_context.cam_id = MLCameraIdentifier_MAIN;
    camera_connect_context.flags = MLCameraConnectFlag_CamOnly;
    camera_connect_context.enable_video_stab = false;
    MLCameraConnect(&camera_connect_context, &camera_context_);
    SetCameraDeviceStatusCallbacks();
    SetCameraCaptureCallbacks();
    return MLResult_Ok;
  }

  MLResult StartCapture() {
    if (has_capture_started_) {
      return MLResult_Ok;
    }
    MLHandle metadata_handle = ML_INVALID_HANDLE;
    MLCameraCaptureConfig config = {};
    MLCameraCaptureConfigInit(&config);
    config.stream_config[0].capture_type = MLCameraCaptureType_Video;
    config.stream_config[0].width = capture_width_;
    config.stream_config[0].height = capture_height_;
    config.stream_config[0].output_format = MLCameraOutputFormat_RGBA_8888;
    config.stream_config[0].native_surface_handle = ML_INVALID_HANDLE;
    config.capture_frame_rate = MLCameraCaptureFrameRate_30FPS;
    config.num_streams = 1;
    MLCameraPrepareCapture(camera_context_, &config, &metadata_handle);
    MLCameraPreCaptureAEAWB(camera_context_);
    MLCameraCaptureVideoStart(camera_context_);
    has_capture_started_ = true;
    return MLResult_Ok;
  }

  static void CheckDeviceAvailability(const MLCameraDeviceAvailabilityInfo *device_availability_info,
                                      bool is_available) {
    if (device_availability_info == nullptr) {
      return;
    }
    ML2Camera *this_app = static_cast<ML2Camera *>(device_availability_info->user_data);
    if (this_app) {
      if (device_availability_info->cam_id == MLCameraIdentifier_MAIN) {
        {
          std::unique_lock<std::mutex> lock(this_app->camera_device_available_lock_);
          this_app->camera_device_available_ = is_available;
        }
        this_app->camera_device_available_condition_.notify_one();
      }
    }
  }

  MLResult SetCameraCaptureCallbacks() {
    MLCameraCaptureCallbacks camera_capture_callbacks = {};
    MLCameraCaptureCallbacksInit(&camera_capture_callbacks);

    camera_capture_callbacks.on_capture_failed = [](const MLCameraResultExtras *, void *) {
//      ALOGE("on_capture_failed() callback called!");
    };

    camera_capture_callbacks.on_capture_aborted = [](void *) {
//        ALOGE("on_capture_aborted() callback called!");
    };

    camera_capture_callbacks.on_video_buffer_available = OnVideoAvailable;
    MLCameraSetCaptureCallbacks(camera_context_, &camera_capture_callbacks, this);
    return MLResult_Ok;
  }

  MLResult SetCameraDeviceStatusCallbacks() {
    MLCameraDeviceStatusCallbacks camera_device_status_callbacks = {};
    MLCameraDeviceStatusCallbacksInit(&camera_device_status_callbacks);

    camera_device_status_callbacks.on_device_error = [](MLCameraError err, void *) {
//      ALOGE("on_device_error(%s) callback called", EnumHelpers::GetMLCameraErrorString(err));
    };

    camera_device_status_callbacks.on_device_disconnected = [](MLCameraDisconnectReason reason, void *) {
//      ALOGE("on_device_disconnected(%s) callback called", EnumHelpers::GetMLCameraDisconnectReasonString(reason));
    };
    MLCameraSetDeviceStatusCallbacks(camera_context_, &camera_device_status_callbacks, this);
    return MLResult_Ok;
  }

  bool camera_device_available_, has_capture_started_;
  std::mutex camera_device_available_lock_;
  std::condition_variable camera_device_available_condition_;
  int32_t capture_width_, capture_height_;
  std::atomic_bool is_frame_available_;
  MLCameraContext camera_context_;
  uint8_t framebuffer_[1920 * 1080 * 4];  ///> 4-byte-per-pixel format, at max 1920 * 1080
};

ML2Camera *pML2CamObj = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_edu_cmu_cs_owf_ML2CameraCapture_createCamera(JNIEnv *env,
                                                  jobject instance,
                                                  jint width, jint height) {
  pML2CamObj = new ML2Camera(width, height);
  pML2CamObj->SetupRestrictedResources();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_edu_cmu_cs_owf_ML2CameraCapture_getFrame(JNIEnv *env,
                                              jobject instance,
                                              jint size) {
  if (pML2CamObj->is_frame_available_) {
    jbyteArray bytesCopied = env->NewByteArray(size * 4);
    env->SetByteArrayRegion(bytesCopied, 0, size * 4,
                            reinterpret_cast<const jbyte *>(pML2CamObj->framebuffer_));
    pML2CamObj->is_frame_available_ = false;
    return bytesCopied;
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_edu_cmu_cs_owf_ML2CameraCapture_stopCamera(JNIEnv *env,
                                                jobject instance) {
  pML2CamObj->StopCapture();
  pML2CamObj->DestroyCamera();
}