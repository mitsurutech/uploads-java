package dev.learntools.uploads;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The page must come out byte for byte the same as the Node sample's page for the same
 * files. The three {@code golden-*.html} files are what {@code TZ=UTC node ref-render.mjs
 * two|empty|trouble} printed, so a change to {@code Page.java} that alters one byte fails here.
 */
class PageTest {

    private static String golden(String name) throws IOException {
        try (var in = PageTest.class.getResourceAsStream("/golden-" + name + ".html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void twoFiles() throws IOException {
        List<Page.Row> files = List.of(
            new Page.Row(2, "<img src=x onerror=alert(1)>.png", 1536, Instant.parse("2026-10-12T14:05:09Z")),
            new Page.Row(1, "notes \"quoted\" & more.txt", 12, Instant.parse("2026-10-11T09:01:02Z")));
        assertEquals(golden("two"), Page.page(files, ""));
    }

    @Test
    void empty() throws IOException {
        assertEquals(golden("empty"), Page.page(List.of(), ""));
    }

    @Test
    void trouble() throws IOException {
        assertEquals(golden("trouble"), Page.page(List.of(), "Something went wrong: <b>x</b>"));
    }

    @Test
    void sizesAndKinds() {
        assertEquals("0 B", Page.saidSize(0));
        assertEquals("1023 B", Page.saidSize(1023));
        assertEquals("1.0 KB", Page.saidSize(1024));
        assertEquals("1.3 KB", Page.saidSize(1280));
        assertEquals("1.0 MB", Page.saidSize(1024 * 1024 + 1));
        assertEquals("1.3 MB", Page.saidSize(1024 * 1024 * 5 / 4));
        assertEquals("10.0 MB", Page.saidSize(10L * 1024 * 1024));
        assertEquals("PDF", Page.kindOf("a.pdf"));
        assertEquals("FILE", Page.kindOf("a.markdown"));
        assertEquals("FILE", Page.kindOf(".bashrc"));
        assertEquals("FILE", Page.kindOf("noext"));
    }
}
