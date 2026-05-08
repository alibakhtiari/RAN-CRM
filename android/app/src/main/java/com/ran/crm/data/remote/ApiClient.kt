package com.ran.crm.data.remote

import android.content.Intent
import com.google.gson.GsonBuilder
import com.ran.crm.BuildConfig
import com.ran.crm.CrmApplication
import com.ran.crm.data.local.PreferenceManager
import com.ran.crm.data.remote.model.RefreshTokenResponse
import com.ran.crm.utils.SyncLogger
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {

    private const val BASE_URL = BuildConfig.BASE_URL

    @Volatile private var authToken: String? = null

    @Suppress("DEPRECATION")
    private val gson =
            GsonBuilder()
                    .setLenient()
                    .registerTypeAdapter(
                            Boolean::class.java,
                            com.ran.crm.utils.BooleanTypeAdapter()
                    )
                    .registerTypeAdapter(
                            Boolean::class.javaObjectType,
                            com.ran.crm.utils.BooleanTypeAdapter()
                    )
                    .create()

    /**
     * OkHttp Authenticator that transparently refreshes the JWT when a 401 is received. If refresh
     * fails, broadcasts a logout so the UI can react.
     */
    private val tokenAuthenticator = Authenticator { _: Route?, response: okhttp3.Response ->
        // Don't retry if we've already tried to refresh (avoid infinite loops)
        if (response.request.header("X-Retry-Auth") != null) {
            broadcastLogout()
            return@Authenticator null
        }

        val currentToken =
                authToken
                        ?: run {
                            broadcastLogout()
                            return@Authenticator null
                        }

        // Try to refresh the token synchronously
        val refreshRequest =
                Request.Builder()
                        .url("${BASE_URL}auth/refresh")
                        .post(ByteArray(0).toRequestBody(null))
                        .header("Authorization", "Bearer $currentToken")
                        .build()

        try {
            // Use a plain OkHttpClient to avoid interceptor loops
            val plainClient =
                    OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(15, TimeUnit.SECONDS)
                            .build()
            val refreshResponse = plainClient.newCall(refreshRequest).execute()

            if (refreshResponse.isSuccessful) {
                val body = refreshResponse.body.string()
                val newToken =
                        body?.let { gson.fromJson(it, RefreshTokenResponse::class.java)?.token }
                if (newToken != null) {
                    // Update the stored token
                    setAuthToken(newToken)
                    val prefs = PreferenceManager(CrmApplication.instance)
                    prefs.authToken = newToken
                    SyncLogger.log("ApiClient: Token refreshed successfully")

                    // Retry the original request with the new token
                    return@Authenticator response.request
                            .newBuilder()
                            .header("Authorization", "Bearer $newToken")
                            .header("X-Retry-Auth", "true")
                            .build()
                }
            }
        } catch (e: Exception) {
            SyncLogger.log("ApiClient: Token refresh failed", e)
        }

        // Refresh failed → logout
        broadcastLogout()
        null
    }

    private val okHttpClient: OkHttpClient by lazy {
        val builder =
                OkHttpClient.Builder()
                        .cookieJar(MemoryCookieJar())
                        .authenticator(tokenAuthenticator)
                        .addInterceptor { chain ->
                            val original = chain.request()
                            val requestBuilder = original.newBuilder()

                            authToken?.let { requestBuilder.header("Authorization", "Bearer $it") }

                            chain.proceed(requestBuilder.build())
                        }
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)

        // Log HTTP bodies in debug builds or when debug mode is enabled in settings
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        }
        builder.addInterceptor { chain ->
            val prefs = PreferenceManager(CrmApplication.instance)
            loggingInterceptor.level = if (BuildConfig.DEBUG || prefs.isDebugMode) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            loggingInterceptor.intercept(chain)
        }

        builder.build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
    }

    val apiService: CrmApiService by lazy { retrofit.create(CrmApiService::class.java) }

    fun setAuthToken(token: String?) {
        authToken = token
    }

    fun getAuthToken(): String? = authToken

    private fun broadcastLogout() {
        SyncLogger.log("ApiClient: Auth failed after refresh attempt. Broadcasting logout.")
        try {
            val context = CrmApplication.instance
            val intent = Intent("com.ran.crm.ACTION_LOGOUT")
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            SyncLogger.log("ApiClient: Failed to broadcast logout", e)
        }
    }

    private class MemoryCookieJar : CookieJar {
        private val cookieStore = java.util.concurrent.ConcurrentHashMap<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: ArrayList()
        }
    }
}
