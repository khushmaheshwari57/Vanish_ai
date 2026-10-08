package com.vanish.ai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class VanishService : Service() {

    companion object {
        @Volatile
        var running = false
        private const val CHANNEL = "vanish_channel"
        private const val NOTIF_ID = 1
        private const val MODEL = "claude-sonnet-5-5"
        private const val SYSTEM_PROMPT =
            "You are Vanish, a voice assistant on the user's Android phone. " +
            "The user talks to you by voice and hears your reply spoken aloud. Rules: " +
            "1) Reply in the SAME language the user spoke: Hindi gets Hindi, English gets English, " +
            "Hinglish gets Hinglish, any other language gets that language. " +
            "2) Hindi and Hinglish replies must be written in Devanagari script (English words may stay in Latin) and lang must be hi-IN. " +
            "English replies use lang en-IN. Other languages use a BCP-47 tag like ta-IN or bn-IN. " +
            "3) Keep it short and spoken-style: 1 to 4 sentences, no markdown, no lists, no emojis, no special symbols. " +
            "4) Tone: confident, warm, with a little friendly bhai swagger when the user speaks Hindi or Hinglish. " +
            "5) Help with studies: explain simply step by step with examples, quiz the user when asked, say maths in spoken form. " +
            "6) Be honest. If unsure, say so. The speech recognizer may mishear, so guess sensibly. " +
            "Output ONLY a JSON object like {\"lang\":\"hi-IN\",\"text\":\"your reply\"} and nothing else."
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private lateinit var recIntent: Intent

    private var ttsReady = false
    private var speaking = false
    private var awaiting = false
    private var pendingCall = false
    private var thinking = false
    private var initialized = false

    private var voices: List<Voice> = emptyList()
    private var voiceIndex = 0

    private val history = ArrayList<Pair<String, String>>()

    private val wakeWords = listOf(
        "hey vanish", "hi vanish", "hey वैनिश", "vanish", "वैनिश", "वनिश", "वानिश",
        "वेनिश", "वैनीश", "वनीश", "वानीश", "banish", "vanesh", "venish", "वैनिष", "वनिष"
    )

    private val stopWords = setOf(
        "open", "kholo", "khol", "karo", "kar", "kro", "please", "app", "start", "launch",
        "call", "dial", "phone", "ko", "ka", "ki", "ke", "laga", "lagao", "lagana", "do", "de",
        "dena", "mujhe", "mera", "meri", "ek", "baar", "pe", "par", "me", "mein",
        "खोलो", "खोल", "करो", "कर", "कॉल", "काल", "फोन", "फ़ोन", "लगाओ", "लगा", "लगाना",
        "को", "चालू", "चलाओ", "ऐप", "ऐप्प", "एक", "बार", "मुझे", "पर", "दो", "दे",
        "का", "की", "के", "में", "प्लीज", "से", "मेरा", "मेरी"
    )

    private val callWords = setOf(
        "call", "dial", "laga", "lagao", "lagana",
        "कॉल", "काल", "लगाओ", "लगा", "लगाना"
    )

    private val aliases: List<Pair<List<String>, String>> = listOf(
        listOf("free fire max", "फ्री फायर मैक्स", "फ्री फ़ायर मैक्स", "फ्रीफायर मैक्स", "फ्री फायर मैक") to "com.dts.freefiremax",
        listOf("free fire", "फ्री फायर", "फ्री फ़ायर", "फ्रीफायर") to "com.dts.freefireth",
        listOf("bgmi", "बीजीएमआई", "बैटलग्राउंड") to "com.pubg.imobile",
        listOf("telegram", "टेलीग्राम") to "org.telegram.messenger",
        listOf("spotify", "स्पॉटिफाई") to "com.spotify.music",
        listOf("snapchat", "स्नैपचैट") to "com.snapchat.android",
        listOf("gmail", "जीमेल") to "com.google.android.gm",
        listOf("maps", "मैप्स", "गूगल मैप") to "com.google.android.apps.maps",
        listOf("phonepe", "फोनपे", "फोन पे") to "com.phonepe.app",
        listOf("paytm", "पेटीएम") to "net.one97.paytm",
        listOf("youtube", "यूट्यूब", "यू ट्यूब") to "com.google.android.youtube",
        listOf("chrome", "क्रोम") to "com.android.chrome",
        listOf("instagram", "इंस्टाग्राम", "इंस्टा") to "com.instagram.android",
        listOf("whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप") to "com.whatsapp"
    )

    private val devMap: Map<Char, Char> = mapOf(
        'क' to 'k', 'ख' to 'k', 'ग' to 'g', 'घ' to 'g', 'च' to 'k', 'छ' to 'k',
        'ज' to 'j', 'झ' to 'j', 'ट' to 't',' ठ' to 't', 'ड' to 'd', 'ढ' to 'd',
        'ण' to 'n', 'त' to 't', 'थ' to 't', 'द' to 'd', 'ध' to 'd', 'न' to 'n',
        'प' to 'p', 'फ' to 'f', 'ब' to 'b', 'भ' to 'b', 'म' to 'm', 'र' to 'r',
        'ल' to 'l', 'व' to 'v', 'श' to 's', 'ष' to 's', 'स' to 's',
        'ं' to 'n', 'ँ' to 'n',
        '\u0958' to 'k', '\u0959' to 'k', '\u095A' to 'g', '\u095B' to 'j',
        '\u095C' to 'r', '\u095D' to 'r', '\u095E' to 'f'
    )

    private val listenRunnable = Runnable {
        if (!speaking) {
            try {
                recognizer?.cancel()
                recognizer?.startListening(recIntent)
            } catch (_: Exception) {
            }
        }
    }

    private val awaitTimeout = Runnable {
        awaiting = false
        pendingCall = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        running = true
        if (!initialized) {
            initialized = true
            setupTts()
            setupRecognizer()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Vanish", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        val n = builder
            .setContentTitle("Vanish chalu hai")
            .setContentText("Bolo: Hey Vanish")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------- Name matching (Hindi <-> English) ----------
    private fun skeleton(s: String): String {
        var t = s.lowercase()
        t = t.replace("ph", "f").replace("ch", "k").replace("sh", "s").replace("th", "t")
            .replace("dh", "d").replace("kh", "k").replace("gh", "g").replace("bh", "b")
            .replace("x", "ks")
        val out = StringBuilder()
        for (ch in t) {
            val m: Char? = when {
                devMap.containsKey(ch) -> devMap[ch]
                ch in 'a'..'z' -> when (ch) {
                    'a', 'e', 'i', 'o', 'u', 'y', 'h' -> null
                    'c', 'q' -> 'k'
                    'w' -> 'v'
                    'z' -> 'j'
                    else -> ch
                }
                ch in '0'..'9' -> ch
                else -> null
            }
            if (m != null && (out.isEmpty() || out.last() != m)) out.append(m)
        }
        return out.toString()
    }

    private fun tokens(c: String) = c.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun nameFrom(c: String): String =
        tokens(c).filter { it !in stopWords }.joinToString(" ")

    private fun isCall(c: String): Boolean {
        val tk = tokens(c)
        return tk.size <= 6 && tk.any { it in callWords }
    }
        // ---------- TTS ----------
    private fun setupTts() {
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val l = tts?.setLanguage(Locale("hi", "IN"))
                if (l == TextToSpeech.LANG_MISSING_DATA || l == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale("en", "IN"))
                }
                loadVoices()
                applyVoice(0)
                tts?.setPitch(0.5f)
                tts?.setSpeechRate(0.78f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        handler.post { speaking = false; scheduleListen(300) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        handler.post { speaking = false; scheduleListen(300) }
                    }
                })
                ttsReady = true
                speak("वैनिश चालू है भाई। बस बोल, हे वैनिश।")
            }
        }
    }

    private fun loadVoices() {
        val all = tts?.voices?.toList() ?: emptyList()
        voices = all
            .filter { it.locale.language == "hi" || it.locale.country == "IN" }
            .sortedWith(
                compareByDescending<Voice> { it.locale.language == "hi" }
                    .thenByDescending { isMaleVoice(it) }
                    .thenBy { it.isNetworkConnectionRequired }
                    .thenBy { it.name }
            )
    }

    private fun isMaleVoice(v: Voice): Boolean {
        val n = v.name.lowercase()
        return n.contains("male") && !n.contains("female") || n.contains("-hid-") || n.contains("-hie-")
    }

    private fun applyVoice(index: Int) {
        if (voices.isEmpty()) return
        voiceIndex = ((index % voices.size) + voices.size) % voices.size
        tts?.voice = voices[voiceIndex]
    }

    private fun nextVoice() {
        if (voices.isEmpty()) {
            speak("Doosri voice available nahi hai.")
            return
        }
        applyVoice(voiceIndex + 1)
        speak("यह मेरी नई आवाज़ है।")
    }

    private fun applyLang(lang: String?) {
        val t = tts ?: return
        if (lang == null || lang.startsWith("hi", ignoreCase = true)) {
            t.setLanguage(Locale("hi", "IN"))
            if (voices.isNotEmpty()) t.voice = voices[voiceIndex]
        } else {
            val r = t.setLanguage(Locale.forLanguageTag(lang))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                t.setLanguage(Locale("hi", "IN"))
                if (voices.isNotEmpty()) t.voice = voices[voiceIndex]
            }
        }
    }

    private fun speak(text: String, lang: String? = null) {
        if (!ttsReady) return
        speaking = true
        handler.removeCallbacks(listenRunnable)
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        applyLang(lang)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "VANISH")
        handler.postDelayed({
            if (speaking) {
                speaking = false
                scheduleListen(0)
            }
        }, 40000)
    }
    // ---------- Always-on listening ----------
    private fun scheduleListen(delay: Long) {
        handler.removeCallbacks(listenRunnable)
        handler.postDelayed(listenRunnable, delay)
    }

    private fun setupRecognizer() {
        recIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stopSelf()
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleListen(1500)
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> scheduleListen(3000)
                    else -> scheduleListen(300)
                }
            }

            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.map { it.lowercase() } ?: emptyList()
                if (list.isNotEmpty() && !thinking) {
                    val text = if (awaiting) list[0]
                    else list.firstOrNull { afterWake(it) != null } ?: list[0]
                    onHeard(text)
                }
                scheduleListen(300)
            }
        })
        scheduleListen(500)
    }

    private fun afterWake(t: String): String? {
        for (w in wakeWords) {
            val i = t.indexOf(w)
            if (i >= 0) return t.substring(i + w.length).trim()
        }
        return null
    }

    private fun onHeard(t: String) {
        if (awaiting) {
            awaiting = false
            handler.removeCallbacks(awaitTimeout)
            val c = afterWake(t)?.takeIf { it.isNotEmpty() } ?: t
            if (pendingCall) {
                pendingCall = false
                callByName(c)
            } else {
                handleCommand(c)
            }
            return
        }
        val rest = afterWake(t) ?: return
        if (rest.length >= 2) {
            handleCommand(rest)
        } else {
            awaiting = true
            handler.removeCallbacks(awaitTimeout)
            handler.postDelayed(awaitTimeout, 8000)
            speak("हाँ भाई, बोल।")
        }
    }     // ---------- Commands ----------
    private fun has(command: String, vararg keys: String) = keys.any { command.contains(it) }

    private fun aliasPackage(command: String): String? =
        aliases.firstOrNull { (names, _) -> names.any { command.contains(it) } }?.second

    private fun handleCommand(command: String) {
        val aliasPkg = if (tokens(command).size <= 6) aliasPackage(command) else null
        when {
            has(command, "change voice", "next voice", "awaaz badlo", "awaz badlo", "आवाज़ बदलो", "आवाज बदलो", "वॉइस बदलो") ->
                nextVoice()

            has(command, "band karo", "बंद करो", "stop vanish", "बंद हो जा") -> {
                speak("ठीक है भाई, फिर मिलते हैं।")
                handler.postDelayed({ stopSelf() }, 3500)
            }

            aliasPkg != null -> openApp(aliasPkg)

            isCall(command) -> callFlow(command)

            else -> fallback(command)
        }
    }

    // Short name -> app or contact. Anything else -> AI.
    private fun fallback(command: String) {
        val name = nameFrom(command)
        val sk = skeleton(name)
        val short = tokens(name).size in 1..3 && sk.length in 3..14
        if (short) {
            if (openByLabel(command)) return
            val c = findContact(name)
            if (c != null) {
                dial(c.first, c.second)
                return
            }
        }
        askAi(command)
    }

    private fun openByLabel(command: String): Boolean {
        val s = skeleton(nameFrom(command))
        if (s.length < 3) return false

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps: List<Pair<String, String>> = packageManager.queryIntentActivities(launcher, 0)
            .map { skeleton(it.loadLabel(packageManager).toString()) to it.activityInfo.packageName }
            .filter { it.first.length >= 3 }

        val match = apps.filter { s.contains(it.first) }.maxByOrNull { it.first.length }
            ?: apps.filter { s.length >= 4 && it.first.contains(s) }.minByOrNull { it.first.length }

        if (match != null) {
            openApp(match.second)
            return true
        }
        return false
    }

    private fun openApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            speak("लो, खोल दिया।")
            try {
                startActivity(launchIntent)
            } catch (_: Exception) {
            }
        } else {
            speak("यह ऐप इंस्टॉल नहीं है।")
        }
    }

    // ---------- AI ----------
    private fun askAi(userText: String) {
        val key = getSharedPreferences("vanish", MODE_PRIVATE).getString("api_key", "")?.trim() ?: ""
        if (key.isEmpty()) {
            speak("पहले ऐप में API key डालो भाई।")
            return
        }

        history.add("user" to userText)
        while (history.size > 12) history.removeAt(0)
        while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)

        val msgs = JSONArray()
        for ((r, c) in history) msgs.put(JSONObject().put("role", r).put("content", c))

        thinking = true
        Thread {
            var out: String? = null
            var err = 0
            try {
                val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 45000
                conn.setRequestProperty("content-type", "application/json")
                conn.setRequestProperty("x-api-key", key)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                conn.doOutput = true
                val body = JSONObject()
                    .put("model", MODEL)
                    .put("max_tokens", 500)
                    .put("system", SYSTEM_PROMPT)
                    .put("messages", msgs)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code in 200..299) {
                    val arr = JSONObject(resp).getJSONArray("content")
                    val sb = StringBuilder()
                    for (i in 0 until arr.length()) {
                        val b = arr.getJSONObject(i)
                        if (b.optString("type") == "text") sb.append(b.optString("text"))
                    }
                    out = sb.toString()
                } else {
                    err = code
                }
            } catch (e: Exception) {
                err = -1
            }
            handler.post { onAiResult(out, err) }
        }.start()
    }

    private fun onAiResult(out: String?, err: Int) {
        thinking = false
        if (out == null || out.isBlank()) {
            if (history.isNotEmpty() && history.last().first == "user") history.removeAt(history.size - 1)
            when (err) {
                401, 403 -> speak("API key गलत है भाई। ऐप में दोबारा डालो।")
                400, 402, 429 -> speak("AI के क्रेडिट या लिमिट की दिक्कत है भाई।")
                else -> speak("नेट या सर्वर की दिक्कत है, थोड़ी देर बाद फिर बोलो।")
            }
            return
        }

        var lang = "hi-IN"
        var text = out.trim()
        try {
            val s = out.indexOf('{')
            val e = out.lastIndexOf('}')
            if (s >= 0 && e > s) {
                val j = JSONObject(out.substring(s, e + 1))
                text = j.optString("text", text)
                lang = j.optString("lang", lang)
            }
        } catch (_: Exception) {
        }

        history.add("assistant" to text)
        // keep conversation open: next sentence works without saying "Hey Vanish"
        awaiting = true
        pendingCall = false
        handler.removeCallbacks(awaitTimeout)
        handler.postDelayed(awaitTimeout, 30000)
        speak(text, lang)
    }
      // ---------- Calling ----------
    private fun callFlow(command: String) {
        val name = nameFrom(command)
        if (name.isEmpty()) {
            pendingCall = true
            awaiting = true
            handler.removeCallbacks(awaitTimeout)
            handler.postDelayed(awaitTimeout, 8000)
            speak("किसको कॉल करूँ?")
        } else {
            callByName(name)
        }
    }

    private fun callByName(spoken: String) {
        val m = findContact(spoken)
        if (m == null) {
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                speak("कॉन्टैक्ट्स की permission दो भाई।")
            } else {
                speak("यह नाम मेरे कॉन्टैक्ट्स में नहीं मिला।")
            }
            return
        }
        dial(m.first, m.second)
    }

    private fun findContact(spoken: String): Pair<String, String>? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val key = skeleton(spoken)
        if (key.isEmpty()) return null

        var best: Pair<String, String>? = null
        var bestScore = 0
        val cur = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        ) ?: return null

        cur.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: continue
                val num = it.getString(1) ?: continue
                val nk = skeleton(name)
                if (nk.isEmpty()) continue
                val score = when {
                    nk == key -> 3
                    nk.length >= 2 && key.length >= 2 && (nk.startsWith(key) || key.startsWith(nk)) -> 2
                    nk.length >= 3 && key.length >= 3 && (nk.contains(key) || key.contains(nk)) -> 1
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = name to num
                }
            }
        }
        return if (bestScore >= 1) best else null
    }

    private fun dial(name: String, number: String) {
        speak("$name को कॉल लगा रहा हूँ।")
        handler.postDelayed({
            val canCall = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
            val i = Intent(
                if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                Uri.parse("tel:" + Uri.encode(number))
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(i)
            } catch (_: Exception) {
            }
        }, 1500)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
