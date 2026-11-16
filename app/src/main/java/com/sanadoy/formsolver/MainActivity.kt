package com.sanadoy.formsolver

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var urlBox: EditText
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        urlBox = EditText(this).apply {
            hint = "Tempel link Google Form"
            isSingleLine = true
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> openUrl(); true }
        }
        top.addView(urlBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Button(this).apply {
            text = "Buka"
            setOnClickListener { openUrl() }
        })

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = WebViewClient()

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        bottom.addView(Button(this).apply {
            text = "Solve"
            setOnClickListener { solve() }
        })
        bottom.addView(Button(this).apply {
            text = "Pengaturan"
            setOnClickListener { showSettings() }
        })
        status = TextView(this).apply {
            text = "Siap."
            textSize = 12f
            setPadding(dp(8), 0, 0, 0)
        }
        bottom.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(top)
        root.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottom)
        setContentView(root)
    }

    private fun setStatus(t: String) = runOnUiThread { status.text = t }

    private fun openUrl() {
        var u = urlBox.text.toString().trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http")) u = "https://$u"
        web.loadUrl(u)
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        val key = EditText(this).apply {
            hint = "API key Gemini (dari aistudio.google.com/apikey)"
            setText(prefs.getString("key", ""))
            isSingleLine = true
        }
        val model = EditText(this).apply {
            hint = "Model (default: gemini-2.5-flash)"
            setText(prefs.getString("model", ""))
            isSingleLine = true
        }
        box.addView(key)
        box.addView(model)
        AlertDialog.Builder(this)
            .setTitle("Pengaturan")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                prefs.edit()
                    .putString("key", key.text.toString().trim())
                    .putString("model", model.text.toString().trim())
                    .apply()
                setStatus("Pengaturan tersimpan.")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun solve() {
        val key = prefs.getString("key", "") ?: ""
        val model = (prefs.getString("model", "") ?: "").ifBlank { "gemini-2.5-flash" }
        if (key.isBlank()) {
            setStatus("Isi API key dulu di Pengaturan.")
            showSettings()
            return
        }
        setStatus("Membaca soal...")
        web.evaluateJavascript(JS_LIB + "; window.__extract();") { raw ->
            val json = try {
                JSONTokener(raw).nextValue() as? String
            } catch (e: Exception) {
                null
            }
            if (json.isNullOrBlank() || json == "[]") {
                setStatus("Soal tidak ditemukan. Buka halaman form (viewform) dulu.")
                return@evaluateJavascript
            }
            val count = JSONArray(json).length()
            setStatus("Meminta jawaban AI untuk $count soal...")
            thread {
                try {
                    val answers = callGemini(json, key, model)
                    runOnUiThread {
                        web.evaluateJavascript("window.__fill(" + JSONObject.quote(answers) + ");", null)
                        status.text = "Jawaban diisi. Periksa dulu sebelum kirim."
                    }
                } catch (e: Exception) {
                    setStatus("Error: " + e.message)
                }
            }
        }
    }

    private fun callGemini(questions: String, key: String, model: String): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put(
                "contents", JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", questions)))
                )
            )
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/" + model.trim() + ":generateContent")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 90000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", key)
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw Exception("API $code: " + text.take(200))

        val out = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        val s = out.indexOf('[')
        val e = out.lastIndexOf(']')
        if (s < 0 || e < 0) throw Exception("Balasan AI tidak valid")
        return out.substring(s, e + 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    companion object {
        const val SYSTEM = "Kamu membantu mengisi formulir. Kamu menerima array JSON soal dengan field: " +
            "id, type (radio | checkbox | dropdown | text), question, options (untuk radio/checkbox/dropdown). " +
            "Balas HANYA dengan array JSON tanpa teks lain. Format: [{\"id\": 0, \"answer\": ...}]. " +
            "Aturan: radio/dropdown: answer = SATU string yang persis sama dengan salah satu options. " +
            "checkbox: answer = array string, tiap item persis sama dengan salah satu options. " +
            "text: answer = string jawaban singkat dan langsung. " +
            "Jika tidak yakin, tetap pilih jawaban paling mungkin benar."

        val JS_LIB = """
(function(){
  if (window.__extract) return;
  var norm = function(s){ return (s||'').replace(/\s+/g,' ').trim().toLowerCase(); };
  var lab = function(e){ return e.getAttribute('data-value') || e.getAttribute('data-answer-value') || e.getAttribute('aria-label') || ''; };
  var items = function(){ return Array.prototype.slice.call(document.querySelectorAll('div[role="listitem"]')); };
  var all = function(el, sel){ return Array.prototype.slice.call(el.querySelectorAll(sel)); };
  var sleep = function(ms){ return new Promise(function(r){ setTimeout(r, ms); }); };

  window.__extract = function(){
    var out = [];
    items().forEach(function(el, id){
      var h = el.querySelector('div[role="heading"]');
      if (!h) return;
      var question = h.innerText.replace(/\s*\*\s*$/, '').trim();
      var radios = all(el, 'div[role="radio"]');
      var checks = all(el, 'div[role="checkbox"]');
      var listbox = el.querySelector('div[role="listbox"]');
      var text = el.querySelector('input[type="text"], textarea');
      if (radios.length) out.push({id:id, type:'radio', question:question, options:radios.map(lab)});
      else if (checks.length) out.push({id:id, type:'checkbox', question:question, options:checks.map(lab)});
      else if (listbox) out.push({id:id, type:'dropdown', question:question,
        options: all(listbox, 'div[role="option"]').map(lab).filter(Boolean)});
      else if (text) out.push({id:id, type:'text', question:question});
    });
    return JSON.stringify(out);
  };

  var setText = function(input, value){
    var proto = input.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(input, value);
    input.dispatchEvent(new Event('input', {bubbles:true}));
    input.dispatchEvent(new Event('change', {bubbles:true}));
  };
  var find = function(els, target){
    var t = norm(target);
    for (var i=0;i<els.length;i++) if (norm(lab(els[i])) === t) return els[i];
    for (var j=0;j<els.length;j++){ var l = norm(lab(els[j])); if (l && (l.indexOf(t) >= 0 || t.indexOf(l) >= 0)) return els[j]; }
    return null;
  };

  window.__fill = async function(str){
    var answers = JSON.parse(str);
    var list = items();
    for (var k=0;k<answers.length;k++){
      var a = answers[k], el = list[a.id];
      if (!el) continue;
      try {
        var radios = all(el, 'div[role="radio"]');
        var checks = all(el, 'div[role="checkbox"]');
        var listbox = el.querySelector('div[role="listbox"]');
        var text = el.querySelector('input[type="text"], textarea');
        if (radios.length) {
          var t = find(radios, String(a.answer)); if (t) t.click();
        } else if (checks.length) {
          var arr = Array.isArray(a.answer) ? a.answer : [a.answer];
          for (var m=0;m<arr.length;m++){
            var c = find(checks, String(arr[m]));
            if (c && c.getAttribute('aria-checked') !== 'true'){ c.click(); await sleep(80); }
          }
        } else if (listbox) {
          listbox.click(); await sleep(400);
          var o = find(all(el, 'div[role="option"]'), String(a.answer)); if (o) o.click();
          await sleep(300);
        } else if (text) {
          setText(text, String(a.answer));
        }
      } catch(e) {}
      await sleep(120);
    }
  };
})();
"""
    }
}
