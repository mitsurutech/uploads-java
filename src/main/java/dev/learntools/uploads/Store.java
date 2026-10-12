package dev.learntools.uploads;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * The two stores this app uses, and the one rule about the order they are written in.
 *
 * <p>S3 holds the bytes and Postgres holds everything else: who the file is called, how big
 * it is, when it arrived and which S3 key it is under. Neither knows about the other; this
 * class is the only place that knows about both.
 *
 * <p><b>Write S3 first, then the row. Delete the row first, then S3.</b> That order is the
 * whole of the reliability story here and it is worth understanding before you change
 * anything:
 *
 * <ul>
 *   <li>If the upload to S3 works and the insert then fails, you have an object nobody points
 *       at. Nothing is broken; it is a few bytes nobody can see, and a tidy-up job could sweep
 *       it later.
 *   <li>If you wrote the row first and the upload then failed, the page would list a file that
 *       cannot be opened. Somebody clicks it and gets an error, and there is no way to tell
 *       that row from a good one.
 * </ul>
 *
 * <p>The first is invisible, the second is a broken page. So always write the thing that can
 * be orphaned harmlessly first, and delete in the opposite order for the same reason.
 */
@Component
public class Store {

    private static final Logger log = LoggerFactory.getLogger(Store.class);

    /**
     * The bucket this app keeps its uploads in.
     *
     * <p><b>The app makes its own bucket and is not given one.</b> The cloud stack in your
     * session starts completely empty, exactly as a real AWS account you just opened would,
     * so {@code CreateBucket} is your code's job, and it is one of the things worth learning.
     * A name must be lower case and DNS-safe.
     */
    private final String bucket = env("BUCKET_NAME", "uploads");

    /**
     * The S3 client.
     *
     * <p><b>{@code forcePathStyle(true)} is the one setting you must not miss</b>, and
     * leaving it out is the mistake almost everybody makes first. By default the SDK
     * addresses a bucket as part of the hostname, {@code uploads.cloud}, and inside your
     * session there is no such name, so every call fails with a DNS error that says nothing
     * about buckets. Path style asks for {@code http://cloud:4566/uploads/...} instead, which
     * is a name that exists.
     *
     * <p>The endpoint and the region are read from the environment here. The credentials are
     * not mentioned at all: the SDK's default provider chain reads {@code AWS_ACCESS_KEY_ID}
     * and {@code AWS_SECRET_ACCESS_KEY} by itself, which is why they do not appear below.
     *
     * <p>The HTTP client is the small one built into the JDK ({@code url-connection-client}),
     * so the app starts quickly and carries fewer libraries.
     */
    private final S3Client s3 = buildS3();

    private static S3Client buildS3() {
        var builder = S3Client.builder()
            .region(Region.of(env("AWS_REGION", "us-east-1")))
            .forcePathStyle(true)
            .httpClient(UrlConnectionHttpClient.builder().build());
        String endpoint = System.getenv("AWS_ENDPOINT_URL");
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /** The JDBC address, the user and the password, taken out of {@code DATABASE_URL}. */
    private final String jdbcUrl;
    private final Properties login = new Properties();

    /**
     * The database your session started, at the name {@code db}. {@code DATABASE_URL} is set
     * for you.
     *
     * <p><b>It looks like {@code postgres://user:password@host:5432/name}, and Java's
     * database driver cannot read that.</b> JDBC wants {@code jdbc:postgresql://host:5432/name}
     * with the user and the password given separately, so this takes the address apart and
     * puts it back together in the shape the driver understands. A user name or password may
     * contain characters written as {@code %40} and the like, so those are decoded.
     */
    public Store() {
        String url = System.getenv("DATABASE_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("DATABASE_URL is not set");
        }
        URI uri = URI.create(url.trim());
        String info = uri.getRawUserInfo();
        if (info != null) {
            int colon = info.indexOf(':');
            login.setProperty("user", decode(colon < 0 ? info : info.substring(0, colon)));
            if (colon >= 0) login.setProperty("password", decode(info.substring(colon + 1)));
        }
        int port = uri.getPort() < 0 ? 5432 : uri.getPort();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getRawPath() + query;
    }

    /** {@code URLDecoder} turns a plus into a space, which is right in a form and wrong here. */
    private static String decode(String part) {
        return URLDecoder.decode(part.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    /**
     * <b>A connection for each piece of work, closed when it is done.</b> The Node sample uses
     * a pool; for an app this small, opening one when needed is simpler to read and costs a
     * few milliseconds. {@code try}-with-resources closes it even when something goes wrong.
     */
    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, login);
    }

    /**
     * Make the bucket and the table, if they are not there already.
     *
     * <p><b>Run on every start and safe to run again</b>, which is what {@code IF NOT EXISTS}
     * and catching "you already own this bucket" are for. An app that only works the first
     * time it starts is an app that breaks the moment it restarts. {@code @PostConstruct}
     * makes Spring call it as soon as this object exists, before the first request, and an
     * exception here stops the application from starting.
     */
    @PostConstruct
    void setUp() throws SQLException {
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("made the bucket {}", bucket);
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException already) {
            // Already there, and made by us. Any other failure is real and should stop the
            // app, because an app that cannot reach its store has nothing useful to do.
        }

        try (Connection db = connect(); Statement sql = db.createStatement()) {
            sql.execute("""
                CREATE TABLE IF NOT EXISTS files (
                  id          bigserial PRIMARY KEY,
                  -- **The key, which is where the bytes are.** A uuid rather than the name somebody
                  -- uploaded: two people may upload "notes.txt", and a key built from a name is a
                  -- file that quietly replaces another one.
                  s3_key      text NOT NULL,
                  -- What to call it on the page and in the download. Kept apart from the key for the
                  -- reason above.
                  name        text NOT NULL,
                  size_bytes  bigint NOT NULL,
                  uploaded_at timestamptz NOT NULL DEFAULT now()
                )
                """);
        }
        log.info("the files table is ready");
    }

    /** One row of the {@code files} table, with the S3 key the bytes are under. */
    public record StoredFile(long id, String s3Key, String name, long sizeBytes) {}

    /** Every file, newest first. */
    public List<Page.Row> listFiles() throws SQLException {
        try (Connection db = connect();
             PreparedStatement sql = db.prepareStatement(
                 "SELECT id, name, size_bytes, uploaded_at FROM files "
                     + "ORDER BY uploaded_at DESC, id DESC");
             ResultSet rows = sql.executeQuery()) {
            List<Page.Row> files = new ArrayList<>();
            while (rows.next()) {
                files.add(new Page.Row(rows.getLong("id"), rows.getString("name"),
                    rows.getLong("size_bytes"), rows.getTimestamp("uploaded_at").toInstant()));
            }
            return files;
        }
    }

    /**
     * Keep one file: the bytes in S3, then the row in Postgres.
     *
     * <p>See this class's own note on why that order and not the other.
     */
    public void addFile(String key, String name, byte[] bytes, String type) throws SQLException {
        s3.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(type == null || type.isEmpty() ? "application/octet-stream" : type)
                .build(),
            RequestBody.fromBytes(bytes));

        try (Connection db = connect();
             PreparedStatement sql = db.prepareStatement(
                 "INSERT INTO files (s3_key, name, size_bytes) VALUES (?, ?, ?)")) {
            sql.setString(1, key);
            sql.setString(2, name);
            sql.setLong(3, bytes.length);
            sql.executeUpdate();
        }
    }

    /**
     * One file's row, or empty.
     *
     * <p>The id arrives from the address bar as text. It is sent to Postgres as text and
     * turned into a number there ({@code ?::bigint}), as the Node sample does, so a made-up
     * id such as {@code abc} is Postgres's error to give and not a case to handle here.
     */
    public Optional<StoredFile> fileById(String id) throws SQLException {
        try (Connection db = connect()) {
            return fileById(db, id);
        }
    }

    private Optional<StoredFile> fileById(Connection db, String id) throws SQLException {
        try (PreparedStatement sql = db.prepareStatement(
            "SELECT id, s3_key, name, size_bytes FROM files WHERE id = ?::bigint")) {
            sql.setObject(1, id, Types.VARCHAR);
            try (ResultSet rows = sql.executeQuery()) {
                return rows.next()
                    ? Optional.of(new StoredFile(rows.getLong("id"), rows.getString("s3_key"),
                        rows.getString("name"), rows.getLong("size_bytes")))
                    : Optional.empty();
            }
        }
    }

    /**
     * The bytes back out of S3, as a stream. The caller closes it, which also gives the
     * connection to S3 back.
     */
    public InputStream readFile(String key) {
        return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    }

    /**
     * Forget one file: the row first, then the bytes.
     *
     * <p><b>The opposite order from writing, and for the same reason.</b> Delete the bytes
     * first and a failure between the two leaves a row pointing at nothing, which is a broken
     * download. Delete the row first and the worst case is an object nobody points at.
     */
    public boolean removeFile(String id) throws SQLException {
        Optional<StoredFile> file = fileById(id);
        if (file.isEmpty()) return false;

        try (Connection db = connect();
             PreparedStatement sql = db.prepareStatement("DELETE FROM files WHERE id = ?")) {
            sql.setLong(1, file.get().id());
            sql.executeUpdate();
        }
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(file.get().s3Key()).build());
        return true;
    }
}
