import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;

/** Checks the model independently of Spring and prints Vosk's native loader diagnostics. */
public final class VoskModelProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass an unpacked Vosk model directory");
        LibVosk.setLogLevel(LogLevel.INFO);
        System.out.println("Loading " + args[0]);
        long start = System.nanoTime();
        try (Model ignored = new Model(args[0])) {
            System.out.printf("Model loaded in %.2f s%n", (System.nanoTime() - start) / 1e9);
        }
    }
}
