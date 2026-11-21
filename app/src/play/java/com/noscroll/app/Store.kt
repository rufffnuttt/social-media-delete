package com.noscroll.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import kotlin.concurrent.thread

/**
 * Pro via the DB Audio Services website: Google sign-in in the app, payment with
 * Stripe on the website, and the website tells the app whether the account has Pro.
 * Site address and Google client ID come from BuildConfig (see README).
 */
object Store {
    const val ALWAYS_PRO = false
    const val IS_STORE_BUILD = true

    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private val site get() = BuildConfig.SITE_URL.trimEnd('/')

    val isConfigured: Boolean
        get() = site.startsWith("https://") && !site.contains("YOUR-") &&
            BuildConfig.GOOGLE_WEB_CLIENT_ID.endsWith(".apps.googleusercontent.com")

    fun isSignedIn(context: Context) = Prefs(context).authToken.isNotEmpty()

    // ---- Sign in with Google ----------------------------------------------------

    /** Shows Google's account picker, then signs in to the website. onDone(error or null). */
    fun signIn(activity: Activity, onDone: (String?) -> Unit) {
        if (!isConfigured) {
            onDone("Sign-in isn't set up in this build yet.")
            return
        }
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        CredentialManager.create(activity).getCredentialAsync(
            activity, request, CancellationSignal(), mainExecutor,
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val cred = result.credential
                    if (cred is CustomCredential &&
                        cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    ) {
                        val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
                        exchangeToken(activity, idToken, onDone)
                    } else {
                        onDone("Unexpected sign-in response")
                    }
                }

                override fun onError(e: GetCredentialException) {
                    onDone(e.message ?: "Sign-in was cancelled")
                }
            },
        )
    }

    private fun exchangeToken(context: Context, idToken: String, onDone: (String?) -> Unit) {
        val prefs = Prefs(context)
        request("POST", "/api/noscroll/auth", JSONObject().put("idToken", idToken), token = null) { code, json ->
            if (code == 200 && json != null && json.optString("token").isNotEmpty()) {
                prefs.authToken = json.getString("token")
                prefs.accountEmail = json.optString("email")
                saveStatus(prefs, json)
                onDone(null)
            } else {
                onDone(json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "Couldn't sign in (error $code)")
            }
        }
    }

    fun signOut(context: Context, onDone: () -> Unit = {}) {
        val prefs = Prefs(context)
        val token = prefs.authToken
        prefs.authToken = ""
        prefs.accountEmail = ""
        prefs.proKind = ""
        prefs.proUntil = 0L
        if (token.isNotEmpty()) request("POST", "/api/noscroll/logout", null, token) { _, _ -> }
        onDone()
    }

    // ---- Pro status ---------------------------------------------------------------

    /** Asks the website whether this account has Pro and caches it (so it works offline). */
    fun refresh(context: Context, onDone: () -> Unit = {}) {
        val prefs = Prefs(context)
        val token = prefs.authToken
        if (token.isEmpty() || !isConfigured) {
            main.post(onDone)
            return
        }
        request("GET", "/api/noscroll/status", null, token) { code, json ->
            when {
                code == 200 && json != null -> saveStatus(prefs, json)
                code == 401 -> signOut(context)        // session revoked or expired
                // Network errors: keep the cached status.
            }
            onDone()
        }
    }

    private fun saveStatus(prefs: Prefs, json: JSONObject) {
        if (json.optBoolean("pro")) {
            val lifetime = json.optString("plan") == "lifetime"
            prefs.proKind = if (lifetime) "lifetime" else "subscription"
            prefs.proUntil = if (lifetime) 0L else json.optLong("expiresAt", 0L)
        } else {
            prefs.proKind = ""
            prefs.proUntil = 0L
        }
    }

    // ---- Plans, checkout, billing portal ------------------------------------------

    fun loadPlans(context: Context, onLoaded: (List<Plan>) -> Unit) {
        if (!isConfigured) {
            main.post { onLoaded(emptyList()) }
            return
        }
        request("GET", "/api/noscroll/plans", null, token = null) { code, json ->
            val arr = json?.optJSONArray("plans")
            val plans = mutableListOf<Plan>()
            if (code == 200 && arr != null) {
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    plans += Plan(p.getString("id"), p.getString("label"), p.getString("price"), null, null)
                }
            }
            onLoaded(plans)
        }
    }

    /** Opens Stripe Checkout (on the website) in the browser. */
    fun buy(activity: Activity, plan: Plan, onDone: () -> Unit = {}) {
        val token = Prefs(activity).authToken
        request("POST", "/api/noscroll/checkout", JSONObject().put("plan", plan.id), token) { code, json ->
            openUrlOrError(activity, code, json)
            onDone()
        }
    }

    /** Stripe's customer portal: cancel, change plan, update card. */
    fun manage(activity: Activity) {
        val token = Prefs(activity).authToken
        request("POST", "/api/noscroll/portal", JSONObject(), token) { code, json ->
            openUrlOrError(activity, code, json)
        }
    }

    private fun openUrlOrError(activity: Activity, code: Int, json: JSONObject?) {
        val url = json?.optString("url").orEmpty()
        if (code == 200 && url.startsWith("http")) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } else {
            val msg = json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "Couldn't reach the website (error $code)"
            android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ---- HTTP -----------------------------------------------------------------------

    /** Small JSON HTTP helper; runs off the main thread, calls back on it. */
    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        token: String?,
        cb: (Int, JSONObject?) -> Unit,
    ) {
        thread {
            var code = -1
            var json: JSONObject? = null
            try {
                val conn = URL(site + path).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/json")
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }
                json = text?.let { runCatching { JSONObject(it) }.getOrNull() }
                conn.disconnect()
            } catch (e: Exception) {
                // code stays -1 (offline / server unreachable)
            }
            main.post { cb(code, json) }
        }
    }
}
