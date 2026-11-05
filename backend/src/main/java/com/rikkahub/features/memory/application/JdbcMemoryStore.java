package com.rikkahub.features.memory.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.MemoryDocument;
import com.rikkahub.agent.port.MemoryHit;
import com.rikkahub.agent.port.MemoryStore;
import com.rikkahub.platform.storage.EventCodec;
import com.rikkahub.platform.storage.StoragePaths;
import com.rikkahub.platform.storage.TransactionRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Markdown files are the editable source; SQLite FTS5 is a rebuildable index. */
@Service
public final class JdbcMemoryStore implements MemoryStore {
    private final JdbcClient jdbc;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final Path memoryRoot;

    public JdbcMemoryStore(StoragePaths paths, JdbcClient jdbc, TransactionRunner transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
        this.memoryRoot = paths.dataDirectory().resolve("memory");
        try {
            Files.createDirectories(memoryRoot);
        } catch (IOException failure) {
            throw new IllegalStateException("memory directory is unavailable", failure);
        }
        refreshFromFiles();
    }

    @Override
    public List<MemoryHit> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int bounded = Math.max(1, Math.min(50, limit));
        if(query.length()>4096)throw new IllegalArgumentException("memory query is too long");
        List<MemoryHit> matches=new ArrayList<>(jdbc.sql("""
                SELECT id, title, snippet(memory_fts, 2, '[', ']', '...', 12) AS snippet,
                       bm25(memory_fts) AS score
                FROM memory_fts
                WHERE memory_fts MATCH :query
                ORDER BY score
                LIMIT :limit
                """).param("query", ftsQuery(query)).param("limit", bounded)
                .query((row, number) -> new MemoryHit(row.getString("id"), row.getString("title"),
                        row.getString("snippet"), row.getDouble("score"))).list());
        // unicode61 does not split continuous CJK text into searchable words.
        // Keep FTS ranking first and fill with literal fragment matches; instr has no wildcard syntax.
        if(matches.size()<bounded&&query.codePoints().anyMatch(c->Set.of(Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,Character.UnicodeScript.KATAKANA,Character.UnicodeScript.HANGUL)
                .contains(Character.UnicodeScript.of(c)))) {
            List<MemoryHit> fragments=jdbc.sql("""
                    SELECT id,title,substr(body,max(1,instr(body,:query)-30),120) AS snippet
                    FROM memory_documents WHERE instr(title,:query)>0 OR instr(body,:query)>0
                    ORDER BY updated_at DESC,id LIMIT :limit
                    """).param("query",query.trim()).param("limit",bounded+matches.size())
                    .query((row,number)->new MemoryHit(row.getString("id"),row.getString("title"),row.getString("snippet"),0)).list();
            Set<String> found=new HashSet<>();matches.forEach(hit->found.add(hit.id()));
            for(MemoryHit hit:fragments)if(found.add(hit.id())&&matches.size()<bounded)matches.add(hit);
        }
        return List.copyOf(matches);
    }

    private static String ftsQuery(String query) {
        List<String> terms = new ArrayList<>();
        for (String term : query.trim().split("\\s+")) {
            if (!term.isBlank()) {
                terms.add("\"" + term.replace("\"", "\"\"") + "\"");
            }
        }
        return terms.isEmpty() ? "\"\"" : String.join(" OR ", terms);
    }

    @Override
    public Optional<MemoryDocument> read(String id) {
        requireId(id);
        return jdbc.sql("SELECT id,title,body,updated_at FROM memory_documents WHERE id=:id")
                .param("id", id).query((row, number) -> new MemoryDocument(row.getString("id"),
                        row.getString("title"), row.getString("body"), Instant.parse(row.getString("updated_at"))))
                .optional();
    }

    @Override
    public List<MemoryDocument> list() {
        return jdbc.sql("SELECT id,title,body,updated_at FROM memory_documents ORDER BY updated_at DESC,id LIMIT 200")
                .query((row, number) -> new MemoryDocument(row.getString("id"), row.getString("title"),
                        row.getString("body"), Instant.parse(row.getString("updated_at")))).list();
    }

    @Override
    public void upsert(MemoryDocument document) {
        writeFile(document);
        transactions.write(() -> {
            index(document);
            return null;
        });
    }

    @Override
    public boolean delete(String id) {
        requireId(id);
        Boolean existed = jdbc.sql("SELECT 1 FROM memory_documents WHERE id=:id").param("id", id)
                .query(Integer.class).optional().map(value -> true).orElse(false);
        if (!existed) {
            return false;
        }
        transactions.write(() -> {
            jdbc.sql("DELETE FROM memory_fts WHERE id=:id").param("id", id).update();
            jdbc.sql("DELETE FROM memory_documents WHERE id=:id").param("id", id).update();
            return null;
        });
        try {
            Files.deleteIfExists(memoryRoot.resolve(id + ".md"));
        } catch (IOException failure) {
            throw new IllegalStateException("memory file could not be deleted", failure);
        }
        return true;
    }

    /** Rebuilds the index from Markdown; safe after manual edits. */
    @Override
    public synchronized void refresh() {
        refreshFromFiles();
    }

    public synchronized void refreshFromFiles() {
        Set<String> present = new HashSet<>();
        try (Stream<Path> files = Files.list(memoryRoot)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md")).sorted().toList()) {
                String id = file.getFileName().toString();
                id = id.substring(0, id.length() - 3);
                if (!id.matches("[A-Za-z0-9_-]{1,128}")) {
                    continue;
                }
                String content = Files.readString(file, StandardCharsets.UTF_8);
                MemoryDocument document = parse(id, content);
                present.add(id);
                transactions.write(() -> {
                    index(document);
                    return null;
                });
            }
        } catch (IOException failure) {
            throw new IllegalStateException("memory index refresh failed", failure);
        }
        List<String> stale = jdbc.sql("SELECT id FROM memory_documents")
                .query(String.class).list().stream().filter(id -> !present.contains(id)).toList();
        if (!stale.isEmpty()) {
            transactions.write(() -> {
                for (String id : stale) {
                    jdbc.sql("DELETE FROM memory_fts WHERE id=:id").param("id", id).update();
                    jdbc.sql("DELETE FROM memory_documents WHERE id=:id").param("id", id).update();
                }
                return null;
            });
        }
    }

    private void index(MemoryDocument document) {
        String at = EventCodec.timestamp(document.updatedAt());
        jdbc.sql("""
                INSERT INTO memory_documents(id,title,body,updated_at) VALUES(:id,:title,:body,:at)
                ON CONFLICT(id) DO UPDATE SET title=:title,body=:body,updated_at=:at
                """).param("id", document.id()).param("title", document.title())
                .param("body", document.body()).param("at", at).update();
        jdbc.sql("DELETE FROM memory_fts WHERE id=:id").param("id", document.id()).update();
        jdbc.sql("INSERT INTO memory_fts(id,title,body) VALUES(:id,:title,:body)")
                .param("id", document.id()).param("title", document.title())
                .param("body", document.body()).update();
    }

    private void writeFile(MemoryDocument document) {
        requireId(document.id());
        Path file = memoryRoot.resolve(document.id() + ".md");
        try {
            Files.createDirectories(memoryRoot);
            Files.writeString(file, "# " + document.title() + "\n\n" + document.body() + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("memory file could not be written", failure);
        }
    }

    private MemoryDocument parse(String id, String content) {
        String title = id;
        String body = content;
        int firstNewline = content.indexOf('\n');
        if (content.startsWith("# ") && firstNewline > 0) {
            title = content.substring(2, firstNewline).trim();
            body = content.substring(firstNewline + 1).replaceFirst("^\\R", "");
        }
        return new MemoryDocument(id, title, body.trim(), clock.now());
    }

    private static void requireId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("invalid memory id");
        }
    }
}
