package me.nicolas.papea2ac.replay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import me.nicolas.papea2ac.schema.TransportCapsule;

/**
 * Durable decision-capsule storage (REPLAY_SYSTEM: "I/O serializes/compresses to repository-configured temporary storage, flushes and
 * atomically promotes the manifest before a durable receipt ... Queue capacity is 16 MiB globally inside replay's partition, charged by
 * retained bytes/pins ... No file work runs on main/network/actor threads"). One bounded I/O thread. Each queued job is charged its retained
 * capsule bytes to the replay IO pool until its receipt.
 *
 * <p>Group commit. The I/O thread drains every queued job, up to {@link #MAX_BATCH_JOBS} jobs or {@link #MAX_BATCH_BYTES} charged bytes. For each
 * job it encodes ({@link CapsuleCodec}) and deflates the capsule; a job that cannot be encoded gets its own failed receipt. It then writes the
 * batch's data file {@code <first id>.capsules.tmp}, calls {@code force(true)} and ATOMIC_MOVEs it to {@code .capsules}. Next it writes the
 * manifest (format, compression, and per capsule its id, offset, sizes, frames and the SHA-256 of its stored bytes) to {@code .manifest.tmp},
 * calls {@code force(true)} and ATOMIC_MOVEs it to {@code .manifest}. Where the platform allows, the directory is also forced. Only then is every
 * job's receipt durable. A failure at any file step fails every job of the batch with the reason and removes the temporary files. Two flushes
 * per batch, not per capsule: a correlated burst is committed together.
 *
 * <p>Retention: at most 7 days and 5 GiB (EVIDENCE_MODEL defaults); the oldest batches are evicted first. {@link #read} is the verifying reader.
 */
public final class DurableCapsuleWriter implements AutoCloseable {
    public static final int QUEUE_CAPACITY = 256, MAX_BATCH_JOBS = 64;
    public static final long MAX_BATCH_BYTES = 8L << 20, RETAIN_BYTES = 5L << 30, RETAIN_MILLIS = 7L * 24 * 3600 * 1000;
    /** The outcome of one submission. {@code submittedNanos}/{@code durableNanos} are System.nanoTime() stamps (latency = the difference). */
    public record Receipt(String id, boolean durable, String sha256, long storedBytes, long encodedBytes, int frames, long submittedNanos, long durableNanos, String failure, String batch) {
        public long latencyNanos() { return durableNanos - submittedNanos; }
    }
    private record Job(String id, TransportCapsule capsule, Consumer<Receipt> callback, long submittedNanos, long charge, ReplayQuota.Lease io) {}
    private record Encoded(Job job, byte[] stored, int encodedBytes, String sha256) {}

    private final Path directory; private final ReplayQuota quota; private final Consumer<Throwable> failures;
    private final ArrayBlockingQueue<Job> jobs = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong submitted = new AtomicLong(), durable = new AtomicLong(), failed = new AtomicLong(), rejected = new AtomicLong(), evicted = new AtomicLong(), batches = new AtomicLong();
    private final Thread thread;

    public DurableCapsuleWriter(Path directory, ReplayQuota quota, Consumer<Throwable> failures) {
        this.directory = Objects.requireNonNull(directory); this.quota = Objects.requireNonNull(quota); this.failures = Objects.requireNonNull(failures);
        thread = new Thread(this::run, "Papea2AC-durable-capsules"); thread.setDaemon(true); thread.start();
    }
    /** Non-blocking: false when the IO pool or the queue is saturated or the writer is closing (the caller treats it as capture unavailable). */
    public boolean submit(String id, TransportCapsule capsule, Consumer<Receipt> callback) {
        if (id == null || !id.matches("[0-9a-f]{16,64}")) throw new IllegalArgumentException("capsule id");
        Objects.requireNonNull(capsule); Objects.requireNonNull(callback);
        if (closing.get() || !capsule.complete()) { rejected.incrementAndGet(); return false; }
        long charge = 512; for (TransportCapsule.Frame f : capsule.frames()) charge += f.chargedBytes();
        var lease = quota.reserve(ReplayQuota.Pool.IO, 1, Math.min(charge, 16L << 20), 1);
        if (lease.isEmpty()) { rejected.incrementAndGet(); return false; }
        Job j = new Job(id, capsule, callback, System.nanoTime(), charge, lease.get());
        if (!jobs.offer(j)) { j.io().close(); rejected.incrementAndGet(); return false; }
        submitted.incrementAndGet(); return true;
    }
    private void run() {
        while (!closing.get() || !jobs.isEmpty()) {
            Job first; try { first = jobs.poll(50, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { continue; }
            if (first == null) continue;
            List<Job> batch = new ArrayList<>(); batch.add(first); long bytes = first.charge();
            for (Job next; batch.size() < MAX_BATCH_JOBS && (next = jobs.peek()) != null && bytes + next.charge() <= MAX_BATCH_BYTES; ) { batch.add(jobs.poll()); bytes += next.charge(); }
            List<Receipt> receipts = commit(batch);
            for (int i = 0; i < batch.size(); i++) {
                Job j = batch.get(i); j.io().close();
                try { j.callback().accept(receipts.get(i)); } catch (RuntimeException e) { failures.accept(e); }
            }
        }
    }
    /** One group commit; the receipts are in batch order. */
    private List<Receipt> commit(List<Job> batch) {
        Receipt[] out = new Receipt[batch.size()]; List<Encoded> ok = new ArrayList<>(); List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            Job j = batch.get(i);
            try { byte[] encoded = CapsuleCodec.encode(j.capsule()); byte[] stored = deflate(encoded); ok.add(new Encoded(j, stored, encoded.length, sha256(stored))); slots.add(i); }
            catch (IOException | RuntimeException e) { failures.accept(e); failed.incrementAndGet(); out[i] = failure(j, e, ""); }
        }
        if (!ok.isEmpty()) {
            String name = ok.getFirst().job().id();
            try {
                write(name, ok); long done = System.nanoTime(); batches.incrementAndGet();
                for (int k = 0; k < ok.size(); k++) { Encoded e = ok.get(k); Job j = e.job(); durable.incrementAndGet();
                    out[slots.get(k)] = new Receipt(j.id(), true, e.sha256(), e.stored().length, e.encodedBytes(), j.capsule().frames().size(), j.submittedNanos(), done, "", name); }
                try { evict(); } catch (IOException ex) { failures.accept(ex); }
            } catch (IOException | RuntimeException ex) {
                failures.accept(ex); cleanup(name);
                for (int k = 0; k < ok.size(); k++) { failed.incrementAndGet(); out[slots.get(k)] = failure(ok.get(k).job(), ex, name); }
            }
        }
        return Arrays.asList(out);
    }
    private static Receipt failure(Job j, Exception e, String batch) { return new Receipt(j.id(), false, "", 0, 0, j.capsule().frames().size(), j.submittedNanos(), System.nanoTime(), e.getClass().getSimpleName(), batch); }
    private void write(String name, List<Encoded> entries) throws IOException {
        Files.createDirectories(directory);
        Path tmp = directory.resolve(name + ".capsules.tmp"), fin = directory.resolve(name + ".capsules");
        StringBuilder manifest = new StringBuilder("{\"format\":\"papea2ac-capsule-batch\",\"version\":1,\"compression\":\"deflate\",\"data\":\"").append(name).append(".capsules\",\"entries\":[\n");
        long offset = 0;
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (int k = 0; k < entries.size(); k++) {
                Encoded e = entries.get(k); ByteBuffer b = ByteBuffer.wrap(e.stored()); while (b.hasRemaining()) ch.write(b);
                manifest.append("{\"id\":\"").append(e.job().id()).append("\",\"offset\":").append(offset).append(",\"storedBytes\":").append(e.stored().length)
                    .append(",\"encodedBytes\":").append(e.encodedBytes()).append(",\"frames\":").append(e.job().capsule().frames().size()).append(",\"schema\":").append(e.job().capsule().schema())
                    .append(",\"sha256\":\"").append(e.sha256()).append("\",\"complete\":").append(e.job().capsule().complete()).append('}').append(k + 1 < entries.size() ? ",\n" : "\n");
                offset += e.stored().length;
            }
            ch.force(true);
        }
        Files.move(tmp, fin, StandardCopyOption.ATOMIC_MOVE);
        manifest.append("]}\n");
        Path mtmp = directory.resolve(name + ".manifest.tmp"), mfin = directory.resolve(name + ".manifest");
        try (FileChannel ch = FileChannel.open(mtmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { ByteBuffer b = ByteBuffer.wrap(manifest.toString().getBytes(StandardCharsets.UTF_8)); while (b.hasRemaining()) ch.write(b); ch.force(true); }
        Files.move(mtmp, mfin, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory();
    }
    /** Makes the two renames durable where a directory can be opened for force (POSIX); Windows cannot open a directory channel and commits NTFS metadata through its journal. */
    private void forceDirectory() { try (FileChannel d = FileChannel.open(directory, StandardOpenOption.READ)) { d.force(true); } catch (IOException | UnsupportedOperationException ignored) { } }
    private void cleanup(String name) { for (String suffix : new String[] { ".capsules.tmp", ".manifest.tmp" }) try { Files.deleteIfExists(directory.resolve(name + suffix)); } catch (IOException ignored) { } }
    private void evict() throws IOException {
        List<Path> manifests = new ArrayList<>(); long total = 0, now = System.currentTimeMillis();
        try (var s = Files.list(directory)) { for (Path p : s.toList()) { String n = p.getFileName().toString(); if (n.endsWith(".capsules") || n.endsWith(".manifest")) { total += Files.size(p); if (n.endsWith(".manifest")) manifests.add(p); } } }
        if (total <= RETAIN_BYTES && manifests.stream().allMatch(m -> age(m, now) <= RETAIN_MILLIS)) return;
        manifests.sort(Comparator.comparingLong(p -> { try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; } }));
        for (Path m : manifests) {
            if (total <= RETAIN_BYTES && age(m, now) <= RETAIN_MILLIS) break;
            String name = m.getFileName().toString().replace(".manifest", ""); Path c = directory.resolve(name + ".capsules");
            total -= Files.size(m) + (Files.exists(c) ? Files.size(c) : 0); Files.deleteIfExists(m); Files.deleteIfExists(c); evicted.incrementAndGet(); // manifest first: never a manifest naming absent data
        }
    }
    private static long age(Path p, long now) { try { return now - Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; } }

    private static final Pattern ENTRY = Pattern.compile("\\{\"id\":\"([0-9a-f]{16,64})\",\"offset\":(\\d+),\"storedBytes\":(\\d+),\"encodedBytes\":(\\d+),\"frames\":(\\d+),\"schema\":(\\d+),\"sha256\":\"([0-9a-f]{64})\",\"complete\":(true|false)\\}");
    /**
     * The verifying reader: finds {@code id} in a promoted manifest (temporary files are never read), checks the stored bytes against the
     * manifest's SHA-256, inflates within the encoded size the manifest declares (at most the 8 MiB capsule bound) and decodes. Throws when
     * the capsule is absent, corrupt or inconsistent.
     */
    public static TransportCapsule read(Path directory, String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{16,64}")) throw new IOException("capsule id");
        try (var s = Files.list(directory)) { for (Path m : s.filter(p -> p.getFileName().toString().endsWith(".manifest")).toList()) { TransportCapsule c = readFrom(directory, m, id); if (c != null) return c; } }
        throw new NoSuchFileException(id);
    }
    /** As {@link #read(Path, String)}, addressed by the batch named in the receipt ({@link Receipt#batch}). */
    public static TransportCapsule read(Path directory, String batch, String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{16,64}") || batch == null || !batch.matches("[0-9a-f]{16,64}")) throw new IOException("capsule id");
        TransportCapsule c = readFrom(directory, directory.resolve(batch + ".manifest"), id);
        if (c == null) throw new NoSuchFileException(id);
        return c;
    }
    /** The capsule {@code id} from manifest {@code m}, verified; null when that manifest does not name it. */
    private static TransportCapsule readFrom(Path directory, Path m, String id) throws IOException {
        String text = Files.readString(m);
        if (!text.contains("\"id\":\"" + id + "\"")) return null;
        Matcher e = ENTRY.matcher(text);
        while (e.find()) {
            if (!e.group(1).equals(id)) continue;
            long offset = Long.parseLong(e.group(2)); int stored = Math.toIntExact(Long.parseLong(e.group(3))), encoded = Math.toIntExact(Long.parseLong(e.group(4)));
            if (encoded > TransportCapsule.MAX_BYTES * 2L || stored > encoded + 1024L) throw new IOException("declared size");
            byte[] bytes = new byte[stored];
            try (FileChannel ch = FileChannel.open(directory.resolve(m.getFileName().toString().replace(".manifest", ".capsules")), StandardOpenOption.READ)) {
                ByteBuffer b = ByteBuffer.wrap(bytes); long pos = offset; while (b.hasRemaining()) { int n = ch.read(b, pos); if (n < 0) throw new IOException("truncated capsule data"); pos += n; }
            }
            if (!sha256(bytes).equals(e.group(7))) throw new IOException("SHA-256 mismatch");
            byte[] plain = inflate(bytes, encoded);
            TransportCapsule c = CapsuleCodec.decode(plain);
            if (c.frames().size() != Integer.parseInt(e.group(5)) || c.complete() != Boolean.parseBoolean(e.group(8))) throw new IOException("manifest/capsule mismatch");
            return c;
        }
        return null;
    }
    static byte[] deflate(byte[] in) {
        Deflater d = new Deflater(Deflater.BEST_SPEED); d.setInput(in); d.finish();
        byte[] buf = new byte[Math.max(64, in.length / 2)]; var out = new ByteArrayOutputStream(buf.length);
        while (!d.finished()) { int n = d.deflate(buf); out.write(buf, 0, n); }
        d.end(); return out.toByteArray();
    }
    static byte[] inflate(byte[] in, int expected) throws IOException {
        Inflater i = new Inflater(); i.setInput(in); byte[] out = new byte[expected]; int n = 0;
        try { while (!i.finished() && n < expected) { int k = i.inflate(out, n, expected - n); if (k == 0 && (i.needsInput() || i.needsDictionary())) break; n += k; }
            if (!i.finished() || n != expected) throw new IOException("inflated size"); }
        catch (DataFormatException e) { throw new IOException("deflate stream", e); }
        finally { i.end(); }
        return out;
    }
    static String sha256(byte[] b) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    public long submitted() { return submitted.get(); }
    public long durable() { return durable.get(); }
    public long failed() { return failed.get(); }
    public long rejected() { return rejected.get(); }
    public long evicted() { return evicted.get(); }
    public long batches() { return batches.get(); }
    public int queued() { return jobs.size(); }
    public boolean terminated() { return !thread.isAlive(); }
    @Override public void close() { closing.set(true); }
}
