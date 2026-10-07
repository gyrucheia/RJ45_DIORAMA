package com.example.sanlomobapp;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.sanlomobapp.data.FirebaseRepository;
import com.example.sanlomobapp.model.ChatMessage;
import com.example.sanlomobapp.model.DioramaReportedState;
import com.example.sanlomobapp.model.DioramaTelemetry;

import java.util.ArrayList;
import java.util.Locale;

public class GeminiFragment extends Fragment {

    private EditText etQuestion;
    private RecyclerView recyclerChat;
    private ChatAdapter chatAdapter;
    private LinearLayout layoutSuggestions;
    private View btnTtsToggle;

    private final FirebaseRepository repository = FirebaseRepository.getInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextToSpeech textToSpeech;
    private boolean isTtsEnabled = true;

    private ActivityResultLauncher<Intent> speechResultLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Register Activity Result Launcher for Speech Recognition (Speech-to-Text)
        speechResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        ArrayList<String> matches = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                        if (matches != null && !matches.isEmpty()) {
                            String spokenText = matches.get(0);
                            askQuestion(spokenText);
                        }
                    }
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_gemini, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        etQuestion        = view.findViewById(R.id.et_ai_question);
        recyclerChat      = view.findViewById(R.id.recycler_chat);
        layoutSuggestions = view.findViewById(R.id.layout_suggestions);
        btnTtsToggle      = view.findViewById(R.id.btn_tts_toggle);

        chatAdapter = new ChatAdapter();
        if (recyclerChat != null) {
            recyclerChat.setLayoutManager(new LinearLayoutManager(requireContext()));
            recyclerChat.setAdapter(chatAdapter);
            recyclerChat.setVisibility(View.VISIBLE);
        }

        // Initialize Android Text-To-Speech for Audio Narration
        initTextToSpeech();

        // Initial welcome message from SanLo AI
        String welcome = "Mabuhay! I am your SanLo Exhibit AI Companion & Voice Guide. Ask me anything about the Colgante River, the tram system, active sensors, or live environmental conditions!";
        chatAdapter.addMessage(new ChatMessage(welcome, false));

        // Speak the initial greeting softly
        handler.postDelayed(() -> speakNarration(welcome), 600);

        // Header Navigation: Back Button
        view.findViewById(R.id.btn_back_guide).setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).goHome();
            }
        });

        // Header TTS Toggle (SL Avatar pill)
        if (btnTtsToggle != null) {
            btnTtsToggle.setOnClickListener(v -> {
                isTtsEnabled = !isTtsEnabled;
                if (!isTtsEnabled && textToSpeech != null) {
                    textToSpeech.stop();
                }
                btnTtsToggle.setAlpha(isTtsEnabled ? 1.0f : 0.4f);
                Toast.makeText(requireContext(), isTtsEnabled ? "AI Voice Guide: ON" : "AI Voice Guide: MUTED", Toast.LENGTH_SHORT).show();
            });
        }

        // Send Button
        View btnAskAi = view.findViewById(R.id.btn_ask_ai);
        if (btnAskAi != null) {
            btnAskAi.setOnClickListener(v -> sendUserQuestion());
        }

        // Keyboard Action Send
        if (etQuestion != null) {
            etQuestion.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendUserQuestion();
                    return true;
                }
                return false;
            });
        }

        // Suggestion Chips
        LinearLayout s1 = view.findViewById(R.id.suggestion_1);
        LinearLayout s2 = view.findViewById(R.id.suggestion_2);
        LinearLayout s3 = view.findViewById(R.id.suggestion_3);
        LinearLayout s4 = view.findViewById(R.id.suggestion_4);

        if (s1 != null) s1.setOnClickListener(v -> askQuestion("What is the Colgante River?"));
        if (s2 != null) s2.setOnClickListener(v -> askQuestion("How does the tram work?"));
        if (s3 != null) s3.setOnClickListener(v -> askQuestion("What sensors are being used?"));
        if (s4 != null) s4.setOnClickListener(v -> askQuestion("Tell me about the river level."));

        // Voice Input Mic Buttons
        View btnMic = view.findViewById(R.id.btn_mic);
        if (btnMic != null) {
            btnMic.setOnClickListener(v -> promptSpeechInput());
        }

        View btnChatMic = view.findViewById(R.id.btn_chat_mic);
        if (btnChatMic != null) {
            btnChatMic.setOnClickListener(v -> promptSpeechInput());
        }

        View btnChatMicContainer = view.findViewById(R.id.btn_chat_mic_container);
        if (btnChatMicContainer != null) {
            btnChatMicContainer.setOnClickListener(v -> promptSpeechInput());
        }

        // Voice Assistant Card
        View voiceCard = view.findViewById(R.id.layout_voice_card);
        if (voiceCard != null) {
            voiceCard.setOnClickListener(v -> promptSpeechInput());
        }
    }

    private void initTextToSpeech() {
        textToSpeech = new TextToSpeech(requireContext(), status -> {
            if (status == TextToSpeech.SUCCESS && textToSpeech != null) {
                int res = textToSpeech.setLanguage(Locale.US);
                if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech.setSpeechRate(0.95f);
                    textToSpeech.setPitch(1.02f);
                }
            }
        });
    }

    private void promptSpeechInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask SanLo AI Guide anything about the diorama...");
        try {
            speechResultLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), "Speech recognition is not available on this device", Toast.LENGTH_SHORT).show();
        }
    }

    private void speakNarration(String text) {
        if (textToSpeech != null && isTtsEnabled) {
            // Remove markdown bullets and hex colors for natural voice output
            String speechClean = text.replaceAll("[*•]", " ")
                                     .replaceAll("#[0-9a-fA-F]{6}", "")
                                     .replaceAll("\\s+", " ")
                                     .trim();
            textToSpeech.speak(speechClean, TextToSpeech.QUEUE_FLUSH, null, "sanlo_ai_tts");
        }
    }

    private void askQuestion(String question) {
        if (etQuestion != null) {
            etQuestion.setText(question);
            sendUserQuestion();
        }
    }

    private void sendUserQuestion() {
        if (etQuestion == null) return;
        String q = etQuestion.getText().toString().trim();
        if (q.isEmpty()) return;

        // Display user message in chat
        chatAdapter.addMessage(new ChatMessage(q, true));
        etQuestion.setText("");
        if (recyclerChat != null) {
            recyclerChat.smoothScrollToPosition(chatAdapter.getItemCount() - 1);
        }

        // Hide suggestions to give maximum screen space to conversation
        if (layoutSuggestions != null && chatAdapter.getItemCount() > 2) {
            layoutSuggestions.setVisibility(View.GONE);
        }

        // Stop any previous speech before generating new response
        if (textToSpeech != null && textToSpeech.isSpeaking()) {
            textToSpeech.stop();
        }

        // Synthesize response grounded in diorama reality
        handler.postDelayed(() -> {
            if (!isAdded()) return;
            String answer = generateDioramaAnswer(q);
            chatAdapter.addMessage(new ChatMessage(answer, false));
            if (recyclerChat != null) {
                recyclerChat.smoothScrollToPosition(chatAdapter.getItemCount() - 1);
            }
            speakNarration(answer);
        }, 500);
    }

    /**
     * Synthesizes verified diorama engineering facts with live real-time sensor telemetry.
     */
    private String generateDioramaAnswer(String prompt) {
        String lower = prompt.toLowerCase(Locale.US);

        DioramaTelemetry telemetry = repository.getCurrentTelemetry();
        DioramaReportedState state = repository.getCurrentReportedState();
        boolean isOnline = repository.getCurrentSystemStatus().isMasterActive();

        // Voice Assistant inquiry about tram operation
        if (lower.contains("ready") && (lower.contains("tram") || lower.contains("ready?"))) {
            return "Mabuhay! The San Lorenzo Ruiz Tramway is designed for scenic crossing demonstrations. " +
                   "You can start the tram transit demonstration anytime from the Simulation tab or use Manual Controls to operate it.";
        }

        if (lower.contains("colgante") || lower.contains("river") || lower.contains("history")) {
            if (lower.contains("level") || lower.contains("water") || lower.contains("flood") || lower.contains("drain")) {
                return formatWaterLevelAnswer(telemetry, isOnline);
            }
            return "The San Lorenzo Ruiz Colgante River diorama models an active river ecosystem and bridge crossing. " +
                   "It demonstrates municipal smart-city telemetry, flood management, and clean mobility using automated sensors and wireless controllers.";
        }

        if (lower.contains("tram") || lower.contains("transit") || lower.contains("motor") || lower.contains("dock")) {
            String currentTram = isOnline ? state.getTram_state() : "OFFLINE";
            return "The SanLo Tramline features autonomous gesture transit:\n\n" +
                   "1. Optical Gesture: An APDS-9960 sensor on MASTER detects hand gestures.\n" +
                   "2. Transit: An ESP-NOW broadcast commands SLAVE1 to drive the DC gear motor.\n" +
                   "3. Station Docking: An A3144 Hall-effect sensor detects the station magnet and halts the tram.\n\n" +
                   "Current Hardware State: " + currentTram + (state.isHall_detected() ? " (Docked at Station)" : "");
        }

        if (lower.contains("sensor") || lower.contains("hardware") || lower.contains("part") || lower.contains("component")) {
            return "The RJ45 Diorama operates 7 distinct sensor subsystems:\n\n" +
                   "• DHT22: Microclimate temperature & humidity\n" +
                   "• Resistive Water Probe: River basin water depth %\n" +
                   "• Flexible Biomechanical Sensor: Airflow resistance changes\n" +
                   "• APDS-9960: Hand gesture detection\n" +
                   "• A3144 Hall-Effect: Magnetic tram station docking\n" +
                   "• TCS34725: Optical color matching for river LEDs\n" +
                   "• HC-SR501 PIR: Ambient visitor motion in the exhibit gallery.";
        }

        if (lower.contains("water") || lower.contains("level") || lower.contains("flood") || lower.contains("pump") || lower.contains("drain")) {
            return formatWaterLevelAnswer(telemetry, isOnline);
        }

        if (lower.contains("wind") || lower.contains("fan") || lower.contains("breeze") || lower.contains("flex")) {
            String fanStatus = isOnline && state.isFan_state() ? "RUNNING" : "IDLE";
            String flexRead = (telemetry.getFlex_raw() != null) ? String.valueOf(telemetry.getFlex_raw()) : "--";
            return "The Valley Wind system demonstrates aerodynamic fluid dynamics:\n\n" +
                   "A 5V DC blower fan induces airflow through the valley channel. A flexible biomechanical sensor bends under the breeze, altering electrical resistance.\n\n" +
                   "• Fan Relay: " + fanStatus + "\n" +
                   "• Live Flex Sensor Reading: " + flexRead;
        }

        if (lower.contains("light") || lower.contains("street") || lower.contains("rgb") || lower.contains("color")) {
            String lightMode = isOnline ? state.getStreet_lights_mode() : "RTC";
            boolean lightsOn = isOnline && state.isStreet_lights_on();
            return "Smart lighting in the diorama includes:\n\n" +
                   "• Street Lights: Governed by an astronomical DS3231 RTC clock (Active from 18:00 to 06:00, or via Demonstration Time).\n" +
                   "• River RGB Lighting: Supports TCS34725 optical color matching or manual operator palette selection.\n\n" +
                   "• Clock Mode: " + lightMode + "\n" +
                   "• Street Lights: " + (lightsOn ? "ON" : "OFF");
        }

        if (lower.contains("temp") || lower.contains("humidity") || lower.contains("climate") || lower.contains("weather")) {
            if (isOnline && telemetry.hasData()) {
                String t = telemetry.getTemperature() != null ? String.format(Locale.US, "%.1f°C", telemetry.getTemperature()) : "--";
                String h = telemetry.getHumidity() != null ? String.format(Locale.US, "%.1f%%", telemetry.getHumidity()) : "--";
                return "Current diorama microclimate readings:\n\n• Temperature: " + t + "\n• Relative Humidity: " + h + "\nReported live by the DHT22 digital environmental sensor.";
            } else {
                return "The diorama monitors environmental conditions using a calibrated DHT22 digital sensor. Currently awaiting fresh telemetry stream.";
            }
        }

        return "I can explain the diorama's environmental systems! Try asking about:\n\n" +
               "• 'How does the tram work?'\n" +
               "• 'What is the river water level?'\n" +
               "• 'What sensors are in the diorama?'\n" +
               "• 'How does the flood warning work?'\n" +
               "• 'Tell me about the wind and fan simulation.'";
    }

    private String formatWaterLevelAnswer(DioramaTelemetry telemetry, boolean isOnline) {
        if (!isOnline || telemetry.getWater_level_pct() == null) {
            return "I don't currently have a water-level reading from the diorama. When the diorama is connected, live water level percentage and flood safety status will appear here.";
        }

        int pct = telemetry.getWater_level_pct();
        String status = telemetry.getWater_status() != null ? telemetry.getWater_status() : "NORMAL";

        return "River Water Level & Automated Flood Protection:\n\n" +
               "• Live River Reading: " + pct + "% (" + status + ")\n\n" +
               "• Normal Zone: Drainage pump remains idle to prevent dry-running, with the warning signal at rest.\n" +
               "• Warning Level: When the river reaches the configured warning level, the diorama raises its flood-warning signal and activates the drainage pump to demonstrate flood mitigation.";
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
    }
}
