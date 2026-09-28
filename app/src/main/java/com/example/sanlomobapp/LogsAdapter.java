package com.example.sanlomobapp;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class LogsAdapter extends RecyclerView.Adapter<LogsAdapter.LogViewHolder> {

    private final Context context;
    private final List<LogEntry> entries;

    public LogsAdapter(Context context, List<LogEntry> entries) {
        this.context = context;
        this.entries = entries;
    }

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_log_entry, parent, false);
        return new LogViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        LogEntry entry = entries.get(position);

        holder.tvTimestamp.setText(entry.getTimestamp());
        holder.tvDescription.setText(entry.getDescription());
        holder.tvEventType.setText(entry.getEventType());

        switch (entry.getType()) {
            case LogEntry.TYPE_PIR:
                holder.accentBar.setBackgroundColor(context.getColor(R.color.celestial_blue));
                holder.ivIcon.setImageResource(R.drawable.ic_log_pir);
                holder.tvEventType.setBackgroundResource(R.drawable.bg_pill_blue);
                holder.tvEventType.setTextColor(context.getColor(R.color.white));
                break;
            case LogEntry.TYPE_ACTION:
                holder.accentBar.setBackgroundColor(context.getColor(R.color.sap_green));
                holder.ivIcon.setImageResource(R.drawable.ic_log_action);
                holder.tvEventType.setBackgroundResource(R.drawable.bg_pill_green);
                holder.tvEventType.setTextColor(context.getColor(R.color.white));
                break;
            case LogEntry.TYPE_ALERT:
                holder.accentBar.setBackgroundColor(context.getColor(R.color.alert_red));
                holder.ivIcon.setImageResource(R.drawable.ic_log_alert);
                holder.tvEventType.setBackgroundResource(R.drawable.bg_pill_red);
                holder.tvEventType.setTextColor(context.getColor(R.color.white));
                break;
            default:
                holder.accentBar.setBackgroundColor(context.getColor(R.color.nav_inactive));
                break;
        }
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    public void clearAll() {
        entries.clear();
        notifyDataSetChanged();
    }

    static class LogViewHolder extends RecyclerView.ViewHolder {
        View accentBar;
        ImageView ivIcon;
        TextView tvTimestamp;
        TextView tvDescription;
        TextView tvEventType;

        LogViewHolder(@NonNull View itemView) {
            super(itemView);
            accentBar = itemView.findViewById(R.id.view_accent_bar);
            ivIcon = itemView.findViewById(R.id.iv_log_icon);
            tvTimestamp = itemView.findViewById(R.id.tv_log_timestamp);
            tvDescription = itemView.findViewById(R.id.tv_log_description);
            tvEventType = itemView.findViewById(R.id.tv_log_event_type);
        }
    }
}
