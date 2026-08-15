package org.aurora.protocol.android.core

import android.content.Context
import android.content.res.AssetManager
import java.io.InputStream

internal class NativeTrustConfigurationException(
    val reason: Reason,
) : IllegalStateException(reason.message) {
    enum class Reason(val message: String) {
        RESOURCE_UNAVAILABLE("native trust resource is unavailable"),
        INVALID_RESOURCE("native trust resource is invalid"),
        CORE_REJECTED("native trust resource was rejected"),
    }
}

internal object NativeTrustConfigurator {
    const val maximumResourceBytes = 64 * 1024
    private const val resourceName = "AuroraSignedSeedTrust.bin"

    fun configure(context: Context) {
        configure(
            openResource = {
                try {
                    context.assets.open(resourceName, AssetManager.ACCESS_STREAMING)
                } catch (error: Exception) {
                    throw NativeTrustConfigurationException(
                        NativeTrustConfigurationException.Reason.RESOURCE_UNAVAILABLE,
                    )
                }
            },
            configureCore = NativeCoreJni::configureNativeProvisioningTrust,
        )
    }

    internal fun configure(
        openResource: () -> InputStream,
        configureCore: (ByteArray) -> Boolean,
    ) {
        val encoded = try {
            openResource().use(::readBounded)
        } catch (error: NativeTrustConfigurationException) {
            throw error
        } catch (_: Exception) {
            throw NativeTrustConfigurationException(NativeTrustConfigurationException.Reason.RESOURCE_UNAVAILABLE)
        }
        try {
            if (!configureCore(encoded)) {
                throw NativeTrustConfigurationException(NativeTrustConfigurationException.Reason.CORE_REJECTED)
            }
        } catch (error: NativeTrustConfigurationException) {
            throw error
        } catch (_: RuntimeException) {
            throw NativeTrustConfigurationException(NativeTrustConfigurationException.Reason.CORE_REJECTED)
        } finally {
            encoded.fill(0)
        }
    }

    private fun readBounded(stream: InputStream): ByteArray {
        val buffer = ByteArray(maximumResourceBytes + 1)
        try {
            var offset = 0
            while (offset < buffer.size) {
                val count = stream.read(buffer, offset, buffer.size - offset)
                if (count < 0) {
                    break
                }
                if (count == 0) {
                    val next = stream.read()
                    if (next < 0) {
                        break
                    }
                    buffer[offset] = next.toByte()
                    offset += 1
                } else {
                    offset += count
                }
            }
            if (offset == 0 || offset > maximumResourceBytes) {
                throw NativeTrustConfigurationException(NativeTrustConfigurationException.Reason.INVALID_RESOURCE)
            }
            return buffer.copyOf(offset)
        } finally {
            buffer.fill(0)
        }
    }
}
