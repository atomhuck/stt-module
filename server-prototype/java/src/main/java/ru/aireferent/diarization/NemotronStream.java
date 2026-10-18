package ru.aireferent.diarization;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class NemotronStream implements AutoCloseable {
    @Structure.FieldOrder({"size", "modelPath", "gpu", "preset", "chunkFrames", "rightContextFrames",
            "leftContextFrames", "fifoFrames", "spkcacheFrames", "updatePeriodFrames"})
    public static class Config extends Structure {
        public long size;             // size_t on supported 64-bit hosts
        public Pointer modelPath;
        public int gpu;
        public Pointer preset;
        public int chunkFrames;
        public int rightContextFrames;
        public int leftContextFrames;
        public int fifoFrames;
        public int spkcacheFrames;
        public int updatePeriodFrames;
    }

    @Structure.FieldOrder({"startTime", "endTime", "speaker"})
    public static class Segment extends Structure {
        public double startTime;
        public double endTime;
        public int speaker;
    }

    interface Api extends Library {
        Pointer nemo_speech_asr_last_error();
        int nemo_speech_diar_create(Pointer config, Pointer model);
        void nemo_speech_diar_destroy(Pointer model);
        int nemo_speech_diar_stream_open(Pointer model, Pointer stream);
        int nemo_speech_diar_stream_push_f32(Pointer stream, float[] samples, long length, int sampleRate);
        int nemo_speech_diar_stream_finish(Pointer stream);
        void nemo_speech_diar_stream_close(Pointer stream);
        long nemo_speech_diar_frame_count(Pointer stream);
        double nemo_speech_diar_seconds_per_frame(Pointer model);
        int nemo_speech_diar_segments(Pointer stream, Pointer reserved, Pointer segments,
                                      long capacity, LongByReference count);
    }

    interface Kernel32 extends StdCallLibrary {
        boolean SetDllDirectoryW(WString path);
    }

    interface VersionApi extends StdCallLibrary {
        int GetFileVersionInfoSizeW(WString filename, IntByReference handle);
        boolean GetFileVersionInfoW(WString filename, int handle, int size, Pointer data);
        boolean VerQueryValueW(Pointer data, WString subBlock, PointerByReference value, IntByReference size);
    }

    private final Api api;
    private Pointer model;
    private Pointer stream;
    private final double frameSeconds;

    NemotronStream(Path dll, Path gguf, String preset) {
        if (!Files.isRegularFile(dll) || !Files.isRegularFile(gguf))
            throw new IllegalArgumentException("Не найдены библиотека Nemotron или GGUF: " + dll + ", " + gguf);
        if (Native.POINTER_SIZE != 8) throw new IllegalArgumentException("Нужна 64-битная Java.");
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            checkWindowsRuntime(dll.getParent());
            Native.load("kernel32", Kernel32.class).SetDllDirectoryW(new WString(dll.getParent().toString()));
        }
        System.setProperty("jna.library.path", dll.getParent().toString());
        api = Native.load(dll.toString(), Api.class);
        Config cfg = new Config();
        Memory modelName = utf8(gguf.toString());
        Memory presetName = utf8(preset);
        cfg.size = cfg.size();
        cfg.modelPath = modelName;
        cfg.gpu = -1;
        cfg.preset = presetName;
        cfg.write();
        Memory modelRef = new Memory(Native.POINTER_SIZE);
        modelRef.clear();
        check(api.nemo_speech_diar_create(cfg.getPointer(), modelRef));
        java.lang.ref.Reference.reachabilityFence(modelName);
        java.lang.ref.Reference.reachabilityFence(presetName);
        model = modelRef.getPointer(0);
        try {
            Memory streamRef = new Memory(Native.POINTER_SIZE);
            streamRef.clear();
            check(api.nemo_speech_diar_stream_open(model, streamRef));
            stream = streamRef.getPointer(0);
            frameSeconds = api.nemo_speech_diar_seconds_per_frame(model);
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    private static void checkWindowsRuntime(Path nemoBin) {
        Path javaRuntime = Path.of(System.getProperty("java.home"), "bin", "msvcp140.dll");
        Path nemoRuntime = nemoBin.resolve("msvcp140.dll");
        if (!Files.isRegularFile(javaRuntime) || !Files.isRegularFile(nemoRuntime)) return;
        VersionApi api = Native.load("version", VersionApi.class);
        long[] javaVersion = fileVersion(api, javaRuntime);
        long[] nemoVersion = fileVersion(api, nemoRuntime);
        if (javaVersion == null || nemoVersion == null) return;
        for (int i = 0; i < javaVersion.length; i++) {
            if (javaVersion[i] > nemoVersion[i]) return;
            if (javaVersion[i] < nemoVersion[i]) {
                throw new IllegalStateException("JRE использует msvcp140.dll " + versionText(javaVersion)
                        + ", а Nemotron требует " + versionText(nemoVersion)
                        + ". В IntelliJ IDEA выберите подготовленный совместимый SDK в Run → Edit Configurations → JRE."
                        + " Сейчас java.home=" + System.getProperty("java.home"));
            }
        }
    }

    private static long[] fileVersion(VersionApi api, Path path) {
        WString filename = new WString(path.toString());
        IntByReference unused = new IntByReference();
        int size = api.GetFileVersionInfoSizeW(filename, unused);
        if (size <= 0) return null;
        Memory info = new Memory(size);
        if (!api.GetFileVersionInfoW(filename, 0, size, info)) return null;
        PointerByReference value = new PointerByReference();
        IntByReference length = new IntByReference();
        if (!api.VerQueryValueW(info, new WString("\\"), value, length) || length.getValue() < 16) return null;
        Pointer fixed = value.getValue();
        if (fixed.getInt(0) != 0xFEEF04BD) return null;
        long high = Integer.toUnsignedLong(fixed.getInt(8));
        long low = Integer.toUnsignedLong(fixed.getInt(12));
        return new long[]{high >>> 16, high & 0xffff, low >>> 16, low & 0xffff};
    }

    private static String versionText(long[] version) {
        return version[0] + "." + version[1] + "." + version[2] + "." + version[3];
    }

    private static Memory utf8(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        Memory result = new Memory(data.length + 1L);
        result.write(0, data, 0, data.length);
        result.setByte(data.length, (byte) 0);
        return result;
    }

    private void check(int status) {
        if (status == 0) return;
        Pointer error = api.nemo_speech_asr_last_error();
        String detail = error == null ? "неизвестная ошибка" : error.getString(0, "UTF-8");
        throw new IllegalStateException("Nemotron C API, код " + status + ": " + detail);
    }

    void push(float[] samples) { check(api.nemo_speech_diar_stream_push_f32(stream, samples, samples.length, Support.RATE)); }
    void finish() { check(api.nemo_speech_diar_stream_finish(stream)); }
    double processedSeconds() { return api.nemo_speech_diar_frame_count(stream) * frameSeconds; }

    List<Segment> segments() {
        LongByReference count = new LongByReference();
        check(api.nemo_speech_diar_segments(stream, null, null, 0, count));
        if (count.getValue() == 0) return List.of();
        Segment[] result = (Segment[]) new Segment().toArray((int) count.getValue());
        check(api.nemo_speech_diar_segments(stream, null, result[0].getPointer(), result.length, count));
        List<Segment> items = new ArrayList<>();
        for (int i = 0; i < count.getValue(); i++) {
            result[i].read();
            items.add(result[i]);
        }
        return items;
    }

    @Override public void close() {
        if (stream != null) { api.nemo_speech_diar_stream_close(stream); stream = null; }
        if (model != null) { api.nemo_speech_diar_destroy(model); model = null; }
    }
}
