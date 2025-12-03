/*
 * Copyright 2025 Amaan Qureshi <contact@amaanq.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.benzeneos.tls

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class ModernTLSSocketFactory : SSLSocketFactory() {
    private val wrapped: SSLSocketFactory = SSLContext
        .getInstance("TLS")
        .apply {
            init(null, null, null)
        }.socketFactory

    override fun getDefaultCipherSuites(): Array<String> = wrapped.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = wrapped.supportedCipherSuites

    override fun createSocket(): Socket = configureSocket(wrapped.createSocket())

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        configureSocket(wrapped.createSocket(s, host, port, autoClose))

    override fun createSocket(host: String, port: Int): Socket =
        configureSocket(wrapped.createSocket(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        configureSocket(wrapped.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        configureSocket(wrapped.createSocket(host, port))

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        configureSocket(wrapped.createSocket(address, port, localAddress, localPort))

    companion object {
        private fun configureSocket(socket: Socket): Socket {
            (socket as SSLSocket).enabledProtocols = arrayOf("TLSv1.3")
            return socket
        }
    }
}
