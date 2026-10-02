package com.example.sanlomobapp;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class GeminiFragment extends Fragment {

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

        EditText etQuestion = view.findViewById(R.id.et_ai_question);

        view.findViewById(R.id.btn_back_guide).setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).goHome();
            }
        });

        view.findViewById(R.id.btn_ask_ai).setOnClickListener(v -> {
            String q = etQuestion.getText().toString().trim();
            if (!q.isEmpty()) {
                Toast.makeText(requireContext(), "Ask SanLo AI — coming soon", Toast.LENGTH_SHORT).show();
                etQuestion.setText("");
            }
        });

        LinearLayout s1 = view.findViewById(R.id.suggestion_1);
        LinearLayout s2 = view.findViewById(R.id.suggestion_2);
        LinearLayout s3 = view.findViewById(R.id.suggestion_3);
        LinearLayout s4 = view.findViewById(R.id.suggestion_4);

        if (s1 != null) s1.setOnClickListener(v -> fillSuggestion(etQuestion, "What is the Colgante River?"));
        if (s2 != null) s2.setOnClickListener(v -> fillSuggestion(etQuestion, "How does the tram work?"));
        if (s3 != null) s3.setOnClickListener(v -> fillSuggestion(etQuestion, "What sensors are being used?"));
        if (s4 != null) s4.setOnClickListener(v -> fillSuggestion(etQuestion, "Tell me about the river level."));

        view.findViewById(R.id.btn_mic).setOnClickListener(v ->
                Toast.makeText(requireContext(), "Voice input — coming soon", Toast.LENGTH_SHORT).show());
    }

    private void fillSuggestion(EditText field, String text) {
        field.setText(text);
        field.setSelection(text.length());
    }
}
