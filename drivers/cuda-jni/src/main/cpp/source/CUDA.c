#include <jni.h>
#include <cuda.h>

/*
 * Class:     uk_ac_manchester_tornado_drivers_cuda_CUDA
 * Method:    cuInit
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_uk_ac_manchester_tornado_drivers_cuda_CUDA_cuInit
  (JNIEnv *env, jclass clazz) {

    cuInit(0);
    return;
}