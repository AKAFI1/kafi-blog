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
 * with short trip counts before the measured phase.
 *
 * Note on duration: an int-counted loop cannot be stretched to cover an arbitrary
 * measurement window, because the trip count saturates at Integer.MAX_VALUE, which
 * on a current CPU is a fraction of a second. spin() therefore re-enters count()
 * until told to stop, so the spinner is inside a poll-free loop for essentially the
 * whole window rather than only the first round. The consequence to keep in mind
 * when reading the numbers: each call boundary is itself a poll point, so a request
 * that happens to land on one sees a short wait. The distribution matters, not any
 * single round.
 */
public final class PollDemo {

    private static volatile long sink;
    private static volatile boolean running = true;

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
        // Drive count() to a C2 compilation before the measured phase.
        for (int w = 0; w < 20_000; w++) {
            sink = count(10_000);
        }
        // One full-range call is much shorter than the probe loop, so keep re-entering
        // until the prober is done. The volatile read is outside the hot loop.
        while (running) {
            sink = count(Integer.MAX_VALUE);
        }
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
        running = false;
        spinner.join(5_000);

        System.out.println("sink = " + sink);
    }
}
