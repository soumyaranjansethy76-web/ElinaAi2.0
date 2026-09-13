package com.elina.assistant.avatar

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.Uri
import androidx.webkit.WebViewAssetLoader

class AvatarRenderer(context:Context, private val onReady:()->Unit = {}) {
    private val webView=WebView(context)
    private var ready=false
    init{
        webView.settings.javaScriptEnabled=true
        webView.settings.domStorageEnabled=true
        webView.settings.allowFileAccess=false
        webView.webViewClient=WebViewClient()
        val assetLoader=WebViewAssetLoader.Builder().addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(context)).build()
        webView.webViewClient=object:WebViewClient(){ override fun shouldInterceptRequest(v:WebView?,u:String?)=assetLoader.shouldInterceptRequest(Uri.parse(u)) }
        webView.addJavascriptInterface(AvatarBridge(),"ElinaNative")
        webView.loadUrl("https://appassets.androidplatform.net/assets/avatar/scene.html")
    }
    fun view():WebView=webView
    fun setEmotion(emotion:String,strength:Float)=eval("window.elina?.emotion(${js(emotion)},${strength.coerceIn(0f,1f)});")
    fun setLip(shape:String,amount:Float)=eval("window.elina?.lip(${js(shape)},${amount.coerceIn(0f,1f)});")
    fun blink()=eval("window.elina?.blink();")
    fun lookAt(x:Float,y:Float)=eval("window.elina?.look(${x.coerceIn(-1f,1f)},${y.coerceIn(-1f,1f)});")
    fun release(){webView.loadUrl("about:blank");webView.removeAllViews();webView.destroy()}
    private fun eval(js:String){webView.post{webView.evaluateJavascript(js,null)}}
    private fun js(s:String)="'"+s.replace("'","\\'")+"'"
    inner class AvatarBridge { @android.webkit.JavascriptInterface fun ready(){ ready=true; onReady() } }
}
