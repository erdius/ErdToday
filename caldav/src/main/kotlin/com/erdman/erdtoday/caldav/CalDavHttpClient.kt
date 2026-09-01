package com.erdman.erdtoday.caldav

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic

/**
 * Builds a Ktor [HttpClient] preconfigured for CalDAV requests against Fastmail.
 *
 * `internal`, not `public`: this module's only public surface is [CalDavDiscovery], which takes
 * plain email/password strings and returns a plain URL string -- no Ktor or dav4jvm type ever
 * crosses the module boundary into :app. That keeps :app's kapt/kotlinc from ever needing to
 * resolve Ktor's own newer-Kotlin-metadata classes, which otherwise requires the same
 * -Xskip-metadata-version-check workaround this module needs for dav4jvm/Ktor/okio.
 */
internal fun buildCalDavHttpClient(email: String, appPassword: String): HttpClient =
    HttpClient(OkHttp) {
        followRedirects = false // dav4jvm handles redirects itself -- required, not optional
        install(Auth) {
            basic {
                credentials { BasicAuthCredentials(username = email, password = appPassword) }
                // CalDAV servers expect Basic auth on the first request -- but only send it to
                // the Fastmail host itself, not to wherever a redirect (compromised DNS, a
                // misbehaving proxy) might point.
                sendWithoutRequest { request -> request.url.host.endsWith("fastmail.com") }
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 20_000
        }
    }
