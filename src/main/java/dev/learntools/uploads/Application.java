package dev.learntools.uploads;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A file upload, end to end: the browser, S3 and Postgres.
 *
 * <p>Four routes and nothing else, so the whole thing can be read in one sitting (they are in
 * {@code Web.java}). How the two stores work is in {@code Store.java}, and the HTML is in
 * {@code Page.java}. This file only starts Spring Boot.
 *
 * <p><b>Stop rather than serve a broken app.</b> {@code Store} makes the bucket and the table
 * while Spring is building it. If that fails, Spring Boot stops the whole application and
 * exits with a non-zero code, and the reason is printed in the log. An app that starts
 * without its bucket or its table answers every request with the same error, and the logs
 * are the only place that says why; failing at the start puts the reason where somebody is
 * already looking.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
