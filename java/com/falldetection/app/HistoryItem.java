package com.falldetection.app;

public class HistoryItem {
    private String key; // TAMBAHAN: Untuk menyimpan ID Unik dari Firebase
    private String time;
    private String confidence;
    private String source;
    private String image_base64;
    private String nama;
    private String kemiringan;
    private String akurasiAi;

    public HistoryItem() {
    }

    public HistoryItem(String time, String confidence, String source, String image_base64, String nama, String kemiringan, String akurasiAi) {
        this.time = time;
        this.confidence = confidence;
        this.source = source;
        this.image_base64 = image_base64;
        this.nama = nama;
        this.kemiringan = kemiringan;
        this.akurasiAi = akurasiAi;
    }

    // Getter dan Setter untuk Key Firebase
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getTime() { return time; }
    public String getConfidence() { return confidence; }
    public String getSource() { return source; }
    public String getImage_base64() { return image_base64; }
    public String getNama() { return nama; }
    public String getKemiringan() { return kemiringan; }
    public String getAkurasiAi() { return akurasiAi; }
}