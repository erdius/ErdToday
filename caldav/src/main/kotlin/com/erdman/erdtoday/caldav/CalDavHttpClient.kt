package com.erdman.erdtoday.caldav

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic

/** Builds a Ktor [HttpClient] preconfigured for CalDAV requests against Fastmail. */
fun buildCalDavHttpClient(email: String, appPassword: String): HttpClient =
    HttpClient(OkHttp) {
        followRedirects = false // dav4jvm handles redirects itself -- required, not optional
        install(Auth) {
            basic {
                credentials { BasicAuthCredentials(username = email, password = appPassword) }
                sendWithoutRequest { true } // CalDAV servers expect Basic auth on the first request
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 20_000
        }
    }
