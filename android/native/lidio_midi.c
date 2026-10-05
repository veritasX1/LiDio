/*
 * LiDio – MIDI (card b697496c, Olaf: „bitte auch MIDI-Support“): Sonivox, the General-MIDI synthesizer Android itself
 * uses (Apache-2.0), renders .mid/.kar/.rmi to 16-bit stereo PCM; LiDio wraps it as a WAV stream like FFmpeg's output.
 *
 * Copyright (C) 2026 Olaf Winkler – GPL-3.0. Sonivox is Apache-2.0.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sonivox/eas.h>

typedef struct {
    EAS_DATA_HANDLE eas;
    EAS_HANDLE stream;
    EAS_FILE file;
    unsigned char *data; int size;
    const S_EAS_LIB_CONFIG *config;
    EAS_PCM *buffer; int pending_pos, pending_size;   /* in samples (frames × channels) */
    EAS_I32 length_ms;
    int finished;
} Tune;

static int read_at(void *handle, void *buf, int offset, int size) {
    Tune *t = handle;
    if (offset >= t->size) return 0;
    if (offset + size > t->size) size = t->size - offset;
    memcpy(buf, t->data + offset, size);
    return size;
}

static int file_size(void *handle) { return ((Tune *) handle)->size; }

static void release(Tune *t) {
    if (!t) return;
    if (t->stream) EAS_CloseFile(t->eas, t->stream);
    if (t->eas) EAS_Shutdown(t->eas);
    free(t->buffer); free(t->data); free(t);
}

JNIEXPORT jlong JNICALL Java_io_github_veritasx1_lidio_Midi_open(JNIEnv *env, jclass cls, jint fd) {
    Tune *t = calloc(1, sizeof(Tune));
    if (!t) return 0;
    /* The whole file into memory – MIDI files are small (and a size limit keeps it so). */
    int capacity = 64 * 1024, n;
    t->data = malloc(capacity);
    lseek(fd, 0, SEEK_SET);
    while (t->data && (n = read(fd, t->data + t->size, capacity - t->size)) > 0) {
        t->size += n;
        if (t->size == capacity) {
            if (capacity >= 16 * 1024 * 1024) break;
            capacity *= 2; t->data = realloc(t->data, capacity);
        }
    }
    if (!t->data || t->size == 0) { release(t); return 0; }
    t->config = EAS_Config();
    t->file.handle = t; t->file.readAt = read_at; t->file.size = file_size;
    if (EAS_Init(&t->eas) != EAS_SUCCESS) { t->eas = NULL; release(t); return 0; }
    if (EAS_OpenFile(t->eas, &t->file, &t->stream) != EAS_SUCCESS) { t->stream = NULL; release(t); return 0; }
    /* The length first (Sonivox parses the file for it), then back to the start and ready to play. */
    if (EAS_ParseMetaData(t->eas, t->stream, &t->length_ms) != EAS_SUCCESS) t->length_ms = 0;
    if (EAS_Prepare(t->eas, t->stream) != EAS_SUCCESS) { release(t); return 0; }
    EAS_Locate(t->eas, t->stream, 0, EAS_FALSE);
    t->buffer = malloc(t->config->mixBufferSize * t->config->numChannels * sizeof(EAS_PCM));
    if (t->length_ms <= 0) {
        /* No length from the file's header: render it once silently and count (far faster than real time),
           then open the stream afresh for playing. */
        long long frames = 0; EAS_STATE state; EAS_I32 got;
        while (EAS_State(t->eas, t->stream, &state) == EAS_SUCCESS && state != EAS_STATE_STOPPED && state != EAS_STATE_ERROR
               && frames < (long long) t->config->sampleRate * 60 * 60) {
            if (EAS_Render(t->eas, t->buffer, t->config->mixBufferSize, &got) != EAS_SUCCESS || got <= 0) break;
            frames += got;
        }
        t->length_ms = (EAS_I32) (frames * 1000 / t->config->sampleRate);
        EAS_CloseFile(t->eas, t->stream); t->stream = NULL;
        if (EAS_OpenFile(t->eas, &t->file, &t->stream) != EAS_SUCCESS || EAS_Prepare(t->eas, t->stream) != EAS_SUCCESS) { release(t); return 0; }
    }
    return (jlong) (intptr_t) t;
}

JNIEXPORT jint JNICALL Java_io_github_veritasx1_lidio_Midi_sampleRate(JNIEnv *env, jclass cls, jlong h) { return ((Tune *) (intptr_t) h)->config->sampleRate; }
JNIEXPORT jint JNICALL Java_io_github_veritasx1_lidio_Midi_channels(JNIEnv *env, jclass cls, jlong h) { return ((Tune *) (intptr_t) h)->config->numChannels; }

/* Length in microseconds (read when the file was opened). */
JNIEXPORT jlong JNICALL Java_io_github_veritasx1_lidio_Midi_durationUs(JNIEnv *env, jclass cls, jlong h) {
    return (jlong) ((Tune *) (intptr_t) h)->length_ms * 1000;
}

JNIEXPORT jint JNICALL Java_io_github_veritasx1_lidio_Midi_read(JNIEnv *env, jclass cls, jlong h, jbyteArray out, jint offset, jint length) {
    Tune *t = (Tune *) (intptr_t) h;
    int channels = t->config->numChannels, done = 0;
    while (done < length) {
        if (t->pending_pos >= t->pending_size) {
            EAS_STATE state;
            if (t->finished || EAS_State(t->eas, t->stream, &state) != EAS_SUCCESS || state == EAS_STATE_STOPPED || state == EAS_STATE_ERROR) {
                t->finished = 1; break;
            }
            EAS_I32 frames = 0;
            if (EAS_Render(t->eas, t->buffer, t->config->mixBufferSize, &frames) != EAS_SUCCESS || frames <= 0) { t->finished = 1; break; }
            t->pending_pos = 0; t->pending_size = frames * channels;
        }
        int samples = t->pending_size - t->pending_pos;
        int room = (length - done) / 2;
        if (samples > room) samples = room - room % channels;
        if (samples <= 0) break;
        (*env)->SetByteArrayRegion(env, out, offset + done, samples * 2, (const jbyte *) (t->buffer + t->pending_pos));
        t->pending_pos += samples; done += samples * 2;
    }
    return done;
}

JNIEXPORT jboolean JNICALL Java_io_github_veritasx1_lidio_Midi_seek(JNIEnv *env, jclass cls, jlong h, jlong sample) {
    Tune *t = (Tune *) (intptr_t) h;
    EAS_I32 ms = (EAS_I32) (sample * 1000 / t->config->sampleRate);
    t->pending_pos = t->pending_size = 0; t->finished = 0;
    return EAS_Locate(t->eas, t->stream, ms, EAS_FALSE) == EAS_SUCCESS ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_io_github_veritasx1_lidio_Midi_close(JNIEnv *env, jclass cls, jlong h) { release((Tune *) (intptr_t) h); }
