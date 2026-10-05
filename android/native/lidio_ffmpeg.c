/*
 * LiDio – FFmpeg for formats Android can't open (card b697496c): WMA, APE, WavPack, DSD, Musepack, TAK, TTA …
 * Opens a file through its descriptor (the app gets it from Android's folder grant), reads tags and the embedded
 * picture, and hands out 16-bit PCM (stereo at most) that LiDio wraps as a WAV stream for the player.
 * No network, no protocols: reading and seeking go straight to the descriptor.
 *
 * Copyright (C) 2026 Olaf Winkler – GPL-3.0. FFmpeg is LGPL-2.1+.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>

typedef struct {
    int fd;
    AVIOContext *io;
    AVFormatContext *fmt;
    AVCodecContext *codec;
    SwrContext *swr;
    AVPacket *packet;
    AVFrame *frame;
    int stream;
    int out_rate, out_channels;
    uint8_t *pending; int pending_size, pending_pos, pending_capacity;
    int64_t skip_until;      /* output samples to drop after a seek (exact position) */
    int64_t position;        /* output samples handed out so far */
    int ended;
} Song;

static int read_fd(void *opaque, uint8_t *buf, int size) {
    Song *s = opaque;
    ssize_t n = read(s->fd, buf, size);
    return n > 0 ? (int) n : (n == 0 ? AVERROR_EOF : AVERROR(EIO));
}

static int64_t seek_fd(void *opaque, int64_t offset, int whence) {
    Song *s = opaque;
    if (whence == AVSEEK_SIZE) { struct stat st; return fstat(s->fd, &st) == 0 ? st.st_size : -1; }
    return lseek(s->fd, offset, whence & ~AVSEEK_FORCE);
}

static void release(Song *s) {
    if (!s) return;
    swr_free(&s->swr);
    avcodec_free_context(&s->codec);
    if (s->fmt) avformat_close_input(&s->fmt);
    if (s->io) { av_freep(&s->io->buffer); avio_context_free(&s->io); }
    av_packet_free(&s->packet);
    av_frame_free(&s->frame);
    free(s->pending);
    if (s->fd >= 0) close(s->fd);
    free(s);
}

JNIEXPORT jlong JNICALL Java_io_github_veritasx1_lidio_Ffmpeg_open(JNIEnv *env, jclass cls, jint fd) {
    Song *s = calloc(1, sizeof(Song));
    if (!s) return 0;
    s->fd = dup(fd);
    s->stream = -1;
    unsigned char *buffer = av_malloc(64 * 1024);
    s->io = avio_alloc_context(buffer, 64 * 1024, 0, s, read_fd, NULL, seek_fd);
    s->fmt = avformat_alloc_context();
    if (!s->io || !s->fmt) { release(s); return 0; }
    s->fmt->pb = s->io;
    s->fmt->flags |= AVFMT_FLAG_CUSTOM_IO;
    if (avformat_open_input(&s->fmt, NULL, NULL, NULL) < 0) { s->fmt = NULL; release(s); return 0; }
    if (avformat_find_stream_info(s->fmt, NULL) < 0) { release(s); return 0; }
    const AVCodec *decoder = NULL;
    s->stream = av_find_best_stream(s->fmt, AVMEDIA_TYPE_AUDIO, -1, -1, &decoder, 0);
    if (s->stream < 0 || !decoder) { release(s); return 0; }
    s->codec = avcodec_alloc_context3(decoder);
    avcodec_parameters_to_context(s->codec, s->fmt->streams[s->stream]->codecpar);
    if (avcodec_open2(s->codec, decoder, NULL) < 0) { release(s); return 0; }

    int rate = s->codec->sample_rate;
    /* DSD and other very high rates: 88.2/96 kHz – plenty for listening, and every phone plays it. */
    s->out_rate = rate <= 96000 ? rate : (rate % 44100 == 0 ? 88200 : 96000);
    s->out_channels = s->codec->ch_layout.nb_channels >= 2 ? 2 : 1;
    AVChannelLayout out_layout;
    av_channel_layout_default(&out_layout, s->out_channels);
    if (swr_alloc_set_opts2(&s->swr, &out_layout, AV_SAMPLE_FMT_S16, s->out_rate,
            &s->codec->ch_layout, s->codec->sample_fmt, rate, 0, NULL) < 0 || swr_init(s->swr) < 0) { release(s); return 0; }
    s->packet = av_packet_alloc();
    s->frame = av_frame_alloc();
    return (jlong) (intptr_t) s;
}

JNIEXPORT jint JNICALL Java_io_github_veritasx1_lidio_Ffmpeg_sampleRate(JNIEnv *env, jclass cls, jlong h) { return ((Song *) (intptr_t) h)->out_rate; }
JNIEXPORT jint JNICALL Java_io_github_veritasx1_lidio_Ffmpeg_channels(JNIEnv *env, jclass cls, jlong h) { return ((Song *) (intptr_t) h)->out_channels; }

JNIEXPORT jlong JNICALL Java_io_github_veritasx1_lidio_Ffmpeg_durationUs(JNIEnv *env, jclass cls, jlong h) {
    Song *s = (Song *) (intptr_t) h;
    if (s->fmt->duration != AV_NOPTS_VALUE && s->fmt->duration > 0) return s->fmt->duration;
    AVStream *st = s->fmt->streams[s->stream];
    if (st->duration != AV_NOPTS_VALUE && st->duration > 0) return av_rescale_q(st->duration, st->time_base, AV_TIME_BASE_Q);
    return 0;
}

/* title, artist, album_artist, album, track, disc, date, genre – from the file, else from the audio stream. */
JNIEXPORT jobjectArray JNICALL Java_io_github_veritasx1_lidio_Ffmpeg_tags(JNIEnv *env, jclass cls, jlong h) {
    Song *s = (Song *) (intptr_t) h;
    static const char *keys[] = { "title", "artist", "album_artist", "album", "track", "disc", "date", "genre" };
    jobjectArray out = (*env)->NewObjectArray(env, 8, (*env)->FindClass(env, "java/lang/String"), NULL);
    for (int i = 0; i < 8; i++) {
        AVDictionaryEntry *e = av_dict_get(s->fmt->metadata, keys[i], NULL, 0);
        if (!e) e = av_dict_get(s->fmt->streams[s->stream]->metadata, keys[i], NULL, 0);
        if (!e && i == 2) e = av_dict_get(s->fmt->metadata, "albumartist", NULL, 0);
        if (!e && i == 6) e = av_dict_get(s->fmt->metadata, "year", NULL, 0);
        if (e && e->value) (*env)->SetObjectArrayElement(env, out, i, (*env)->NewStringUTF(env, e->value));
    }
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_veritasx1_lidio_Ffmpeg_cover(JNIEnv *env, jclass cls, jlong h) {
    Song *s = (Song *) (intptr_t) h;
    for (unsigned i = 0; i < s->fmt->nb_streams; i++) {
        AVStream *st = s->fmt->streams[i];
        if ((st->disposition & AV_DISPOSITION_ATTACHED_PIC) && st->attached_pic.size > 0) {
            jbyteArray out = (*env)->NewByteArray(env, st->attached_pic.size);
            (*env)->SetByteArrayRegion(env, out, 0, st->attached_pic.size, (const jbyte *) st->attached_pic.data);
            return out;
        }
    }
    return NULL;
}

/* Decodes the next frame into the pending buffer; 0 at the end. */
static int decode_more(Song *s) {
    for (;;) {
        int r = avcodec_receive_frame(s->codec, s->frame);
        if (r == 0) {
            int capacity = swr_get_out_samples(s->swr, s->frame->nb_samples) + 32;
            int bytes = capacity * s->out_channels * 2;
            if (bytes > s->pending_capacity) { s->pending = realloc(s->pending, bytes); s->pending_capacity = bytes; }
            uint8_t *out = s->pending;
            int got = swr_convert(s->swr, &out, capacity, (const uint8_t **) s->frame->extended_data, s->frame->nb_samples);
            av_frame_unref(s->frame);
            if (got <= 0) continue;
            int start = 0;
            /* After a seek: drop what lies before the wanted sample. */
            if (s->skip_until > s->position) {
                int64_t drop = s->skip_until - s->position;
                if (drop >= got) { s->position += got; continue; }
                start = (int) drop; s->position += drop;
            }
            s->pending_pos = start * s->out_channels * 2;
            s->pending_size = got * s->out_channels * 2;
            return 1;
        }
        if (r == AVERROR_EOF) return 0;
        if (r != AVERROR(EAGAIN)) return 0;
        if (s->ended) { avcodec_send_packet(s->codec, NULL); continue; }
        r = av_read_frame(s->fmt, s->packet);
        if (r < 0) { s->ended = 1; avcodec_send_packet(s->codec, NULL); continue; }
        if (s->packet->stream_index == s->stream) avcodec_send_packet(s->codec, s->packet);
        av_packet_unref(s->packet);
    }
}

/* PCM bytes into buf; 0 = end, -1 = error. */
JNIEXPORT jint JNICALL Java_io_github_veritasx1_lidio_Ffmpeg_read(JNIEnv *env, jclass cls, jlong h, jbyteArray buf, jint offset, jint length) {
    Song *s = (Song *) (intptr_t) h;
    int done = 0;
    while (done < length) {
        if (s->pending_pos >= s->pending_size) {
            s->pending_pos = s->pending_size = 0;
            if (!decode_more(s)) break;
        }
        int n = s->pending_size - s->pending_pos;
        if (n > length - done) n = length - done;
        n -= n % (s->out_channels * 2);
        if (n <= 0) break;
        (*env)->SetByteArrayRegion(env, buf, offset + done, n, (const jbyte *) (s->pending + s->pending_pos));
        s->pending_pos += n; done += n;
        s->position += n / (s->out_channels * 2);
    }
    return done;
}

/* To the exact output sample: seek the container before it, then drop the rest while decoding. */
JNIEXPORT jboolean JNICALL Java_io_github_veritasx1_lidio_Ffmpeg_seek(JNIEnv *env, jclass cls, jlong h, jlong sample) {
    Song *s = (Song *) (intptr_t) h;
    int64_t us = av_rescale(sample, AV_TIME_BASE, s->out_rate);
    if (av_seek_frame(s->fmt, -1, us, AVSEEK_FLAG_BACKWARD) < 0 && av_seek_frame(s->fmt, -1, 0, AVSEEK_FLAG_BACKWARD) < 0) return JNI_FALSE;
    avcodec_flush_buffers(s->codec);
    swr_init(s->swr);
    s->ended = 0;
    s->pending_pos = s->pending_size = 0;
    /* Where decoding starts again: the first frame tells it; until then assume the container landed at "us". */
    AVStream *st = s->fmt->streams[s->stream];
    int64_t landed = sample;
    if (av_read_frame(s->fmt, s->packet) >= 0) {
        if (s->packet->stream_index == s->stream && s->packet->pts != AV_NOPTS_VALUE)
            landed = av_rescale_q(s->packet->pts, st->time_base, (AVRational) { 1, s->out_rate });
        if (s->packet->stream_index == s->stream) avcodec_send_packet(s->codec, s->packet);
        av_packet_unref(s->packet);
    }
    s->position = landed < 0 ? 0 : (landed > sample ? sample : landed);
    s->skip_until = sample;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_io_github_veritasx1_lidio_Ffmpeg_close(JNIEnv *env, jclass cls, jlong h) { release((Song *) (intptr_t) h); }
