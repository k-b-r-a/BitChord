package com.music.bitchord.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.music.bitchord.R
import com.music.bitchord.data.deezer.DeezerAuthResult
import com.music.bitchord.data.deezer.DeezerService
import kotlinx.coroutines.launch

private const val DEEZER_LOGIN_URL = "https://www.deezer.com/login"
private const val DEEZER_COOKIE_DOMAIN = "https://www.deezer.com"
private val DEEZER_COOKIE_URLS = listOf(
    "https://www.deezer.com",
    "https://deezer.com",
    "https://connect.deezer.com",
)

internal fun extractArlFromCookieHeader(cookies: String?): String? {
    if (cookies.isNullOrBlank()) return null
    val value = Regex("""(?:^|;\s*)arl=([^;\s]+)""").find(cookies)?.groupValues?.getOrNull(1)?.trim()
    return value?.takeIf { it.length >= 32 && it != "deleted" }
}

/**
 * Interactive WebView login for Deezer. Captures the `arl` cookie via [CookieManager],
 * validates it against Deezer's gateway, and invokes [onCaptured] once authenticated.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeezerLoginScreen(
    onCaptured: (arl: String) -> Unit,
    onCancel: () -> Unit,
) {
    var captured by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val scope = rememberCoroutineScope()
    val triedArls = remember { mutableSetOf<String>() }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) wv.goBack() else onCancel()
    }

    fun tryCaptureCookie(url: String?) {
        if (captured) return
        val cm = CookieManager.getInstance()
        val cookies = cm.getCookie(DEEZER_COOKIE_DOMAIN) ?: url?.let(cm::getCookie)
        val candidateArl = extractArlFromCookieHeader(cookies) ?: return
        if (!triedArls.add(candidateArl)) return

        scope.launch {
            if (captured) return@launch
            when (val result = DeezerService.validateArl(candidateArl, persistOnSuccess = true)) {
                is DeezerAuthResult.Authenticated -> if (!captured) {
                    captured = true
                    cm.flush()
                    onCaptured(result.session.arl)
                }
                else -> triedArls.remove(candidateArl)
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cancel),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    stringResource(R.string.deezer_sign_in_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .navigationBarsPadding(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = browserUserAgent(settings.userAgentString)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) = tryCaptureCookie(url)
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                tryCaptureCookie(request?.url?.toString())
                                return false
                            }
                            override fun onPageFinished(view: WebView?, url: String?) = tryCaptureCookie(url)
                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) = tryCaptureCookie(url)
                        }
                        loadUrl(DEEZER_LOGIN_URL)
                        webViewRef = this
                    }
                },
                onRelease = {
                    it.stopLoading()
                    it.destroy()
                },
            )
        }
    }
}

fun clearDeezerWebSession() {
    val cm = CookieManager.getInstance()
    for (domain in DEEZER_COOKIE_URLS) {
        val names = cm.getCookie(domain)?.split(";")
            ?.map { it.substringBefore("=").trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        for (name in names) {
            val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
            cm.setCookie(domain, expired)
            cm.setCookie(domain, "$expired; Domain=.deezer.com")
        }
    }
    cm.flush()
}
