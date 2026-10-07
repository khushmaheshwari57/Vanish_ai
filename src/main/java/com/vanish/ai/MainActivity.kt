package com.vanish.ai

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var status: TextView
    private var ttsReady = false

    private var voices: List<Voice> = emptyList()
    private var voiceIndex = 0

    companion object {
        private const val REQUEST_SPEECH = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        val listenButton = findViewById<Button>(R.id.listenButton)

        tts = TextToSpeech(this, this)
        listenButton.setOnClickListener { listen() }
    }

    // ---------- TTS (Hinglish = Hindi voice, mixed Hindi + English words) ----------
    override fun onInit(result: Int) {
        if (result != TextToSpeech.SUCCESS) {
            Toast.makeText(this, "Text-to-speech start nahi hua", Toast.LENGTH_SHORT).show()
            return
        }

        val lang = tts.setLanguage(Locale("hi", "IN"))
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            Toast.makeText(this, "Hindi voice install karo (Settings > Text-to-speech)", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            } catch (_: Exception) {}
            tts.setLanguage(Locale("en", "IN"))
        }

        loadVoices()
        applyVoice(0)
        tts.setPitch(0.5f)
        tts.setSpeechRate(0.78f)
        ttsReady = true
        speak("वैनिश हाज़िर है, भाई। बोल, क्या करना है?")
    }

    private fun loadVoices() {
        val all = tts.voices?.toList() ?: emptyList()
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
        tts.voice = voices[voiceIndex]
        status.text = "Voice ${voiceIndex + 1}/${voices.size}: ${voices[voiceIndex].name}"
    }

    private fun nextVoice() {
        if (voices.isEmpty()) {
            speak("Doosri voice available nahi hai.")
            return
        }
        applyVoice(voiceIndex + 1)
        speak("यह मेरी नई आवाज़ है।")
    }

    // ---------- Speech recognition ----------
    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Boliye...")
        }
        try {
            startActivityForResult(intent, REQUEST_SPEECH)
        } catch (e: ActivityNotFoundException) {
            speak("Speech recognition is device par available nahi hai.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SPEECH && resultCode == RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val command = results?.firstOrNull()?.lowercase() ?: return
            status.text = command
            handleCommand(command)
        }
    }

    private fun has(command: String, vararg keys: String) = keys.any { command.contains(it) }

    private fun handleCommand(command: String) {
        when {
            has(command, "hello", "hey vanish", "hi vanish", "हेलो", "हैलो", "नमस्ते", "namaste") ->
                speak("क्या हाल है भाई? वैनिश ऑनलाइन है।")

            has(command, "change voice", "next voice", "awaaz badlo", "awaz badlo", "आवाज़ बदलो", "आवाज बदलो", "वॉइस बदलो") ->
                nextVoice()

            has(command, "youtube", "यूट्यूब", "यू ट्यूब") -> openApp("com.google.android.youtube")
            has(command, "chrome", "क्रोम") -> openApp("com.android.chrome")
            has(command, "instagram", "इंस्टाग्राम", "insta", "इंस्टा") -> openApp("com.instagram.android")
            has(command, "whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप") -> openApp("com.whatsapp")

            has(command, "morning", "subah", "सुबह", "गुड मॉर्निंग", "good morning") ->
                speak("सुबह हो गई भाई। उठ जा, आज का दिन तेरा है।")

            has(command, "skincare", "स्किनकेयर", "स्किन केयर") ->
                speak("मॉर्निंग स्किनकेयर: पहले क्लींज़र, फिर मॉइस्चराइज़र, और आखिर में सनस्क्रीन।")

            has(command, "study", "padhai", "स्टडी", "पढ़ाई", "पढाई") ->
                speak("स्टडी मोड ऑन। अब सिर्फ़ फोकस, कोई बहाना नहीं।")

            has(command, "trading", "ट्रेडिंग") ->
                speak("ट्रेडिंग लर्निंग मोड ऑन। मैं चार्ट और इंडिकेटर समझने में आपकी मदद करूँगा।")

            else -> speak("मैंने सुना, लेकिन यह कमांड अभी सेट नहीं है।")
        }
    }

    private fun openApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            speak("लो, खोल दिया।")
            startActivity(launchIntent)
        } else {
            speak("यह ऐप इंस्टॉल नहीं है।")
        }
    }

    private fun speak(text: String) {
        if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "VANISH")
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}
