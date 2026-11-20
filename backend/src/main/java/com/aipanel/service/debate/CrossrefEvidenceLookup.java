package com.aipanel.service.debate;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import com.aipanel.gateway.BoundedHttp;
import com.aipanel.gateway.ContentGuard;
import com.aipanel.gateway.ModelGatewayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 从跨学科索引召回 DOI，再读取出版方为该 DOI 登记的原始摘要。 */
@Component
public class CrossrefEvidenceLookup {
    private static final String WORKS = "https://api.crossref.org/works";
    private static final Pattern DOI = Pattern.compile("10\\.\\d{4,9}/[-._;()/:A-Za-z0-9]+");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper;
    private final ContentGuard guard;
    private final Clock clock;

    public CrossrefEvidenceLookup(ObjectMapper mapper, ContentGuard guard, Clock clock) {
        this.mapper = mapper;
        this.guard = guard;
        this.clock = clock;
    }

    public List<Article> search(String query, Instant deadline)
            throws IOException, InterruptedException, TimeoutException {
        var hits = read(WORKS + "?filter=has-abstract:true&rows=5&select=DOI,title&query.bibliographic="
                + encode(query), deadline).path("items");
        var articles = new ArrayList<Article>();
        var titles = new HashSet<String>();
        for (JsonNode hit : hits) {
            String doi = hit.path("DOI").asText();
            String title = text(hit.path("title").path(0));
            if (!DOI.matcher(doi).matches() || title.isBlank() || titles.contains(title)) {
                continue;
            }
            try {
                var record = read(WORKS + "/" + encode(doi), deadline);
                Article article = article(record);
                if (article != null && doi.equalsIgnoreCase(article.doi()) && title.equals(article.title())) {
                    articles.add(article);
                    titles.add(title);
                }
            } catch (IOException | TimeoutException unavailable) {
                if (!clock.instant().isBefore(deadline)) {
                    break;
                }
            }
            if (articles.size() == 3) {
                break;
            }
        }
        return List.copyOf(articles);
    }

    Article article(JsonNode record) {
        String title = text(record.path("title").path(0));
        String abstractText = text(record.path("abstract"));
        var authors = new ArrayList<String>();
        for (var author : record.path("author")) {
            String name = (author.path("given").asText("") + " " + author.path("family").asText("")).strip();
            if (name.isEmpty()) {
                name = author.path("name").asText("");
            }
            if (!name.isBlank()) {
                authors.add(name);
            }
        }
        String year = record.path("published").path("date-parts").path(0).path(0).asText("");
        String journal = text(record.path("container-title").path(0));
        String author = String.join(", ", authors);
        String doi = record.path("DOI").asText("");
        if (!DOI.matcher(doi).matches() || doi.length() > 256 || title.isBlank() || title.length() > 440
                || author.isBlank() || author.length() > 400 || journal.length() > 320 || !year.matches("\\d{4}")
                || abstractText.length() < 12 || abstractText.length() > 6500) {
            return null;
        }
        try {
            var item = new Article(title, author, journal, year, doi, abstractText);
            guard.checkOutput(mapper.valueToTree(item));
            return item;
        } catch (ModelGatewayException unsafe) {
            return null;
        }
    }

    private JsonNode read(String uri, Instant deadline) throws IOException, TimeoutException, InterruptedException {
        Duration time = Duration.between(clock.instant(), deadline);
        if (time.isNegative() || time.isZero()) {
            throw new TimeoutException("查证时间已用尽");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).timeout(time)
                .header("User-Agent", "AI-Panel-Studio/1.0").GET().build();
        var response = BoundedHttp.send(http, request, time, 256 * 1024);
        if (response.statusCode() != 200) {
            throw new IOException("文献索引未返回有效记录");
        }
        JsonNode body = mapper.readTree(response.body());
        if (body == null || !body.hasNonNull("message")) {
            throw new IOException("文献索引返回空记录");
        }
        return body.path("message");
    }

    private static String text(JsonNode field) {
        return HtmlUtils.htmlUnescape(field.asText("").replaceAll("<[^>]+>", " ")).replaceAll("\\s+", " ").strip();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record Article(String title, String author, String journal, String year, String doi, String abstractText) {
    }
}
