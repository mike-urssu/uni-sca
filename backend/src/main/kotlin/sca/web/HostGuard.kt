package sca.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI

/**
 * This server runs docker on request, so it only serves pages from itself on loopback, or on `sca.public-host`
 * when a reverse proxy that authenticates users puts it on a domain:
 * the Host check blocks DNS rebinding, the Origin check blocks other sites, and requiring JSON
 * on POST forces a CORS preflight that this server never answers.
 */
@Component
@ConditionalOnWebApplication
class HostGuard(@Value("\${sca.public-host:}") publicHost: String) : OncePerRequestFilter() {
    private val loopback = setOf("localhost", "127.0.0.1", "[::1]") + listOf(publicHost.lowercase()).filter { it.isNotEmpty() }

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val host = req.serverName?.let { if (':' in it && !it.startsWith("[")) "[$it]" else it }   // Tomcat takes it from the Host header
        val origin = req.getHeader("Origin")?.let { runCatching { URI(it).host?.let { h -> if (':' in h && !h.startsWith("[")) "[$h]" else h } }.getOrNull() ?: "invalid" }
        val type = req.contentType?.lowercase() ?: ""
        val badPost = req.method == "POST" && !type.startsWith("application/json")
        if (host !in loopback || (origin != null && origin !in loopback) || badPost) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN)
            return
        }
        chain.doFilter(req, res)
    }
}
