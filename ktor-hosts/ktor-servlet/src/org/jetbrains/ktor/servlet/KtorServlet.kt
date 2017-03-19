package org.jetbrains.ktor.servlet

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.future.*
import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.cio.*
import org.jetbrains.ktor.host.*
import java.lang.reflect.*
import javax.servlet.*
import javax.servlet.http.*
import kotlin.coroutines.experimental.*

abstract class KtorServlet : HttpServlet() {

    abstract val application: Application
    protected val hostPipeline by lazy { defaultHostPipeline(application.environment) }

    override fun service(request: HttpServletRequest, response: HttpServletResponse) {
        if (response.isCommitted) {
            return
        }

        response.characterEncoding = "UTF-8"
        request.characterEncoding = "UTF-8"

        try {
            val asyncContext = request.startAsync().apply {
                timeout = 0L
            }
            val call = ServletApplicationCall(application, request, response, NoPool, { call, block, next ->
                tryPush(request, call, block, next)
            })

            future(AsyncContextCoroutineDispatcher(asyncContext)) {
                try {
                    hostPipeline.execute(call)
                } finally {
                    asyncContext?.complete()
                }
            }
        } catch (ex: Throwable) {
            application.log.error("ServletApplicationHost cannot service the request", ex)
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ex.message)
        }
    }

    private fun tryPush(request: HttpServletRequest, call: ApplicationCall, block: ResponsePushBuilder.() -> Unit, next: () -> Unit) {
        listOf(
                "org.jetbrains.ktor.servlet.v4.PushKt.doPush",
                "org.jetbrains.ktor.servlet.v4.TomcatInternalPushKt.doPushInternal"
        ).mapNotNull { tryFind(it) }
                .firstOrNull { function ->
                    tryInvoke(function, request, call, block, next)
                } ?: next()
    }

    private fun tryInvoke(function: Method, request: HttpServletRequest, call: ApplicationCall, block: ResponsePushBuilder.() -> Unit, next: () -> Unit) = try {
        function.invoke(null, request, call, block, next)
        true
    } catch (ignore: ReflectiveOperationException) {
        false
    } catch (ignore: LinkageError) {
        false
    }

    private fun tryFind(spec: String): Method? = try {
        require("." in spec)
        val methodName = spec.substringAfterLast(".")

        Class.forName(spec.substringBeforeLast(".")).methods.singleOrNull { it.name == methodName }
    } catch (ignore: ReflectiveOperationException) {
        null
    } catch (ignore: LinkageError) {
        null
    }

    private class AsyncContextCoroutineDispatcher(val asyncContext: AsyncContext) : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = asyncContext.start(block)
    }
}