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

#include <jni.h>

#include <ml_audio.h>

#include <algorithm>
#include <fstream>
#include <limits>
#include <vector>

constexpr auto kKibibyte = 2 << 10;
constexpr auto kMaxBufferSize = 920 * kKibibyte;  // Around 58 seconds at 16kHz sampling rate

class ML2Audio {
public:
    ML2Audio(jint channelCount, jint sampleRate)
      : audio_input_handle_(ML_INVALID_HANDLE),
        audio_channel_count_(channelCount),
        audio_sample_rate_(sampleRate),
        buffer_format({})
    {}

  void InitializeAudio() {
    if (audio_input_handle_ != ML_INVALID_HANDLE) {
      return;
    }

    uint32_t recommended_size;

    MLAudioGetBufferedInputDefaults(audio_channel_count_,
                                    audio_sample_rate_,
                                    &buffer_format,
                                    &recommended_size,
                                    nullptr);

    MLAudioBufferCallback input_callback = [](MLHandle handle, void *callback_context) {
        auto *self = reinterpret_cast<ML2Audio *>(callback_context);
        self->FillInputBuffer();
    };

    MLAudioCreateInputFromMicCapture(MLAudioMicCaptureType_VoiceComm,
                                     &buffer_format,
                                     recommended_size,
                                     input_callback,
                                     (void *)this,
                                     &audio_input_handle_);
  }

  bool FillInputBuffer() {
    if (audio_input_handle_ == ML_INVALID_HANDLE) {
      return false;
    }

    MLAudioBuffer buffer = {};
    MLResult result{MLAudioGetInputBuffer(audio_input_handle_, &buffer)};
    if (MLAudioResult_BufferNotReady == result) {
      return false;
    }

    std::lock_guard<std::mutex> lock{pcm_buffer_mtx_};

    if (pcm_buffer_.size() + buffer.size <= kMaxBufferSize) {
      auto old_size = pcm_buffer_.size();
      pcm_buffer_.resize(pcm_buffer_.size() + buffer.size);
      memcpy(pcm_buffer_.data() + old_size, buffer.ptr, buffer.size);
    } else {
      // Out of space in buffer
      MLAudioReleaseInputBuffer(audio_input_handle_);
//      ALOGI("Record buffer full. Recording stopped.");
      return false;
    }

    MLAudioReleaseInputBuffer(audio_input_handle_);
    return true;
  }

  MLHandle audio_input_handle_ = ML_INVALID_HANDLE;
  uint32_t audio_channel_count_, audio_sample_rate_;
  std::vector<uint8_t> pcm_buffer_;
  std::mutex pcm_buffer_mtx_;
  MLAudioBufferFormat buffer_format;
};

ML2Audio *pML2AudioObj = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_edu_cmu_cs_owf_ML2AudioCapture_createAudioInput(JNIEnv *env,
                                                     jobject instance,
                                                     jint channelCount,
                                                     jint sampleRate) {
  pML2AudioObj = new ML2Audio(channelCount, sampleRate);
  pML2AudioObj->InitializeAudio();
}

extern "C" JNIEXPORT jint JNICALL
Java_edu_cmu_cs_owf_ML2AudioCapture_getBitDepth(JNIEnv *env,
                                                jobject instance) {
  if (pML2AudioObj == nullptr) {
    return -1;
  }
  return (int)pML2AudioObj->buffer_format.bits_per_sample;
}

extern "C" JNIEXPORT jint JNICALL
Java_edu_cmu_cs_owf_ML2AudioCapture_getAudioInputState(JNIEnv *env,
                                                       jobject instance) {
  if (pML2AudioObj == nullptr) {
    return -1;
  }
  MLAudioState state;
  MLAudioGetInputState(pML2AudioObj->audio_input_handle_, &state);
  switch (state) {
    case MLAudioState_Stopped:
    case MLAudioState_Paused:
      return 0x1;  /// RECORDSTATE_STOPPED
    case MLAudioState_Playing:
      return 0x3;  /// RECORDSTATE_RECORDING
    default:
      return 0x0;
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_edu_cmu_cs_owf_ML2AudioCapture_readAudioBuffer(JNIEnv *env,
                                                    jobject instance,
                                                    jshortArray audioData,
                                                    jint offsetInShorts,
                                                    jint sizeInShorts) {
  if (pML2AudioObj == nullptr) {
    return -3;  /// ERROR_INVALID_OPERATION
  }
  if (audioData == nullptr) {
    return -2;  /// ERROR_BAD_VALUE
  }
  auto *recordBuff = env->GetShortArrayElements(audioData, nullptr);
  if (recordBuff == nullptr) {
    return -2;  /// ERROR_BAD_VALUE
  }

  std::lock_guard<std::mutex> lock{pML2AudioObj->pcm_buffer_mtx_};
  size_t read_size_in_short = std::min((size_t)(sizeInShorts),
                              pML2AudioObj->pcm_buffer_.size() / sizeof(jshort));
  size_t read_size_in_byte = read_size_in_short * sizeof(jshort);
  if (read_size_in_byte > 0) {
    memcpy(recordBuff + offsetInShorts,
           pML2AudioObj->pcm_buffer_.data(),
           read_size_in_byte);
    pML2AudioObj->pcm_buffer_.erase(pML2AudioObj->pcm_buffer_.begin(),
                                    pML2AudioObj->pcm_buffer_.begin() + (long)read_size_in_byte);
  }

  env->ReleaseShortArrayElements(audioData, recordBuff, 0);
  return (jint)read_size_in_short;
}

extern "C" JNIEXPORT void JNICALL
Java_edu_cmu_cs_owf_ML2AudioCapture_startAudioInput(JNIEnv *env,
                                                    jobject instance) {
  if (pML2AudioObj == nullptr) {
    return;
  }
  MLAudioStartInput(pML2AudioObj->audio_input_handle_);
}

extern "C" JNIEXPORT void JNICALL
Java_edu_cmu_cs_owf_ML2AudioCapture_stopAudioInput(JNIEnv *env,
                                                   jobject instance) {
  if (pML2AudioObj == nullptr) {
    return;
  }
  MLAudioStopInput(pML2AudioObj->audio_input_handle_);
  std::lock_guard<std::mutex> lock{pML2AudioObj->pcm_buffer_mtx_};
  pML2AudioObj->pcm_buffer_.clear();
}

extern "C" JNIEXPORT void JNICALL
Java_edu_cmu_cs_owf_ML2AudioCapture_destroyAudioInput(JNIEnv *env,
                                                      jobject instance) {
  if (pML2AudioObj == nullptr) {
    return;
  }
  if (pML2AudioObj->audio_input_handle_ != ML_INVALID_HANDLE) {
    MLAudioDestroyInput(pML2AudioObj->audio_input_handle_);
    pML2AudioObj->audio_input_handle_ = ML_INVALID_HANDLE;
  }
}
