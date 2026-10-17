package com.local.aivoicecontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import kotlin.concurrent.thread

class VoiceBrainService : AccessibilityService(), TextToSpeech.OnInitListener {
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val toneGen = ToneGenerator(AudioManager.STREAM_ACCESSIBILITY, 80)
    private var isListening = false
    private var alwaysWakeWordMode = false
    private var lastVolUpPressTime = 0L
    private val nodeMap = mutableMapOf<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        tts = TextToSpeech(this, this)
        mainHandler.post { initSpeechRecognizer() }
        speak("AI Voice Brain ready. Double press Volume Up to speak.")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.forLanguageTag("en-IN")
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastVolUpPressTime < 450) {
                mainHandler.post {
                    if (isListening) {
                        stopListening()
                        speak("Stopped")
                    } else {
                        beep()
                        startListening()
                    }
                }
                return true
            }
            lastVolUpPressTime = now
        }
        return super.onKeyEvent(event)
    }

    private fun initSpeechRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { isListening = true }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { isListening = false }
                override fun onError(error: Int) {
                    isListening = false
                    if (alwaysWakeWordMode) restartWakeWordLoop()
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.lowercase(Locale.getDefault()) ?: ""
                    if (spoken.isNotBlank()) handleSpokenText(spoken)
                    if (alwaysWakeWordMode) restartWakeWordLoop()
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun stopListening() {
        alwaysWakeWordMode = false
        speechRecognizer?.stopListening()
        isListening = false
    }

    private fun restartWakeWordLoop() {
        mainHandler.postDelayed({ if (alwaysWakeWordMode && !isListening) startListening() }, 600)
    }

    private fun handleSpokenText(rawText: String) {
        var cmd = rawText.trim()
        if (cmd.contains("wake word on") || cmd.contains("always listen")) {
            alwaysWakeWordMode = true
            speak("Wake word mode on. Say Jarvis before your command.")
            restartWakeWordLoop()
            return
        }
        if (cmd.contains("wake word off") || cmd.contains("stop listening")) {
            stopListening()
            speak("Wake word mode off.")
            return
        }
        if (alwaysWakeWordMode) {
            val triggers = listOf("jarvis", "hey bhai", "sun bhai", "phone")
            val matched = triggers.firstOrNull { cmd.startsWith(it) || cmd.contains(it) } ?: return
            cmd = cmd.substringAfter(matched).trim()
            if (cmd.isEmpty()) { beep(); return }
        }
        if (executeFastReflexCommand(cmd)) return
        val screenTree = dumpScreenNodes()
        askLocalLLM(cmd, screenTree)
    }

    private fun executeFastReflexCommand(cmd: String): Boolean {
        when {
            cmd in listOf("back", "go back", "peeche", "peeche jao", "piche") -> {
                performGlobalAction(GLOBAL_ACTION_BACK); return true
            }
            cmd in listOf("home", "go home", "home screen", "home chalo") -> {
                performGlobalAction(GLOBAL_ACTION_HOME); return true
            }
            cmd.contains("notification") || cmd.contains("notifications") -> {
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); return true
            }
            cmd.contains("recent") || cmd.contains("recent apps") -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS); return true
            }
            cmd.contains("scroll down") || cmd.contains("niche karo") || cmd.contains("neeche") -> {
                swipeScreen(scrollDown = true); return true
            }
            cmd.contains("scroll up") || cmd.contains("upar karo") || cmd.contains("oopar") -> {
                swipeScreen(scrollDown = false); return true
            }
            cmd.startsWith("type ") || cmd.startsWith("likho ") || cmd.startsWith("write ") -> {
                val textToType = cmd.replaceFirst(Regex("^(type|likho|write)\\s+"), "")
                if (typeIntoFocusedOrFirstInput(textToType)) {
                    speak("Typed $textToType")
                    return true
                }
            }
            cmd.startsWith("click ") || cmd.startsWith("open ") || cmd.startsWith("tap ") || cmd.startsWith("dabao ") -> {
                val target = cmd.replaceFirst(Regex("^(click|open|tap|dabao)\\s+"), "").trim()
                if (clickNodeByText(target)) {
                    beep()
                    return true
                }
            }
        }
        return false
    }

    private fun dumpScreenNodes(): String {
        nodeMap.clear()
        val root = rootInActiveWindow ?: return "Screen empty"
        val sb = StringBuilder()
        var idCounter = 1
        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val label = (node.text ?: node.contentDescription ?: "").toString().trim()
            val clickable = node.isClickable || node.parent?.isClickable == true
            val editable = node.isEditable
            if (label.isNotEmpty() || editable || clickable) {
                val id = idCounter++
                nodeMap[id] = node
                val role = when {
                    editable -> "InputBox"
                    clickable -> "Button"
                    else -> "Text"
                }
                if (label.isNotEmpty() || editable) {
                    sb.append("[$id] ($role) \"$label\"\n")
                }
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }
        traverse(root)
        return sb.toString().take(3500)
    }

    private fun askLocalLLM(userCommand: String, screenTree: String) {
        thread {
            try {
                val prompt = """
                    You control an Android phone for a user with a broken touchscreen.
                    User voice command: "$userCommand"
                    Current screen elements:
                    $screenTree

                    Reply ONLY with valid JSON using ONE of these actions:
                    {"action":"CLICK","id":1,"say":"Clicking button"}
                    {"action":"TYPE","id":2,"text":"hello","say":"Typing hello"}
                    {"action":"SCROLL_DOWN","say":"Scrolling down"}
                    {"action":"SCROLL_UP","say":"Scrolling up"}
                    {"action":"BACK","say":"Going back"}
                    {"action":"HOME","say":"Going home"}
                """.trimIndent()
                val url = URL("http://127.0.0.1:11434/api/generate")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 4000
                    readTimeout = 15000
                    doOutput = true
                }
                val payload = JSONObject().apply {
                    put("model", "qwen2.5:1.5b")
                    put("prompt", prompt)
                    put("stream", false)
                    put("format", "json")
                }
                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
                val responseStr = conn.inputStream.bufferedReader().readText()
                val llmReply = JSONObject(responseStr).optString("response", "{}")
                val actionJson = JSONObject(llmReply)
                mainHandler.post { executeLLMJsonAction(actionJson) }
            } catch (e: Exception) {
                mainHandler.post {
                    if (!clickNodeByText(userCommand)) {
                        speak("Could not find $userCommand on screen.")
                    }
                }
            }
        }
    }

    private fun executeLLMJsonAction(json: JSONObject) {
        val action = json.optString("action", "")
        val id = json.optInt("id", -1)
        val text = json.optString("text", "")
        val say = json.optString("say", "")
        if (say.isNotEmpty()) speak(say)
        when (action) {
            "CLICK" -> nodeMap[id]?.let { clickNodeOrParent(it) }
            "TYPE" -> {
                val targetNode = nodeMap[id]
                if (targetNode != null) setNodeText(targetNode, text)
                else typeIntoFocusedOrFirstInput(text)
            }
            "SCROLL_DOWN" -> swipeScreen(true)
            "SCROLL_UP" -> swipeScreen(false)
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun clickNodeByText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByText(query)
        for (node in matches) {
            if (clickNodeOrParent(node)) return true
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var curr: AccessibilityNodeInfo? = node
        var depth = 0
        while (curr != null && depth < 5) {
            if (curr.isClickable) {
                return curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            curr = curr.parent
            depth++
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
            return true
        }
        return false
    }

    private fun typeIntoFocusedOrFirstInput(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            return setNodeText(focused, text)
        }
        dumpScreenNodes()
        val firstEdit = nodeMap.values.firstOrNull { it.isEditable } ?: return false
        return setNodeText(firstEdit, text)
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun swipeScreen(scrollDown: Boolean) {
        val metrics = resources.displayMetrics
        val midX = metrics.widthPixels / 2f
        val startY = if (scrollDown) metrics.heightPixels * 0.75f else metrics.heightPixels * 0.25f
        val endY = if (scrollDown) metrics.heightPixels * 0.25f else metrics.heightPixels * 0.75f
        val path = Path().apply {
            moveTo(midX, startY)
            lineTo(midX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 350)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun tapCoordinates(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun speak(msg: String) {
        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "ai_reply")
    }

    private fun beep() {
        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
