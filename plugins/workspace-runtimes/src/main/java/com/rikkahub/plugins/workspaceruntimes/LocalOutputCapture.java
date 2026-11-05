package com.rikkahub.plugins.workspaceruntimes;

import com.rikkahub.agent.execution.CapturedOutput;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** A pump never stops consuming just because storage failed or a quota was exhausted. */
final class LocalOutputCapture {
    private final int memoryLimit;
    private final LocalOutputStore store;
    private final ByteArrayOutputStream preview;
    private long total;
    private long spilled;
    private String reference;
    private boolean spillAttempted;
    private boolean spillTruncated;
    private boolean failed;
    private boolean drained;

    LocalOutputCapture(RuntimeLimits limits, LocalOutputStore store) {
        this.memoryLimit = limits.outputMemoryBytes();
        this.store = store;
        this.preview = new ByteArrayOutputStream(memoryLimit);
    }

    void pump(InputStream input) {
        try (input) {
            byte[] buffer = new byte[8192];
            for (int n; (n = input.read(buffer)) != -1;) accept(buffer, n);
            synchronized (this) { drained = true; }
        } catch (IOException | RuntimeException e) { synchronized (this) { failed = true; } }
    }

    private synchronized void accept(byte[] bytes, int length) {
        total = total > Long.MAX_VALUE - length ? Long.MAX_VALUE : total + length;
        int copied = Math.min(length, memoryLimit - preview.size());
        preview.write(bytes, 0, copied);
        if (total <= memoryLimit || failed) return;
        try {
            if (!spillAttempted) {
                spillAttempted = true;
                reference = store.create();
                if (reference != null) {
                    byte[] prefix = preview.toByteArray();
                    append(prefix, 0, prefix.length);
                    // prefix includes the part of this chunk retained in memory
                    append(bytes, copied, length - copied);
                } else spillTruncated = true;
            } else if (reference != null) append(bytes, 0, length);
        } catch (IOException | RuntimeException e) {
            failed = true;
            spillTruncated = true;
        }
    }

    private void append(byte[] bytes, int offset, int length) throws IOException {
        int n = store.append(reference, bytes, offset, length, spilled);
        spilled += n;
        if (n < length) spillTruncated = true;
    }

    synchronized boolean failed() { return failed; }
    // Storage failure still consumes to EOF; a pipe-reader failure does not.
    synchronized boolean drained() { return drained; }

    synchronized CapturedOutput snapshot() {
        // Ignore a trailing partial UTF-8 code point; replace other malformed input.
        ByteBuffer input = ByteBuffer.wrap(preview.toByteArray());
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        var chars = java.nio.CharBuffer.allocate(memoryLimit);
        decoder.decode(input, chars, false);
        chars.flip();
        String text = chars.toString();
        // Invalid input can expand to three-byte replacement characters.
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        int end = text.length();
        while (bytes > memoryLimit && end > 0) {
            int cp = text.codePointBefore(end);
            end -= Character.charCount(cp);
            bytes -= new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
        }
        return new CapturedOutput(text.substring(0, end), total, total > memoryLimit, reference, spillTruncated || failed);
    }
}
