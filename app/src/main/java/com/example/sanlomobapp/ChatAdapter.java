package com.example.sanlomobapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.sanlomobapp.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ChatViewHolder> {

    private final List<ChatMessage> messages = new ArrayList<>();

    public void addMessage(ChatMessage message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    public void setMessages(List<ChatMessage> newMessages) {
        messages.clear();
        messages.addAll(newMessages);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_message, parent, false);
        return new ChatViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        ChatMessage msg = messages.get(position);
        if (msg.isUser()) {
            holder.layoutUserBubble.setVisibility(View.VISIBLE);
            holder.layoutGeminiBubble.setVisibility(View.GONE);
            holder.tvUserMsg.setText(msg.getText());
        } else {
            holder.layoutUserBubble.setVisibility(View.GONE);
            holder.layoutGeminiBubble.setVisibility(View.VISIBLE);
            holder.tvGeminiMsg.setText(msg.getText());
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class ChatViewHolder extends RecyclerView.ViewHolder {
        LinearLayout layoutGeminiBubble, layoutUserBubble;
        TextView tvGeminiMsg, tvUserMsg;

        ChatViewHolder(@NonNull View itemView) {
            super(itemView);
            layoutGeminiBubble = itemView.findViewById(R.id.layout_gemini_bubble);
            layoutUserBubble   = itemView.findViewById(R.id.layout_user_bubble);
            tvGeminiMsg        = itemView.findViewById(R.id.tv_gemini_msg);
            tvUserMsg          = itemView.findViewById(R.id.tv_user_msg);
        }
    }
}
