package com.sanadoy.formsolver

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.util.Base64
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
            hint = "Model (default: gemini-flash-latest)"
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
        val model = (prefs.getString("model", "") ?: "").ifBlank { "gemini-flash-latest" }
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
            val all = JSONArray(json)
            thread {
                try {
                    val total = all.length()
                    var i = 0
                    while (i < total) {
                        val end = minOf(i + CHUNK, total)
                        val chunk = JSONArray()
                        for (j in i until end) chunk.put(all.getJSONObject(j))
                        setStatus("AI mengerjakan soal " + (i + 1) + "-" + end + " dari " + total + "...")
                        val answers = callGemini(chunk, key, model)
                        runOnUiThread {
                            web.evaluateJavascript("window.__fill(" + JSONObject.quote(answers) + ");", null)
                        }
                        i = end
                    }
                    setStatus("Selesai. Periksa jawaban sebelum kirim.")
                } catch (e: Exception) {
                    setStatus("Error: " + e.message)
                }
            }
        }
    }

    private fun downloadImage(url: String): JSONObject? {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (c.responseCode !in 200..299) return null
            val mime = (c.contentType ?: "").substringBefore(";").trim()
            if (!mime.startsWith("image/")) return null
            val bytes = c.inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > 6000000) return null
            return JSONObject().put(
                "inline_data",
                JSONObject().put("mime_type", mime).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun callGemini(chunk: JSONArray, key: String, model: String): String {
        val imageParts = ArrayList<Pair<String, JSONObject>>()
        val clean = JSONArray()
        for (k in 0 until chunk.length()) {
            val q = JSONObject(chunk.getJSONObject(k).toString())
            val id = q.getInt("id")
            val qi = q.optJSONArray("images")
            val oi = q.optJSONArray("optionImages")
            q.remove("images")
            q.remove("optionImages")
            if (qi != null) {
                for (n in 0 until qi.length()) {
                    if (imageParts.size >= MAX_IMG) break
                    val part = downloadImage(qi.getString(n)) ?: continue
                    imageParts.add(Pair("Gambar pada soal id $id (nomor ${n + 1}):", part))
                }
            }
            if (oi != null) {
                for (n in 0 until oi.length()) {
                    val u = oi.optString(n, "")
                    if (u.isEmpty() || imageParts.size >= MAX_IMG) continue
                    val part = downloadImage(u) ?: continue
                    imageParts.add(Pair("Gambar pilihan ke-${n + 1} pada soal id $id:", part))
                }
            }
            clean.put(q)
        }
        setStatus("Mengirim " + chunk.length() + " soal + " + imageParts.size + " gambar ke AI...")

        val parts = JSONArray()
        parts.put(
            JSONObject().put(
                "text",
                "Soal (JSON). Gambar terlampir setelah ini, tiap gambar didahului labelnya:\n" + clean.toString()
            )
        )
        for ((label, part) in imageParts) {
            parts.put(JSONObject().put("text", label))
            parts.put(part)
        }

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2)
            )
        val payload = body.toString().toByteArray(Charsets.UTF_8)

        var lastErr = ""
        for (attempt in 1..4) {
            val conn = URL(
                "https://generativelanguage.googleapis.com/v1beta/models/" + model.trim() + ":generateContent"
            ).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(payload) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }

            if (code in 200..299) {
                val cands = JSONObject(text).optJSONArray("candidates")
                if (cands == null || cands.length() == 0) throw Exception("AI tidak memberi jawaban (mungkin diblokir).")
                val ps = cands.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                val sb = StringBuilder()
                for (n in 0 until ps.length()) sb.append(ps.getJSONObject(n).optString("text", ""))
                val out = sb.toString()
                val s = out.indexOf('[')
                val e = out.lastIndexOf(']')
                if (s < 0 || e < 0) throw Exception("Balasan AI tidak valid")
                return out.substring(s, e + 1)
            }
            lastErr = "API " + code + ": " + text.take(150)
            if (code == 429 || code >= 500) {
                setStatus("Server sibuk, mencoba ulang ($attempt/4)...")
                Thread.sleep(4000L * attempt)
                continue
            }
            throw Exception(lastErr)
        }
        throw Exception(lastErr)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    companion object {
        const val CHUNK = 5
        const val MAX_IMG = 16

        const val SYSTEM = "Kamu guru yang teliti sekaligus pengisi formulir. Kamu menerima array JSON soal dengan field: " +
            "id, type (radio | checkbox | dropdown | text), question, options (untuk radio/checkbox/dropdown). " +
            "Gambar yang berkaitan dengan soal atau pilihan dilampirkan setelah JSON dan diberi label. " +
            "Baca gambar (tabel, grafik, diagram) dengan teliti sebelum menjawab. " +
            "Kerjakan tiap soal dengan hati-hati, hitung langkah demi langkah. " +
            "Balas HANYA dengan array JSON: [{\"id\": 0, \"reasoning\": \"langkah singkat\", \"answer\": ...}]. " +
            "Tulis reasoning SEBELUM answer. " +
            "Aturan answer: radio/dropdown: SATU string yang persis sama dengan salah satu options. " +
            "checkbox: array string, tiap item persis sama dengan salah satu options. " +
            "text: string jawaban singkat dan langsung. " +
            "Jika tidak yakin, tetap pilih jawaban paling mungkin benar."

        val JS_LIB = """
(function(){
  if (window.__extract) return;
  var norm = function(s){ return (s||'').replace(/\s+/g,' ').trim().toLowerCase(); };
  var lab = function(e){ return e.getAttribute('data-value') || e.getAttribute('data-answer-value') || e.getAttribute('aria-label') || ''; };
  var items = function(){ return Array.prototype.slice.call(document.querySelectorAll('div[role="listitem"]')); };
  var all = function(el, sel){ return Array.prototype.slice.call(el.querySelectorAll(sel)); };
  var sleep = function(ms){ return new Promise(function(r){ setTimeout(r, ms); }); };

  var optBox = function(r, el, sel){
    var c = r;
    while (c.parentElement && c.parentElement !== el && c.parentElement.querySelectorAll(sel).length === 1) c = c.parentElement;
    return c;
  };
  var imgOf = function(im){
    var w = im.naturalWidth || im.width || 0;
    if (w && w < 50) return '';
    var s = im.currentSrc || im.src || '';
    return /^https?:/.test(s) ? s : '';
  };
  var imgUrl = function(box){
    var im = box.querySelector('img');
    return im ? imgOf(im) : '';
  };
  var qImgs = function(el, boxes){
    var out = [];
    all(el, 'img').forEach(function(im){
      for (var i=0;i<boxes.length;i++) if (boxes[i].contains(im)) return;
      var s = imgOf(im);
      if (s && out.indexOf(s) < 0) out.push(s);
    });
    return out;
  };

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
      var group = radios.length ? radios : checks;
      var sel = radios.length ? 'div[role="radio"]' : 'div[role="checkbox"]';
      if (group.length) {
        var boxes = group.map(function(r){ return optBox(r, el, sel); });
        var labels = group.map(function(r, i){ return lab(r) || ('Pilihan ' + (i+1)); });
        var oimgs = boxes.map(function(b){ return imgUrl(b); });
        var o = {id:id, type: radios.length ? 'radio' : 'checkbox', question:question, options:labels, images:qImgs(el, boxes)};
        if (oimgs.some(function(u){ return u; })) o.optionImages = oimgs;
        out.push(o);
      } else if (listbox) {
        out.push({id:id, type:'dropdown', question:question,
          options: all(listbox, 'div[role="option"]').map(lab).filter(Boolean), images:qImgs(el, [])});
      } else if (text) {
        var identitas = question.length < 60 && /(e-?mail|nama|sekolah|kelas|absen|nisn?)/i.test(question);
        if (!identitas) out.push({id:id, type:'text', question:question, images:qImgs(el, [])});
      }
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
    var m = /^pilihan\s*(\d+)${'$'}/.exec(t);
    if (m && els[parseInt(m[1], 10) - 1]) return els[parseInt(m[1], 10) - 1];
    for (var i=0;i<els.length;i++) if (norm(lab(els[i])) === t) return els[i];
    for (var j=0;j<els.length;j++){ var l = norm(lab(els[j])); if (l && (l.indexOf(t) >= 0 || t.indexOf(l) >= 0)) return els[j]; }
    return null;
  };

  var runFill = async function(str){
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
  window.__fill = function(str){
    window.__q = (window.__q || Promise.resolve()).then(function(){ return runFill(str); });
  };
})();
"""
    }
}
