package com.music.bitchord.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.music.bitchord.R
import com.music.bitchord.data.deezer.DeezerAuthResult
import com.music.bitchord.data.deezer.DeezerService
import kotlinx.coroutines.launch

private const val DEEZER_LOGIN_URL = "https://www.deezer.com/login"
private const val DEEZER_COOKIE_DOMAIN = "https://www.deezer.com"
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/**
 * Extracts the `arl` cookie value from a standard `Cookie` header string, if present.
 */
internal fun extractArlFromCookieHeader(cookies: String?): String? {
    if (cookies.isNullOrBlank()) return null
    val match = Regex("""(?:^|;\s*)arl=([^;\s]+)""").find(cookies) ?: return null
    val value = match.groupValues.getOrNull(1)?.trim().orEmpty()
    return value.takeIf { it.length >= 32 && it != "deleted" }
}

/**
 * Interactive WebView login for Deezer. Loads `deezer.com/login`, monitors the `arl` cookie
 * via [CookieManager], validates the session against Deezer's gateway once present, and
 * invokes [onCaptured] with the authenticated `arl` token.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeezerLoginScreen(
    onCaptured: (arl: String) -> Unit,
    onCancel: () -> Unit,
) {
    var progress by remember { mutableIntStateOf(0) }
    var currentHost by remember { mutableStateOf("deezer.com") }
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
        val cookies = cm.getCookie(DEEZER_COOKIE_DOMAIN)
            ?: url?.let { cm.getCookie(it) }
        val candidateArl = extractArlFromCookieHeader(cookies) ?: return
        if (!triedArls.add(candidateArl)) return

        scope.launch {
            if (captured) return@launch
            when (val result = DeezerService.validateArl(candidateArl, persistOnSuccess = true)) {
                is DeezerAuthResult.Authenticated -> {
                    if (!captured) {
                        captured = true
                        cm.flush()
                        onCaptured(result.session.arl)
                    }
                }
                else -> {
                    // Not yet authenticated; allow retry if URL changes
                    triedArls.remove(candidateArl)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0C))
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { onCancel() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cancel),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = Color(0xFF4ADE80),
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.deezer_sign_in_title),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = currentHost,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
        } else {
            Spacer(Modifier.height(2.dp))
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        userAgentString = DESKTOP_USER_AGENT
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            url?.let {
                                currentHost = runCatching {
                                    android.net.Uri.parse(it).host ?: "deezer.com"
                                }.getOrDefault("deezer.com")
                            }
                            tryCaptureCookie(url)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            tryCaptureCookie(request?.url?.toString())
                            return false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            tryCaptureCookie(url)
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView?,
                            url: String?,
                            isReload: Boolean,
                        ) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            tryCaptureCookie(url)
                        }
                    }

                    loadUrl(DEEZER_LOGIN_URL)
                    webViewRef = this
                }
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                destroy()
            }
            webViewRef = null
        }
    }
}

/**
 * Clears WebView cookies for Deezer domains so a subsequent login starts fresh.
 */
fun clearDeezerWebSession() {
    val cm = CookieManager.getInstance()
    val domains = listOf(
        "https://www.deezer.com",
        "https://deezer.com",
        "https://connect.deezer.com",
    )
    for (domain in domains) {
        val existing = cm.getCookie(domain) ?: continue
        existing.split(";").forEach { raw ->
            val name = raw.substringBefore("=").trim()
            if (name.isNotEmpty()) {
                cm.setCookie(domain, "$name=; Max-Age=0; Path=/")
                cm.setCookie(domain, "$name=; Max-Age=0; Domain=.deezer.com; Path=/")
            }
        }
    }
    cm.flush()
}
