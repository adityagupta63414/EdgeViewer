#include <jni.h>
#include <opencv2/opencv.hpp>

extern "C"
JNIEXPORT void JNICALL
Java_com_example_edgeviewer_NativeBridge_processFrame(
        JNIEnv *env, jclass,
        jbyteArray frameData,
        jint width, jint height,
        jobject outputBuffer) {

    // Validate input
    if (frameData == nullptr || outputBuffer == nullptr || width <= 0 || height <= 0) {
        return;
    }

    // Get camera NV21 data
    jbyte *data = env->GetByteArrayElements(frameData, nullptr);
    if (!data) return;

    // Create YUV Mat (NV21: height + height/2)
    cv::Mat yuv(height + height / 2, width, CV_8UC1, reinterpret_cast<unsigned char*>(data));

    cv::Mat rgba, gray, edges;

    // Convert formats
    cv::cvtColor(yuv, rgba, cv::COLOR_YUV2RGBA_NV21);
    cv::cvtColor(rgba, gray, cv::COLOR_RGBA2GRAY);

    // Canny edge detection
    cv::Canny(gray, edges, 80, 150);

    // Get output buffer as uchar*
    auto out = reinterpret_cast<unsigned char*>(env->GetDirectBufferAddress(outputBuffer));
    if (out != nullptr) {
        memcpy(out, edges.data, static_cast<size_t>(width) * static_cast<size_t>(height));
    }

    // Release camera frame buffer
    env->ReleaseByteArrayElements(frameData, data, JNI_ABORT);
}
