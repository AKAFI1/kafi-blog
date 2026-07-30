package dev.kafi.safepoints;

/**
 * Starting harness, not a finished experiment.
 *
 * Shows one thread sitting in an int-counted loop while another thread asks for a
 * VM operation. Whether the requesting thread waits milliseconds or seconds depends
 * on whether C2 left a safepoint poll inside the loop, which depends on the JDK
 * release and the collector in use.
 *
 * Run with: -Xlog:safepoint and compare across collectors.
 *
 * Note on compilation state: the interpreter polls on every backedge, so the loop
 * has to reach C2 before the effect can appear. That is why spin() warms count()
 * with short trip counts before the long one.
 */
public final class PollDemo {

    private static volatile long sink;

    private PollDemo() {
    }

    // region:spinner
    private static long count(int trips) {
        long acc = 0;
        for (int i = 0; i < trips; i++) {
            acc += i ^ (i >>> 3);
        }
        return acc;
    }
    // endregion:spinner

    private static void spin() {
        // Drive count() to a C2 compilation before the long-running call.
        for (int w = 0; w < 20_000; w++) {
            sink = count(10_000);
        }
        sink = count(Integer.MAX_VALUE);
    }

    // region:probe
    private static void probe(int rounds) throws InterruptedException {
        for (int i = 0; i < rounds; i++) {
            long t0 = System.nanoTime();
            System.gc();
            long elapsedMicros = (System.nanoTime() - t0) / 1_000;
            System.out.printf("round %2d  requester blocked %,9d us%n", i, elapsedMicros);
            Thread.sleep(50);
        }
    }
    // endregion:probe

    public static void main(String[] args) throws InterruptedException {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 12;

        Thread spinner = new Thread(PollDemo::spin, "spinner");
        spinner.setDaemon(true);
        spinner.start();

        Thread.sleep(1_000);
        probe(rounds);

        System.out.println("sink = " + sink);
    }
}
