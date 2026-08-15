#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "libauroracore.h"

#define AURORA_CLOSE_NATIVE_SESSION_OPERATION 10
#define AURORA_NEXT_LOCAL_PACKET_OPERATION 15
#define AURORA_BEGIN_NATIVE_SESSION_JSON_OPERATION 16
#define AURORA_COMPLETE_NATIVE_SESSION_RAW_OPERATION 17
#define AURORA_INGRESS_LOCAL_PACKET_JSON_OPERATION 18
#define AURORA_CONFIGURE_TRUST_OPERATION 21
#define AURORA_RESERVATION_JSON_OPERATION 22
#define AURORA_MAX_TRUST_BYTES (64 * 1024)
#define AURORA_MAX_PROVISIONING_BYTES (16 * 1024 * 1024)
#define AURORA_MAX_RESERVATION_INPUT_BYTES ((16 * 1024 * 1024) + 4 + 1 + (64 * 48))
#define AURORA_MAX_RESERVATION_OUTPUT_BYTES ((4 * ((16 * 1024 * 1024 + 2) / 3)) + 384)
#define AURORA_MAX_ISSUER_WORK_OUTPUT_BYTES (32 * 1024)
#define AURORA_MAX_ISSUER_RESPONSE_BYTES (1024 * 1024)
#define AURORA_MAX_LOCAL_PACKET_BYTES 65535
#define AURORA_MAX_LOCAL_PACKET_RESULT_BYTES (2 * 1024 * 1024)

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
    if (operation != AURORA_CLOSE_NATIVE_SESSION_OPERATION &&
        operation != AURORA_NEXT_LOCAL_PACKET_OPERATION &&
        operation != AURORA_BEGIN_NATIVE_SESSION_JSON_OPERATION &&
        operation != AURORA_COMPLETE_NATIVE_SESSION_RAW_OPERATION &&
        operation != AURORA_INGRESS_LOCAL_PACKET_JSON_OPERATION &&
        operation != AURORA_CONFIGURE_TRUST_OPERATION &&
        operation != AURORA_RESERVATION_JSON_OPERATION) {
        return NULL;
    }
    if (argument < 0 ||
        ((operation == AURORA_BEGIN_NATIVE_SESSION_JSON_OPERATION || operation == AURORA_CONFIGURE_TRUST_OPERATION) && argument != 0) ||
        ((operation == AURORA_CLOSE_NATIVE_SESSION_OPERATION ||
          operation == AURORA_NEXT_LOCAL_PACKET_OPERATION ||
          operation == AURORA_COMPLETE_NATIVE_SESSION_RAW_OPERATION ||
          operation == AURORA_INGRESS_LOCAL_PACKET_JSON_OPERATION ||
          operation == AURORA_RESERVATION_JSON_OPERATION) && argument == 0)) {
        return NULL;
    }

    jsize input_length = input == NULL ? 0 : (*environment)->GetArrayLength(environment, input);
    int minimum_input_bytes = 0;
    int maximum_input_bytes = 0;
    int maximum_output_bytes = 1;
    switch (operation) {
        case AURORA_CLOSE_NATIVE_SESSION_OPERATION:
            break;
        case AURORA_NEXT_LOCAL_PACKET_OPERATION:
            maximum_output_bytes = 1 + AURORA_MAX_LOCAL_PACKET_BYTES;
            break;
        case AURORA_BEGIN_NATIVE_SESSION_JSON_OPERATION:
            minimum_input_bytes = 1;
            maximum_input_bytes = AURORA_MAX_PROVISIONING_BYTES;
            maximum_output_bytes = 1 + AURORA_MAX_ISSUER_WORK_OUTPUT_BYTES;
            break;
        case AURORA_COMPLETE_NATIVE_SESSION_RAW_OPERATION:
            minimum_input_bytes = 1;
            maximum_input_bytes = AURORA_MAX_ISSUER_RESPONSE_BYTES;
            break;
        case AURORA_INGRESS_LOCAL_PACKET_JSON_OPERATION:
            minimum_input_bytes = 1;
            maximum_input_bytes = AURORA_MAX_LOCAL_PACKET_BYTES;
            maximum_output_bytes = 1 + AURORA_MAX_LOCAL_PACKET_RESULT_BYTES;
            break;
        case AURORA_CONFIGURE_TRUST_OPERATION:
            minimum_input_bytes = 1;
            maximum_input_bytes = AURORA_MAX_TRUST_BYTES;
            break;
        case AURORA_RESERVATION_JSON_OPERATION:
            minimum_input_bytes = 1;
            maximum_input_bytes = AURORA_MAX_RESERVATION_INPUT_BYTES;
            maximum_output_bytes = 1 + AURORA_MAX_RESERVATION_OUTPUT_BYTES;
            break;
        default:
            return NULL;
    }
    if (input_length < minimum_input_bytes || input_length > maximum_input_bytes) {
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
    if (output_length <= 0 || output_length > maximum_output_bytes) {
        AuroraCoreZeroFree(native_output, output_length > 0 ? output_length : 0);
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
