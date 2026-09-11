package com.nvmex.networkhelper.ui.chart

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EChartsLineChart(
    optionJson: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestOption = remember { arrayOf(optionJson) }
    val webView = remember {
        WebView.setWebContentsDebuggingEnabled(false)
        WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            isLongClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    val quoted = JSONObject.quote(latestOption[0])
                    view.evaluateJavascript("window.NHChartRender($quoted);", null)
                }
            }
            loadUrl("file:///android_asset/echarts/chart.html")
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier
    )

    LaunchedEffect(webView, optionJson) {
        latestOption[0] = optionJson
        val quoted = JSONObject.quote(optionJson)
        webView.evaluateJavascript("window.NHChartRender($quoted);", null)
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
}
