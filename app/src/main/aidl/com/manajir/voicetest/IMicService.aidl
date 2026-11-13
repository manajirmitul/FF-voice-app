package com.manajir.voicetest;

import android.os.ParcelFileDescriptor;

interface IMicService {
    void destroy() = 16777114;
    String start(in ParcelFileDescriptor input, int sampleRate) = 1;
    String tone(int seconds) = 2;
    void stop() = 3;
}
