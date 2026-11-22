# Datei-Uploads in Java: S3 für die Bytes, Postgres für alles andere

[English](README.md) | [Français](README.fr.md) | [Español](README.es.md) | [日本語](README.ja.md) | [Deutsch](README.de.md)

Eine Beispiel-App in Java (Spring Boot) für die [MitsuruTech Learning Platform](https://mitsurutech.co.uk/de/learning). Laden Sie eine Datei hoch; S3 bewahrt sie auf; Postgres merkt sich, wo sie liegt und wie sie heißt.
Die Tabelle auf der Seite kommt aus Postgres, und jeder Download kommt aus S3.

Sie ist absichtlich klein, vier Routen und vier Quelldateien, damit Sie das Ganze lesen können, bevor
Sie etwas daran ändern.

```
src/main/java/dev/learntools/uploads/
  Application.java   Spring Boot's entry point
  Web.java           the web: four routes and nothing else
  Store.java         the two stores, and the order they are written in
  Page.java          the HTML
```

## Hier bereitstellen

Öffnen Sie auf der Plattform **Deploy**, wählen Sie dieses Repository und starten Sie daneben eine
**Datenbank** und den **Cloud-Stack**. Alles, was die App braucht, steht dann bereits in ihrer
Umgebung; es gibt nichts zu konfigurieren. Die Plattform baut sie mit `mvn -q -DskipTests package` und
startet sie mit `java -jar target/*.jar`.

| Variable | Gesetzt von | Was sie ist |
| --- | --- | --- |
| `DATABASE_URL` | Ihrer Sitzung | der Postgres-Server, der als `db` läuft |
| `AWS_ENDPOINT_URL` | Ihrer Sitzung | der Cloud-Stack, der als `cloud` läuft |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION` | Ihrer Sitzung | die Zugangsdaten dafür |
| `PORT` | der Plattform | der Port, auf dem gelauscht wird |
| `BUCKET_NAME` | Ihnen, optional | Standard ist `uploads` |

## Die drei Dinge, die sich zu lesen lohnen

**1. `forcePathStyle(true)`, in `Store.java`.** Das ist die Einstellung, die alle zuerst
übersehen. Standardmäßig setzt das AWS SDK den Bucket in den Hostnamen, `uploads.cloud`,
und diesen Namen gibt es im Netzwerk Ihrer Sitzung nicht. Jeder Aufruf scheitert also mit einem
DNS-Fehler, der nichts über Buckets sagt. Der Pfadstil fragt stattdessen nach
`http://cloud:4566/uploads/...`.

**2. Die App legt ihren Bucket selbst an.** Ihr Cloud-Stack startet völlig leer, genau wie ein
echtes AWS-Konto, das Sie gerade eröffnet haben. `createBucket` ist Aufgabe Ihres Codes, es läuft
bei jedem Start und fängt „das gehört Ihnen schon“ ab, damit ein Neustart kein Absturz ist.

**3. Zuerst S3 schreiben, dann die Zeile. Zuerst die Zeile löschen, dann S3.**

- Laden Sie die Bytes hoch, dann fügen Sie die Zeile ein. Scheitert das Einfügen, haben Sie ein
  Objekt, auf das niemand verweist: unsichtbar, harmlos, aufräumbar.
- Schreiben Sie zuerst die Zeile, hinterlässt ein gescheiterter Upload eine Datei auf der Seite,
  die sich nicht öffnen lässt, und keine Möglichkeit, diese Zeile von einer guten zu unterscheiden.
- Beim Löschen geht es aus demselben Grund andersherum.

Der eine Fehler kostet ein paar verschwendete Bytes. Der andere ist eine kaputte Seite. Lassen Sie
immer das zuerst gehen, was harmlos verwaisen kann.

Ein Java-Detail, das man kennen sollte: `DATABASE_URL` sieht aus wie `postgres://user:password@host:5432/name`,
und der Java-Datenbanktreiber kann das nicht lesen. `Store.java` zerlegt sie in eine
`jdbc:postgresql://`-Adresse sowie einen Benutzer und ein Passwort, also in die Form, die JDBC erwartet.

## Zwei Sicherheitsgewohnheiten, in einem Beispiel absichtlich ernst genommen

**Der S3-Schlüssel ist eine UUID, nie der Dateiname.** Zwei Personen laden `notes.txt` hoch, und
die eine darf die andere nicht überschreiben; außerdem ist ein Name, der aus einem Browser kam,
nichts, woraus man einen Pfad baut: `../../etc/passwd` ist für ein Formular ein völlig gültiger
Dateiname. Der echte Name ist eine Spalte, und dort ist er sicher.

**Downloads sind immer `attachment`, immer `application/octet-stream`, immer `nosniff`.**
Jeder kann alles hochladen. Eine HTML-Datei, die inline ausgeliefert wird, liefe als Seite unter der
eigenen Adresse Ihrer App, mit allem, was diese Seite erreichen kann; auf der Festplatte gespeichert
ist sie harmlos.

Und jeder Dateiname wird auf dem Weg zurück auf die Seite maskiert: Eine Datei namens
`<img src=x onerror=alert(1)>` ist ein völlig korrekter Dateiname und darf nicht zu Markup werden.

## Auf dem eigenen Rechner ausführen

Sie brauchen Java 21, Maven, ein Postgres und etwas, das sich wie S3 verhält. Mit Docker:

```bash
docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=dev --name pg postgres:16
docker run -d -p 4566:4566 --name cloud localstack/localstack

export DATABASE_URL=postgres://postgres:dev@localhost:5432/postgres
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1

mvn -q -DskipTests package && java -jar target/app.jar
```

Öffnen Sie dann <http://localhost:8080>. Die Tests (sie prüfen, dass die Seite genau wie erwartet
aufgebaut wird) führen Sie mit `mvn test` aus.

## Was Sie als Nächstes ausprobieren können

- Einen großen Upload direkt nach S3 streamen, statt ihn im Speicher zu halten (`Web.java` sagt,
  wo).
- Einen Download-Zähler hinzufügen, in DynamoDB: Auch das emuliert Ihr Cloud-Stack.
- Die Dateien hinter eine Anmeldung legen, sodass die Tabelle pro Person gilt.
- Eine Bildvorschau zeigen, mit einer vorsignierten URL, statt die Bytes durchzureichen.

## Lizenz

Dieses Werk steht unter der Lizenz [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/). Sie dürfen es zum Lernen und Unterrichten teilen und bearbeiten, sofern Sie Mitsuru Technologies Ltd nennen, es nicht kommerziell nutzen und alles, was Sie daraus machen, unter derselben Lizenz teilen. Der vollständige Text steht in der Datei `LICENSE`.

## Über dieses Projekt

Erstellt von [Mitsuru Technologies](https://mitsurutech.co.uk/de/learning), dem Unternehmen hinter der MitsuruTech
Learning Platform für den Programmierunterricht. Dasselbe Beispiel gibt es auch in anderen Sprachen:
[github.com/mitsurutech/uploads-node](https://github.com/mitsurutech/uploads-node),
[uploads-python](https://github.com/mitsurutech/uploads-python) und
[uploads-go](https://github.com/mitsurutech/uploads-go).

Sie unterrichten eine Klasse? Sehen Sie sich [den Preis](https://mitsurutech.co.uk/de/pricing) an oder [legen Sie ein Schulkonto an](https://mitsurutech.co.uk/de/signup).
