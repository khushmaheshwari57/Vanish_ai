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
        'ज' to 'j', 'झ' to 'j', 'ट' to 't',
