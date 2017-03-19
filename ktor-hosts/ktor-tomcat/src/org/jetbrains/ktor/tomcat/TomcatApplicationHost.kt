package org.jetbrains.ktor.tomcat

import org.apache.catalina.connector.*
import org.apache.catalina.startup.*
import org.apache.coyote.http2.*
import org.apache.tomcat.jni.*
import org.apache.tomcat.util.net.*
import org.apache.tomcat.util.net.jsse.*
import org.apache.tomcat.util.net.openssl.*
import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.host.*
import org.jetbrains.ktor.servlet.*
import org.jetbrains.ktor.transform.*
import java.nio.file.*
import java.util.concurrent.*
import javax.servlet.*

class TomcatApplicationHost(override val hostConfig: ApplicationHostConfig,
                            val environment: ApplicationEnvironment,
                            val lifecycle: ApplicationLifecycle) : ApplicationHostStartable {


    private val application: Application get() = lifecycle.application
    private val tempDirectory by lazy { Files.createTempDirectory("ktor-tomcat-") }

    constructor(hostConfig: ApplicationHostConfig, config: ApplicationEnvironment)
            : this(hostConfig, config, ApplicationLifecycleReloading(config, hostConfig.autoreload))

    private val ktorServlet = object : KtorServlet() {
        override val application: Application
            get() = this@TomcatApplicationHost.application
    }

    val server = Tomcat().apply {
        service.apply {
            findConnectors().forEach { existing ->
                removeConnector(existing)
            }

            hostConfig.connectors.forEach { ktorConnector ->
                addConnector(Connector().apply {
                    port = ktorConnector.port

                    if (ktorConnector is HostSSLConnectorConfig) {
                        secure = true
                        scheme = "https"

                        if (ktorConnector.keyStorePath == null) {
                            throw IllegalArgumentException("Tomcat requires keyStorePath")
                        }

                        setAttribute("keyAlias", ktorConnector.keyAlias)
                        setAttribute("keystorePass", String(ktorConnector.keyStorePassword()))
                        setAttribute("keyPass", String(ktorConnector.privateKeyPassword()))
                        setAttribute("keystoreFile", ktorConnector.keyStorePath!!.absolutePath)
                        setAttribute("clientAuth", false)
                        setAttribute("sslProtocol", "TLS")
                        setAttribute("SSLEnabled", true)

                        val sslImpl = chooseSSLImplementation()

                        setAttribute("sslImplementationName", sslImpl.name)

                        if (sslImpl.simpleName == "OpenSSLImplementation") {
                            addUpgradeProtocol(Http2Protocol())
                        }
                    } else {
                        scheme = "http"
                    }
                })
            }
        }

        if (connector == null) {
            connector = service.findConnectors()?.firstOrNull() ?: Connector().apply { port = 80 }
        }
        setBaseDir(tempDirectory.toString())

        val ctx = addContext("", tempDirectory.toString())

        Tomcat.addServlet(ctx, "ktor-servlet", ktorServlet).apply {
            addMapping("/*")
            isAsyncSupported = true
            multipartConfigElement = MultipartConfigElement("")
        }
    }

    init {
        environment.monitor.applicationStart += {
            it.install(ApplicationTransform).registerDefaultHandlers()
        }
    }

    override fun start(wait: Boolean): TomcatApplicationHost {
        lifecycle.start()
        server.start()
        if (wait) {
            server.server.await()
            stop(1, 5, TimeUnit.SECONDS)
        }
        return this
    }

    override fun stop(gracePeriod: Long, timeout: Long, timeUnit: TimeUnit) {
        server.stop()
        lifecycle.stop()
        tempDirectory.toFile().deleteRecursively()
    }

    companion object {
        private val nativeNames = listOf("netty-tcnative", "libnetty-tcnative", "netty-tcnative-1", "libnetty-tcnative-1", "tcnative-1", "libtcnative-1", "netty-tcnative-windows-x86_64")

        private fun chooseSSLImplementation(): Class<out SSLImplementation> {
            return try {
                val nativeName = nativeNames.firstOrNull { tryLoadLibrary(it) }
                if (nativeName != null) {
                    Library.initialize(nativeName)
                    SSL.initialize(null)
                    SSL.freeSSL(SSL.newSSL(SSL.SSL_PROTOCOL_ALL.toLong(), true))
                    OpenSSLImplementation::class.java
                } else {
                    JSSEImplementation::class.java
                }
            } catch (t: Throwable) {
                JSSEImplementation::class.java
            }
        }

        private fun tryLoadLibrary(libraryName: String): Boolean = try {
            System.loadLibrary(libraryName)
            true
        } catch(t: Throwable) {
            false
        }
    }
}