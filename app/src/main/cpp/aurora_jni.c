#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "libauroracore.h"

#define AURORA_RESERVATION_JSON_OPERATION 22
#define AURORA_MAX_RESERVATION_INPUT_BYTES ((16 * 1024 * 1024) + 4 + 1 + (64 * 48))
#define AURORA_MAX_RESERVATION_OUTPUT_BYTES ((4 * ((16 * 1024 * 1024 + 2) / 3)) + 384)

static void aurora_secure_zero(void *value, size_t length) {
    volatile uint8_t *cursor = (volatile uint8_t *)value;
    while (length > 0) {
        *cursor++ = 0;
        length--;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_org_aurora_protocol_android_core_NativeCoreJni_nativeCall(
    JNIEnv *environment,
    jobject receiver,
    jint operation,
    jbyteArray input,
    jlong argument
) {
    (void)receiver;
    if (operation != AURORA_RESERVATION_JSON_OPERATION || argument < 0) {
        return NULL;
    }

    jsize input_length = input == NULL ? 0 : (*environment)->GetArrayLength(environment, input);
    if (input_length < 0 || input_length > AURORA_MAX_RESERVATION_INPUT_BYTES) {
        return NULL;
    }

    uint8_t *native_input = NULL;
    if (input_length > 0) {
        native_input = malloc((size_t)input_length);
        if (native_input == NULL) {
            return NULL;
        }
        (*environment)->GetByteArrayRegion(environment, input, 0, input_length, (jbyte *)native_input);
        if ((*environment)->ExceptionCheck(environment)) {
            aurora_secure_zero(native_input, (size_t)input_length);
            free(native_input);
            return NULL;
        }
    }

    int output_length = 0;
    uint8_t *native_output = AuroraCoreCall(operation, native_input, input_length, (uint64_t)argument, &output_length);
    if (native_input != NULL) {
        aurora_secure_zero(native_input, (size_t)input_length);
        free(native_input);
    }
    if (native_output == NULL) {
        return NULL;
    }
    if (output_length <= 0 || output_length > AURORA_MAX_RESERVATION_OUTPUT_BYTES) {
        AuroraCoreFree(native_output);
        return NULL;
    }

    jbyteArray result = (*environment)->NewByteArray(environment, output_length);
    if (result == NULL) {
        AuroraCoreZeroFree(native_output, output_length);
        return NULL;
    }
    (*environment)->SetByteArrayRegion(environment, result, 0, output_length, (const jbyte *)native_output);
    AuroraCoreZeroFree(native_output, output_length);
    if ((*environment)->ExceptionCheck(environment)) {
        return NULL;
    }
    return result;
}
