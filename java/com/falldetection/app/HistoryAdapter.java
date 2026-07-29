package com.falldetection.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder> {

    private final List<HistoryItem> historyList;
    private final OnItemLongClickListener longClickListener; // TAMBAHAN: Pendengar sentuhan lama

    // TAMBAHAN: Interface untuk menjembatani Adapter dan Activity
    public interface OnItemLongClickListener {
        void onItemLongClick(HistoryItem item);
    }

    // TAMBAHAN: Memasukkan listener ke dalam constructor
    public HistoryAdapter(List<HistoryItem> historyList, OnItemLongClickListener longClickListener) {
        this.historyList = historyList;
        this.longClickListener = longClickListener;
    }

    @NonNull
    @Override
    public HistoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history, parent, false);
        return new HistoryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull HistoryViewHolder holder, int position) {
        HistoryItem item = historyList.get(position);

        boolean isYoloData = false;
        if (item.getImage_base64() != null && !item.getImage_base64().trim().isEmpty()) {
            isYoloData = true;
        } else if (item.getConfidence() != null && item.getConfidence().contains("%") || item.getConfidence().contains("Validasi")) {
            isYoloData = true;
        } else if (item.getSource() != null && item.getSource().toUpperCase().contains("TORSO")) {
            isYoloData = true;
        } else if (item.getSource() != null && item.getSource().toUpperCase().contains("CCTV")) {
            isYoloData = true;
        }

        if (isYoloData) {
            holder.yoloFullContainer.setVisibility(View.VISIBLE);
            holder.mpuFullContainer.setVisibility(View.GONE);

            holder.yoloTimeText.setText(item.getTime());
            holder.yoloConfidenceText.setText(item.getConfidence());

            if (holder.yoloAkurasiAiText != null) {
                String akurasiAi = item.getAkurasiAi();
                if (akurasiAi == null || akurasiAi.trim().isEmpty()) {
                    akurasiAi = "--";
                }
                holder.yoloAkurasiAiText.setText(akurasiAi);
            }

            if (holder.yoloKemiringanText != null) {
                String kemiringan = item.getKemiringan();
                if (kemiringan == null || kemiringan.trim().isEmpty()) {
                    kemiringan = "--°";
                }
                holder.yoloKemiringanText.setText(kemiringan);
            }

            if (item.getSource() != null && holder.yoloSourceText != null) {
                holder.yoloSourceText.setText(item.getSource());
            }

            if (holder.yoloNameText != null) {
                String nama = item.getNama();
                if (nama == null || nama.trim().isEmpty()) {
                    nama = "Umum";
                }
                holder.yoloNameText.setText(nama);
                if (nama.equalsIgnoreCase("Umum")) {
                    holder.yoloNameText.setTextColor(Color.parseColor("#8D93A5"));
                } else {
                    holder.yoloNameText.setTextColor(Color.parseColor("#4A5568"));
                }
            }

            if (item.getImage_base64() != null) {
                try {
                    byte[] decodedString = Base64.decode(item.getImage_base64(), Base64.DEFAULT);
                    Bitmap decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
                    holder.yoloImage.setImageBitmap(decodedByte);
                } catch (Exception e) {
                    holder.yoloImage.setImageResource(android.R.drawable.ic_menu_camera);
                }
            }
        } else {
            holder.yoloFullContainer.setVisibility(View.GONE);
            holder.mpuFullContainer.setVisibility(View.VISIBLE);

            holder.mpuTimeText.setText(item.getTime());

            String mpuText = item.getConfidence() != null ? item.getConfidence() : "Terdeteksi Benturan";
            holder.mpuDetailText.setText(mpuText);
        }

        // TAMBAHAN: Eksekusi ketika kartu riwayat ditekan dan ditahan
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onItemLongClick(item);
            }
            return true; // Mengembalikan true agar klik biasa tidak ikut terpicu
        });
    }

    @Override
    public int getItemCount() {
        return historyList.size();
    }

    static class HistoryViewHolder extends RecyclerView.ViewHolder {
        LinearLayout yoloFullContainer, mpuFullContainer;
        ImageView yoloImage;
        TextView yoloTimeText, yoloConfidenceText, yoloSourceText, yoloNameText, mpuTimeText, mpuDetailText, yoloKemiringanText, yoloAkurasiAiText;

        public HistoryViewHolder(@NonNull View itemView) {
            super(itemView);
            yoloFullContainer = itemView.findViewById(R.id.yoloFullContainer);
            mpuFullContainer = itemView.findViewById(R.id.mpuFullContainer);

            yoloImage = itemView.findViewById(R.id.yoloCaptureImage);
            yoloTimeText = itemView.findViewById(R.id.yoloTimeText);
            yoloConfidenceText = itemView.findViewById(R.id.yoloConfidenceText);

            yoloKemiringanText = itemView.findViewById(R.id.yoloKemiringanText);
            yoloAkurasiAiText = itemView.findViewById(R.id.yoloAkurasiAiText);

            yoloSourceText = itemView.findViewById(R.id.yoloSourceText);
            yoloNameText = itemView.findViewById(R.id.yoloNameText);

            mpuTimeText = itemView.findViewById(R.id.mpuTimeText);
            mpuDetailText = itemView.findViewById(R.id.mpuDetailText);
        }
    }
}