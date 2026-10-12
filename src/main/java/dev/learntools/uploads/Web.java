package dev.learntools.uploads;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * The web side of the app: four routes and nothing else.
 *
 * <pre>
 *   GET  /                  the page: a form, and a table of what has been uploaded
 *   POST /upload            keep one file
 *   GET  /files/{id}        send one back
 *   POST /files/{id}/delete forget one
 * </pre>
 *
 * Everything about <em>how</em> the two stores work is in {@code Store.java}. This file is
 * about the web.
 */
@RestController
public class Web {

    private static final Logger log = LoggerFactory.getLogger(Web.class);

    private static final String HTML = "text/html;charset=UTF-8";

    private final Store store;

    public Web(Store store) {
        this.store = store;
    }

    private static ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status).header(HttpHeaders.CONTENT_TYPE, HTML).body(body);
    }

    /** See the other page, not this one: a 303 sends the browser to ask for {@code /} again. */
    private static ResponseEntity<Void> backToThePage() {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, "/").build();
    }

    @GetMapping("/")
    public ResponseEntity<String> home() throws SQLException {
        return html(HttpStatus.OK, Page.page(store.listFiles(), ""));
    }

    /**
     * <b>The file is kept in memory and not on disk</b>, which is right here and would be
     * wrong for a bigger one. Your app's filesystem does not survive a restart, so writing
     * uploads there would lose them; and the point of this sample is that S3 is where bytes
     * live. (Spring may park a big upload in a temporary file while it reads the request, but
     * it is gone by the end of it.) Ten megabytes is a limit somebody chose, in {@code
     * application.properties}, not a rule: a file larger than this should be streamed
     * straight to S3 rather than held whole.
     *
     * <p>{@code required = false} is what lets "no file at all" reach the code below as
     * {@code null} so it can answer with a page, instead of Spring answering with its own
     * error.
     */
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam(value = "file", required = false) MultipartFile file)
            throws SQLException, IOException {
        if (file == null) {
            return html(HttpStatus.BAD_REQUEST, Page.page(store.listFiles(), "Choose a file first."));
        }

        /*
         * **A uuid for the key, never the name somebody typed.** Two people uploading
         * `notes.txt` must not overwrite each other, and a name that arrived from a browser is
         * not a thing to build a path out of: `../../etc/passwd` is a perfectly good filename
         * as far as a form is concerned. The real name is a column, which is where it is safe.
         */
        store.addFile(UUID.randomUUID().toString(), baseName(browserName(file.getOriginalFilename())),
            file.getBytes(), file.getContentType());

        // **See the other page, not this one.** A refresh after a POST re-posts it, which is
        // how somebody uploads the same file three times by pressing F5.
        return backToThePage();
    }

    /**
     * Undo what a browser did to the filename on the way here.
     *
     * <p><b>A browser cannot put a double quote, a carriage return or a line feed inside the
     * quotes of a multipart header, so it sends them as {@code %22}, {@code %0D} and
     * {@code %0A}</b> (that is what the HTML standard says to do). Some servers decode those
     * for you and some do not, and Tomcat does not: a file called {@code my "best" notes.txt}
     * would otherwise be listed as {@code my %22best%22 notes.txt}. The Node and Python
     * versions of this sample decode them, so this does too, and the page shows the same name
     * whichever language is running it.
     */
    private static String browserName(String sent) {
        return sent == null ? "" : sent.replace("%22", "\"").replace("%0D", "\r").replace("%0A", "\n");
    }

    /** Only the last part of a path: some browsers send {@code C:\docs\notes.txt}. */
    private static String baseName(String sent) {
        String name = sent == null ? "" : sent;
        return name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    }

    @GetMapping("/files/{id}")
    public ResponseEntity<?> download(@PathVariable String id) throws SQLException {
        Optional<Store.StoredFile> file = store.fileById(id);
        if (file.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8")
                .body("There is no such file.");
        }

        InputStream body = store.readFile(file.get().s3Key());

        /*
         * **Always an attachment, and always `octet-stream`.** Anybody can upload anything
         * here, and an HTML file served inline would run as a page on this app's own address.
         * Saved to disk it is inert. `nosniff` stops a browser guessing a type of its own and
         * undoing the first two. Double quotes and backslashes are removed from the name so it
         * cannot end the quoted string it sits in.
         */
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + file.get().name().replaceAll("[\"\\\\]", "") + "\"")
            .body(new InputStreamResource(body));
    }

    @PostMapping("/files/{id}/delete")
    public ResponseEntity<Void> delete(@PathVariable String id) throws SQLException {
        store.removeFile(id);
        return backToThePage();
    }

    /**
     * Anything unhandled, said rather than swallowed: a blank page teaches nobody anything.
     *
     * <p>It lives in a "controller advice" because a file that is too big is refused by
     * Spring before any route above has started, and only an advice can answer for that. The
     * list of exceptions is on purpose short: Spring's own answers (a 405 for the wrong
     * method, say) should stay as they are.
     */
    @RestControllerAdvice
    static class Trouble {

        @ExceptionHandler({SQLException.class, IOException.class, SdkException.class,
                           MultipartException.class, IllegalStateException.class})
        ResponseEntity<String> said(Exception why) {
            log.error("request failed", why);
            return html(HttpStatus.INTERNAL_SERVER_ERROR,
                Page.page(List.of(), "Something went wrong: " + words(why)));
        }

        private static String words(Exception why) {
            if (why instanceof MaxUploadSizeExceededException) return "File too large";
            // Postgres's own sentence, without the driver's "Position: ..." lines around it.
            if (why instanceof PSQLException sql && sql.getServerErrorMessage() != null) {
                return sql.getServerErrorMessage().getMessage();
            }
            return String.valueOf(why.getMessage());
        }
    }
}
